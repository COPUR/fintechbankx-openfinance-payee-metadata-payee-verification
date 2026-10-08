#!/usr/bin/env bash
# Rehearses the data side of svc-of-payee-verification on a throwaway
# PostgreSQL database: schema migrations (db/migration, in Flyway version
# order), the core-banking directory import (db/import), then the optional
# sample seed (db/seed) with an assertion that it left imported rows alone,
# re-runs, changed and stale rows, reserved SAMPLE rows and a rejected file.
# Exits non-zero on any mismatch.
#
# Runs db/bootstrap/bootstrap-roles.sql, migrates and seeds as
# payee_verification_migrate, imports as payee_verification_import and checks
# what payee_verification_app may do, as in a real environment. Needs psql and an
# admin role that can create databases and roles, reached over TCP through
# PGHOST/PGPORT/PGUSER/PGPASSWORD (CI: deploy/data-migration-rehearsal). The three
# roles are dropped at the end unless another database still references them.
set -euo pipefail
: "${PGHOST:?set PGHOST: the roles log in with a password over TCP}"

root="$(cd "$(dirname "$0")/../.." && pwd)"
db="${REHEARSAL_DB:-db_of_payee_verification_rehearsal}"
schema=sc_of_payee_verification
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

psql_admin() { psql --no-psqlrc -v ON_ERROR_STOP=1 -q -d "${PGDATABASE_ADMIN:-postgres}" "$@"; }
psql_db() { psql --no-psqlrc -v ON_ERROR_STOP=1 -q -d "$db" "$@"; }
value() { psql_db -At -c "$1"; }
expect() {
  local label="$1" expected="$2" actual
  actual="$(value "$3")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label = $actual"
}

new_password() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n'; }
pw_migrate="$(new_password)"; pw_app="$(new_password)"; pw_import="$(new_password)"
# as_role <role> <psql args...>: connect to the rehearsal database as that role.
as_role() {
  local role="$1" pw; shift
  case "$role" in
    payee_verification_migrate) pw="$pw_migrate" ;;
    payee_verification_app) pw="$pw_app" ;;
    payee_verification_import) pw="$pw_import" ;;
  esac
  PGUSER="$role" PGPASSWORD="$pw" PGOPTIONS="-c search_path=$schema" \
    psql --no-psqlrc -v ON_ERROR_STOP=1 -q -d "$db" "$@"
}
# Flyway labels its session payee-verification-flyway (spring.flyway.init-sqls).
as_flyway() { PGAPPNAME=payee-verification-flyway as_role payee_verification_migrate "$@"; }
import_as() {
  PGUSER=payee_verification_import PGPASSWORD="$pw_import" PGDATABASE="$db" \
    "$root"/db/import/import-payee-directory.sh "$@"
}

echo "== fresh database $db"
psql_admin -c "DROP DATABASE IF EXISTS \"$db\""
psql_admin -c "CREATE DATABASE \"$db\""

echo "== DBA bootstrap (db/bootstrap/bootstrap-roles.sql)"
psql_db -f "$root"/db/bootstrap/bootstrap-roles.sql
psql_db -c "ALTER ROLE payee_verification_migrate PASSWORD '$pw_migrate'" \
  -c "ALTER ROLE payee_verification_app PASSWORD '$pw_app'" \
  -c "ALTER ROLE payee_verification_import PASSWORD '$pw_import'"

echo "== migrations (db/migration) as the schema owner"
as_flyway -c "CREATE SCHEMA $schema"
for file in $(ls "$root"/src/main/resources/db/migration/V*__*.sql | sort -V); do
  echo "   $(basename "$file")"
  as_flyway -f "$file"
done
expect "tables" "dpop_proof_replay,outbox_event,payee_directory_entry,payee_directory_entry_history,payee_verification" \
  "select string_agg(table_name, ',' order by table_name) from information_schema.tables where table_schema = '$schema'"
expect "holder_name classified as PII" "t" \
  "select col_description('$schema.payee_directory_entry'::regclass, 4) like 'PII%'"

