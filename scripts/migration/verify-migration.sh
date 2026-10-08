#!/usr/bin/env bash
# Rehearses the data side of svc-of-payee-verification on a throwaway
# PostgreSQL database: schema migrations (db/migration, in Flyway version
# order), the core-banking directory import (db/import), then the optional
# sample seed (db/seed) with an assertion that it left imported rows alone,
# re-runs, changed and stale rows, reserved SAMPLE rows and a rejected file.
# Exits non-zero on any mismatch.
#
# Needs psql and a server reachable through PGHOST/PGPORT/PGUSER/PGPASSWORD
# with the right to create databases (CI: deploy/data-migration-rehearsal).
set -euo pipefail

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

echo "== fresh database $db"
psql_admin -c "DROP DATABASE IF EXISTS \"$db\""
psql_admin -c "CREATE DATABASE \"$db\""

echo "== migrations (db/migration)"
psql_db -c "CREATE SCHEMA $schema"
for file in $(ls "$root"/src/main/resources/db/migration/V*__*.sql | sort -V); do
  echo "   $(basename "$file")"
  PGOPTIONS="-c search_path=$schema" psql_db -f "$file"
done
expect "tables" "dpop_proof_replay,outbox_event,payee_directory_entry,payee_verification" \
  "select string_agg(table_name, ',' order by table_name) from information_schema.tables where table_schema = '$schema'"
expect "holder_name classified as PII" "t" \
  "select col_description('$schema.payee_directory_entry'::regclass, 4) like 'PII%'"

# Snapshots are md5 digests so holder names never reach the log.
imported="select md5(string_agg(t::text, '|' order by scheme_name, identification)) from (select scheme_name, identification, holder_name, account_type, account_status, updated_at from $schema.payee_directory_entry where scheme_name <> 'SAMPLE') t"
sample="select md5(string_agg(t::text, '|' order by identification)) from (select scheme_name, identification, holder_name, account_type, account_status, updated_at from $schema.payee_directory_entry where scheme_name = 'SAMPLE') t"
seed() { PGOPTIONS="-c search_path=$schema" psql_db -f "$root"/src/main/resources/db/seed/R__seed_sample_payee_directory.sql; }

echo "== import example export, twice (idempotent)"
export PGDATABASE="$db"
"$root"/db/import/import-payee-directory.sh "$root"/db/import/payee-directory.example.csv
"$root"/db/import/import-payee-directory.sh "$root"/db/import/payee-directory.example.csv | tee "$work/second.txt"
grep -Eq '^ +4 \| +0 \| +0 \| +4$' "$work/second.txt" || { echo "FAIL second import was not a no-op" >&2; exit 1; }
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
"$root"/db/import/import-payee-directory.sh "$work/changes.csv"
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
if "$root"/db/import/import-payee-directory.sh "$work/reserved.csv" 2>"$work/reserved.err"; then
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
if "$root"/db/import/import-payee-directory.sh "$work/bad.csv" 2>"$work/bad.err"; then
  echo "FAIL invalid file was accepted" >&2; exit 1
fi
grep -q "invalid row" "$work/bad.err" && echo "ok   invalid file rejected"
expect "no partial load" "t" \
  "select holder_name = 'Example Holder, Jr.' from $schema.payee_directory_entry where identification = 'AE070331234567890123456'"

echo "== decisions are insert-only"
psql_db -c "insert into $schema.payee_verification values ('7f0c3c1e-4b8e-4d2a-9a51-0c1d2e3f4a5b', 'tpp-rehearsal', 'ix-1', repeat('a', 64), 'ACTIVE', 'MATCH', 'EXACT_NAME_MATCH', 100, now())"
if psql_db -c "update $schema.payee_verification set match_outcome = 'NO_MATCH'" 2>/dev/null; then
  echo "FAIL payee_verification accepted an update" >&2; exit 1
fi
echo "ok   update rejected"
if psql_db -c "insert into $schema.payee_verification values (gen_random_uuid(), 'tpp-rehearsal', 'ix-1', repeat('a', 64), 'ACTIVE', 'MATCH', 'EXACT_NAME_MATCH', 100, now())" 2>/dev/null; then
  echo "FAIL duplicate idempotency key accepted" >&2; exit 1
fi
echo "ok   duplicate (tpp_id, interaction_id) rejected"

if [ "${KEEP_REHEARSAL_DB:-false}" != "true" ]; then
  psql_admin -c "DROP DATABASE \"$db\""
fi
echo "migration rehearsal passed"
