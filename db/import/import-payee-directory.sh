#!/usr/bin/env bash
# Loads account-holder rows exported from core banking into
# sc_of_payee_verification.payee_directory_entry (idempotent upsert).
#
# Until an accounts service owns this data and publishes account events
# (docs/architecture/decisions/ADR-0001-payee-directory-projection.md), the
# directory is refreshed from a core-banking export with this script.
#
# Usage: PGHOST=... PGDATABASE=db_of_payee_verification_<env> PGUSER=payee_verification_import \
#        PGPASSWORD=... db/import/import-payee-directory.sh <export.csv>
#
# Runs as the import role payee_verification_import (SELECT/INSERT/UPDATE on
# payee_directory_entry only; secret <env>/payee-verification-service/db-import).
# Every inserted or updated row is recorded in payee_directory_entry_history
# (role, application name, time, old/new values; names only as SHA-256).
#
# CSV header: scheme_name,identification,holder_name,account_type,account_status,updated_at
#   account_type   PERSONAL | BUSINESS
#   account_status ACTIVE | CLOSED | DECEASED
#   updated_at     ISO-8601 instant at which core banking last changed the account,
#                  with an explicit offset (2026-09-30T10:00:00Z or +04:00); a value
#                  without one is rejected rather than read in a session time zone
#
# Rules:
# - scheme and identification are normalised (upper case, spaces removed),
#   exactly as the service normalises a request;
# - a new account is inserted; an existing one is updated only if the export
#   row is at least as recent (updated_at) AND something changed, so
#   re-running the same file changes nothing and an older export cannot
#   overwrite newer data;
# - delta mode (default): accounts missing from the file are left as they are
#   (close them with account_status CLOSED in the export);
# - full mode (--full --as-of <instant>): the file is the complete signed-off
#   export taken at <instant>. Every ACTIVE account missing from it is set to
#   CLOSED with updated_at = <instant>, unless the row was changed after
#   <instant>. The run is rolled back if it would close more than
#   --max-close-percent (default 10) of the ACTIVE accounts, a guard against a
#   truncated export. Rows are never deleted;
# - the whole file is one transaction: one bad row rejects the load, and so
#   does a row in the SAMPLE scheme or with a SAMPLE- identification (reserved
#   for the dev/CI seed, which the import therefore never touches).
# Holder names are personal data: this script never prints them.
set -euo pipefail

usage() { echo "usage: import-payee-directory.sh [--full --as-of <ISO-8601 instant with offset> [--max-close-percent N]] <export.csv>" >&2; exit 2; }
full=false
as_of=""
max_close_percent=10
while [ "$#" -gt 0 ]; do
  case "$1" in
    --full) full=true; shift ;;
    --as-of) [ "$#" -ge 2 ] || usage; as_of="$2"; shift 2 ;;
    --max-close-percent) [ "$#" -ge 2 ] || usage; max_close_percent="$2"; shift 2 ;;
    --) shift; break ;;
    -*) usage ;;
    *) break ;;
  esac
done
[ "$#" -eq 1 ] || usage
csv="$1"
offset_re='^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}(:[0-9]{2}(\.[0-9]+)?)?(Z|[+-][0-9]{2}(:?[0-9]{2})?)$'
if [ "$full" = true ]; then
  [[ "$as_of" =~ $offset_re ]] || { echo "--full needs --as-of <ISO-8601 instant with offset>, e.g. 2026-10-01T00:00:00Z" >&2; exit 2; }
fi
case "$max_close_percent" in ''|*[!0-9]*) usage ;; esac
[ "$max_close_percent" -le 100 ] || usage
[ -n "$as_of" ] || as_of="1970-01-01T00:00:00Z"
schema="${PAYEE_SCHEMA:-sc_of_payee_verification}"
[ -r "$csv" ] || { echo "cannot read $csv" >&2; exit 2; }
case "$schema" in
  *[!a-z0-9_]*) echo "invalid schema name" >&2; exit 2 ;;
esac

# Recorded in payee_directory_entry_history.application_name for every row this run changes.
export PGAPPNAME="${PGAPPNAME:-payee-verification-import}"

psql --no-psqlrc --quiet -v ON_ERROR_STOP=1 --set=schema="$schema" --set=full="$full" \
     --set=as_of="$as_of" --set=max_close_percent="$max_close_percent" <<SQL
\\set QUIET on
BEGIN;
SET LOCAL search_path TO :"schema";
-- Offsets are mandatory (checked below); UTC only governs how results print.
SET LOCAL TimeZone TO 'UTC';

CREATE TEMP TABLE payee_directory_import (
    scheme_name     TEXT,
    identification  TEXT,
    holder_name     TEXT,
    account_type    TEXT,
    account_status  TEXT,
    updated_at      TEXT
) ON COMMIT DROP;

\\copy payee_directory_import FROM '$csv' WITH (FORMAT csv, HEADER true)

DO \$\$
DECLARE
    bad INTEGER;
BEGIN
    SELECT count(*) INTO bad FROM payee_directory_import
    WHERE btrim(coalesce(updated_at, '')) !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}(:[0-9]{2}(\.[0-9]+)?)?(Z|[+-][0-9]{2}(:?[0-9]{2})?)\$';
    IF bad > 0 THEN
        RAISE EXCEPTION 'import rejected: % row(s) with an updated_at that is not an ISO-8601 instant with an explicit offset (Z or +hh:mm)', bad;
    END IF;