# Snapshots are md5 digests so holder names never reach the log.
imported="select md5(string_agg(t::text, '|' order by scheme_name, identification)) from (select scheme_name, identification, holder_name, account_type, account_status, updated_at from $schema.payee_directory_entry where scheme_name <> 'SAMPLE') t"
sample="select md5(string_agg(t::text, '|' order by identification)) from (select scheme_name, identification, holder_name, account_type, account_status, updated_at from $schema.payee_directory_entry where scheme_name = 'SAMPLE') t"
seed() { as_flyway -f "$root"/src/main/resources/db/seed/R__seed_sample_payee_directory.sql; }

echo "== import example export, twice (idempotent)"
import_as "$root"/db/import/payee-directory.example.csv
import_as "$root"/db/import/payee-directory.example.csv | tee "$work/second.txt"
grep -Eq '^ +4 \| +0 \| +0 \| +4 \| +0$' "$work/second.txt" || { echo "FAIL second import was not a no-op" >&2; exit 1; }
expect "entries after import" "4" "select count(*) from $schema.payee_directory_entry"
expect "normalised identification" "1" \
  "select count(*) from $schema.payee_directory_entry where scheme_name = 'IBAN' and identification = 'AE770330000000987654321'"
after_import="$(value "$imported")"

echo "== import, then seed, then assert (a dev/CI pod start after an import)"
seed
expect "seed adds only its SAMPLE rows" "5" "select count(*) from $schema.payee_directory_entry where scheme_name = 'SAMPLE' and identification like 'SAMPLE-%'"
expect "seed leaves every imported row as imported" "$after_import" "$imported"
after_seed="$(value "$sample")"
seed
expect "re-applied seed changes nothing" "$after_seed" "$sample"

echo "== changed and stale rows"
cat > "$work/changes.csv" <<'CSV'
scheme_name,identification,holder_name,account_type,account_status,updated_at
IBAN,AE770330000000987654321,Atlas Services LLC,BUSINESS,CLOSED,2026-10-01T08:00:00Z
IBAN,AE280330000000123456789,Stale Name LLC,BUSINESS,ACTIVE,2025-01-01T00:00:00Z
CSV
import_as "$work/changes.csv"
expect "newer status applied" "CLOSED" \
  "select account_status from $schema.payee_directory_entry where identification = 'AE770330000000987654321'"
expect "stale row ignored" "t" \
  "select holder_name = 'Al Tareq Trading LLC' from $schema.payee_directory_entry where identification = 'AE280330000000123456789'"
after_change="$(value "$imported")"
seed
expect "seed never reverts an imported change" "$after_change" "$imported"
expect "import never touches SAMPLE rows" "$after_seed" "$sample"

echo "== reserved SAMPLE rows are refused"
cat > "$work/reserved.csv" <<'CSV'
scheme_name,identification,holder_name,account_type,account_status,updated_at
SAMPLE,SAMPLE-AE280330000000123456789,Overwritten LLC,BUSINESS,CLOSED,2027-01-01T00:00:00Z
CSV
if import_as "$work/reserved.csv" 2>"$work/reserved.err"; then
  echo "FAIL import accepted a SAMPLE row" >&2; exit 1
fi
grep -q "reserved SAMPLE" "$work/reserved.err" && echo "ok   SAMPLE row rejected"
expect "reserved import changed nothing" "$after_seed" "$sample"

echo "== invalid file is rejected as a whole"
cat > "$work/bad.csv" <<'CSV'
scheme_name,identification,holder_name,account_type,account_status,updated_at
IBAN,AE070331234567890123456,Changed Holder,PERSONAL,ACTIVE,2026-10-02T00:00:00Z
IBAN,AE080330000000333333333,Somebody,PERSONAL,FROZEN,2026-10-02T00:00:00Z
CSV
if import_as "$work/bad.csv" 2>"$work/bad.err"; then
  echo "FAIL invalid file was accepted" >&2; exit 1
fi
grep -q "invalid row" "$work/bad.err" && echo "ok   invalid file rejected"
expect "no partial load" "t" \
  "select holder_name = 'Example Holder, Jr.' from $schema.payee_directory_entry where identification = 'AE070331234567890123456'"

echo "== timestamps need an explicit offset"
cat > "$work/no-offset.csv" <<'CSV'
scheme_name,identification,holder_name,account_type,account_status,updated_at
IBAN,AE280330000000123456789,Al Tareq Trading LLC,BUSINESS,CLOSED,2026-10-05T08:00:00
CSV
if import_as "$work/no-offset.csv" 2>"$work/no-offset.err"; then
  echo "FAIL an updated_at without offset was accepted" >&2; exit 1
fi
grep -q "explicit offset" "$work/no-offset.err" && echo "ok   updated_at without offset rejected"
expect "rejected file changed nothing" "ACTIVE" \
  "select account_status from $schema.payee_directory_entry where identification = 'AE280330000000123456789'"
printf 'scheme_name,identification,holder_name,account_type,account_status,updated_at\nIBAN,AE280330000000123456789,Al Tareq Trading LLC,BUSINESS,ACTIVE,2026-09-30T14:00:00+04:00\n' > "$work/offset.csv"
import_as "$work/offset.csv" >/dev/null
expect "+04:00 offset read as the same UTC instant (no change)" "2026-09-30 10:00:00+00" \
  "select updated_at at time zone 'UTC' || '+00' from $schema.payee_directory_entry where identification = 'AE280330000000123456789'"

echo "== full import (complete export as of an instant)"
if import_as --full "$root"/db/import/payee-directory.example.csv 2>/dev/null; then
  echo "FAIL --full without --as-of was accepted" >&2; exit 1
fi
echo "ok   --full requires --as-of"
grep -v '^IBAN,AE070331234567890123456,' "$root"/db/import/payee-directory.example.csv > "$work/full.csv"
import_as --full --as-of 2026-09-01T00:00:00Z --max-close-percent 100 "$work/full.csv" >/dev/null
expect "an account changed after the export instant is not closed" "ACTIVE" \
  "select account_status from $schema.payee_directory_entry where identification = 'AE070331234567890123456'"
before_full="$(value "$imported")"
if import_as --full --as-of 2026-10-01T00:00:00Z "$work/full.csv" 2>"$work/full.err"; then
  echo "FAIL full import closed 1 of 2 active accounts past the 10 % guard" >&2; exit 1
fi
grep -q "would close 1 of 2 active accounts" "$work/full.err" && echo "ok   guard rejected the full import"
expect "guarded full import changed nothing" "$before_full" "$imported"
import_as --full --as-of 2026-10-01T00:00:00Z --max-close-percent 50 "$work/full.csv" | tee "$work/full.txt"
grep -Eq '\| +1$' "$work/full.txt" || { echo "FAIL full import did not report one closed account" >&2; exit 1; }
expect "missing account closed as of the export" "CLOSED|2026-10-01 00:00:00" \
  "select account_status || '|' || (updated_at at time zone 'UTC') from $schema.payee_directory_entry where identification = 'AE070331234567890123456'"
expect "full mode never touches SAMPLE rows" "$after_seed" "$sample"

echo "== decisions are insert-only (as the runtime role)"
as_role payee_verification_app -c "insert into payee_verification values ('7f0c3c1e-4b8e-4d2a-9a51-0c1d2e3f4a5b', 'tpp-rehearsal', 'ix-1', repeat('a', 64), 'ACTIVE', 'MATCH', 'EXACT_NAME_MATCH', 100, now())"
if as_role payee_verification_app -c "update payee_verification set match_outcome = 'NO_MATCH'" 2>/dev/null; then
  echo "FAIL payee_verification accepted an update" >&2; exit 1
fi
echo "ok   update rejected"
if as_role payee_verification_app -c "insert into payee_verification values (gen_random_uuid(), 'tpp-rehearsal', 'ix-1', repeat('a', 64), 'ACTIVE', 'MATCH', 'EXACT_NAME_MATCH', 100, now())" 2>/dev/null; then
  echo "FAIL duplicate idempotency key accepted" >&2; exit 1
fi
echo "ok   duplicate (tpp_id, interaction_id) rejected"