END
\$\$;

CREATE TEMP TABLE payee_directory_normalised ON COMMIT DROP AS
SELECT DISTINCT ON (scheme_name, identification)
       scheme_name, identification, holder_name, account_type, account_status, updated_at
FROM (
    SELECT upper(btrim(scheme_name))                          AS scheme_name,
           upper(replace(btrim(identification), ' ', ''))     AS identification,
           btrim(holder_name)                                 AS holder_name,
           upper(btrim(account_type))                         AS account_type,
           upper(btrim(account_status))                       AS account_status,
           btrim(updated_at)::timestamptz                     AS updated_at
    FROM payee_directory_import
) n
ORDER BY scheme_name, identification, updated_at DESC;

DO \$\$
DECLARE
    bad INTEGER;
BEGIN
    SELECT count(*) INTO bad FROM payee_directory_normalised
    WHERE coalesce(scheme_name, '') = '' OR coalesce(identification, '') = ''
       OR coalesce(holder_name, '') = '' OR length(holder_name) > 140
       OR account_type NOT IN ('PERSONAL', 'BUSINESS')
       OR account_status NOT IN ('ACTIVE', 'CLOSED', 'DECEASED')
       OR updated_at IS NULL;
    IF bad > 0 THEN
        RAISE EXCEPTION 'import rejected: % invalid row(s)', bad;
    END IF;
    -- Scheme SAMPLE and SAMPLE- identifications belong to the dev/CI seed (db/seed).
    SELECT count(*) INTO bad FROM payee_directory_normalised
    WHERE scheme_name LIKE 'SAMPLE%' OR identification LIKE 'SAMPLE-%';
    IF bad > 0 THEN
        RAISE EXCEPTION 'import rejected: % row(s) use the reserved SAMPLE scheme or SAMPLE- prefix of the dev/CI seed', bad;
    END IF;
END
\$\$;

CREATE TEMP TABLE payee_directory_result (action TEXT) ON COMMIT DROP;

WITH upserted AS (
    INSERT INTO payee_directory_entry AS t
           (scheme_name, identification, holder_name, account_type, account_status, updated_at)
    SELECT scheme_name, identification, holder_name, account_type, account_status, updated_at
    FROM payee_directory_normalised
    ON CONFLICT (scheme_name, identification) DO UPDATE
        SET holder_name    = EXCLUDED.holder_name,
            account_type   = EXCLUDED.account_type,
            account_status = EXCLUDED.account_status,
            updated_at     = EXCLUDED.updated_at
        WHERE t.updated_at <= EXCLUDED.updated_at
          AND (t.holder_name, t.account_type, t.account_status, t.updated_at)
              IS DISTINCT FROM (EXCLUDED.holder_name, EXCLUDED.account_type, EXCLUDED.account_status, EXCLUDED.updated_at)
    RETURNING (xmax = 0) AS inserted
)
INSERT INTO payee_directory_result
SELECT CASE WHEN inserted THEN 'inserted' ELSE 'updated' END FROM upserted;

\\if :full
-- Full mode: close every ACTIVE, non-sample account missing from the export,
-- unless core banking changed it after the export's as-of instant.
CREATE TEMP TABLE payee_directory_full_params ON COMMIT DROP AS
SELECT :'as_of'::timestamptz AS as_of, :max_close_percent::integer AS max_close_percent,
       (SELECT count(*) FROM payee_directory_entry WHERE account_status = 'ACTIVE' AND scheme_name <> 'SAMPLE') AS active_before;

WITH closed AS (
    UPDATE payee_directory_entry t
    SET account_status = 'CLOSED', updated_at = p.as_of
    FROM payee_directory_full_params p
    WHERE t.account_status = 'ACTIVE'
      AND t.scheme_name <> 'SAMPLE'
      AND t.updated_at <= p.as_of
      AND NOT EXISTS (SELECT 1 FROM payee_directory_normalised n
                      WHERE n.scheme_name = t.scheme_name AND n.identification = t.identification)
    RETURNING 1
)
INSERT INTO payee_directory_result SELECT 'closed' FROM closed;

DO \$\$
DECLARE
    closed BIGINT; active_before BIGINT; max_pct INTEGER;
BEGIN
    SELECT count(*) INTO closed FROM payee_directory_result WHERE action = 'closed';
    SELECT p.active_before, p.max_close_percent INTO active_before, max_pct FROM payee_directory_full_params p;
    IF closed * 100 > max_pct * active_before THEN
        RAISE EXCEPTION 'full import would close % of % active accounts, more than % percent; nothing was changed (check the export, or raise --max-close-percent)',
            closed, active_before, max_pct;
    END IF;
END
\$\$;
\\endif

\\set QUIET off
SELECT (SELECT count(*) FROM payee_directory_normalised)                               AS rows_in_file,
       (SELECT count(*) FROM payee_directory_result WHERE action = 'inserted')         AS inserted,
       (SELECT count(*) FROM payee_directory_result WHERE action = 'updated')          AS updated,
       (SELECT count(*) FROM payee_directory_normalised)
         - (SELECT count(*) FROM payee_directory_result WHERE action <> 'closed')      AS unchanged_or_stale,
       (SELECT count(*) FROM payee_directory_result WHERE action = 'closed')           AS closed_missing;
COMMIT;
SQL