echo "== least privilege"
allowed() {
  local label="$1" role="$2" sql="$3"
  as_role "$role" -c "$sql" >/dev/null || { echo "FAIL $label: $role was refused: $sql" >&2; exit 1; }
  echo "ok   $label"
}
denied() {
  local label="$1" role="$2" sql="$3"
  if as_role "$role" -c "$sql" >/dev/null 2>"$work/denied.err"; then
    echo "FAIL $label: $role was allowed: $sql" >&2; exit 1
  fi
  grep -Eq "permission denied|append-only" "$work/denied.err" || { cat "$work/denied.err" >&2; exit 1; }
  echo "ok   $label"
}
app=payee_verification_app; imp=payee_verification_import; own=payee_verification_migrate
allowed "app reads the directory" $app "select count(*) from payee_directory_entry"
allowed "app records a decision and reads it back" $app "select count(*) from payee_verification"
allowed "app writes, relays and purges the outbox" $app "insert into outbox_event (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, topic, payload, correlation_id, occurred_at) values (gen_random_uuid(), 'PayeeVerification', 'x', 0, 'x', 'evt.of.payee.rehearsal.v1', '{}', 'ix-1', now()); update outbox_event set published_at = now(); delete from outbox_event"
allowed "app checks and purges DPoP replays" $app "insert into dpop_proof_replay values (repeat('b', 64), now()) on conflict (proof_key) do nothing; delete from dpop_proof_replay where expires_at < now() + interval '1 day'"
denied "app cannot change the directory" $app "update payee_directory_entry set account_status = 'CLOSED'"
denied "app cannot insert into the directory" $app "insert into payee_directory_entry (scheme_name, identification, holder_name, account_type, account_status, updated_at) values ('X', 'X', 'x', 'PERSONAL', 'ACTIVE', now())"
denied "app cannot delete decisions" $app "delete from payee_verification"
denied "app cannot read the directory history" $app "select count(*) from payee_directory_entry_history"
denied "import cannot delete from the directory" $imp "delete from payee_directory_entry"
denied "import cannot read decisions" $imp "select count(*) from payee_verification"
denied "import cannot read the history" $imp "select count(*) from payee_directory_entry_history"
denied "history is append-only, even for the owner" $own "update payee_directory_entry_history set changed_by = 'x'"
denied "history cannot be deleted, even by the owner" $own "delete from payee_directory_entry_history"
denied "history cannot be truncated, even by the owner" $own "truncate payee_directory_entry_history"
expect "schema owned by the migrate role" "payee_verification_migrate" \
  "select nspowner::regrole from pg_namespace where nspname = '$schema'"
expect "runtime and import roles own nothing" "0" \
  "select count(*) from pg_class where relowner in ('payee_verification_app'::regrole, 'payee_verification_import'::regrole)"

echo "== audit trail"
expect "imports recorded with role and application" "4:2" \
  "select count(*) filter (where operation = 'INSERT') || ':' || count(*) filter (where operation = 'UPDATE') from $schema.payee_directory_entry_history where changed_by = 'payee_verification_import' and application_name = 'payee-verification-import'"
expect "the update keeps old and new status" "ACTIVE>CLOSED" \
  "select old_account_status || '>' || new_account_status from $schema.payee_directory_entry_history where operation = 'UPDATE' and identification = 'AE770330000000987654321'"
expect "seed inserts attributed to the migration role" "5:payee-verification-flyway" \
  "select count(*) || ':' || min(application_name) from $schema.payee_directory_entry_history where scheme_name = 'SAMPLE' and changed_by = 'payee_verification_migrate'"
expect "history holds no holder names, only digests" "0" \
  "select count(*) from $schema.payee_directory_entry_history h join $schema.payee_directory_entry e using (entry_id) where h::text like '%' || e.holder_name || '%'"
expect "name digest matches the directory row" "t" \
  "select bool_and(h.new_holder_name_sha256 = encode(sha256(convert_to(e.holder_name, 'UTF8')), 'hex')) from $schema.payee_directory_entry_history h join $schema.payee_directory_entry e using (entry_id) where h.operation = 'INSERT'"
expect "history is classified like the main table" "t" \
  "select col_description('$schema.payee_directory_entry_history'::regclass, 7) like 'PII%'"

if [ "${KEEP_REHEARSAL_DB:-false}" != "true" ]; then
  psql_admin -c "DROP DATABASE \"$db\""
  for role in payee_verification_migrate payee_verification_app payee_verification_import; do
    psql_admin -c "DROP ROLE $role" 2>/dev/null || echo "note: role $role kept (still referenced elsewhere)"
  done
fi
echo "migration rehearsal passed"
