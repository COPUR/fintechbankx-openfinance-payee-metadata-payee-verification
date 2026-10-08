#!/usr/bin/env bash
# Loads account-holder rows exported from core banking into
# sc_of_payee_verification.payee_directory_entry (idempotent upsert).
#
# Until an accounts service owns this data and publishes account events
# (docs/architecture/decisions/ADR-0001-payee-directory-projection.md), the
# directory is refreshed from a core-banking export with this script.
#
# Usage: PGHOST=... PGDATABASE=db_of_payee_verification_<env> PGUSER=... \
#        PGPASSWORD=... db/import/import-payee-directory.sh <export.csv>
#
# CSV header: scheme_name,identification,holder_name,account_type,account_status,updated_at
#   account_type   PERSONAL | BUSINESS
#   account_status ACTIVE | CLOSED | DECEASED
#   updated_at     ISO-8601 instant at which core banking last changed the account
#
# Rules:
# - scheme and identification are normalised (upper case, spaces removed),
#   exactly as the service normalises a request;
# - a new account is inserted; an existing one is updated only if the export
#   row is at least as recent (updated_at) AND something changed, so
#   re-running the same file changes nothing and an older export cannot
#   overwrite newer data;
# - accounts missing from the file are left as they are (close them with
#   account_status CLOSED in the export);
# - the whole file is one transaction: one bad row rejects the load, and so
#   does a row in the SAMPLE scheme or with a SAMPLE- identification (reserved
#   for the dev/CI seed, which the import therefore never touches).
# Holder names are personal data: this script never prints them.
set -euo pipefail

csv="${1:?usage: import-payee-directory.sh <export.csv>}"
schema="${PAYEE_SCHEMA:-sc_of_payee_verification}"
[ -r "$csv" ] || { echo "cannot read $csv" >&2; exit 2; }
case "$schema" in
  *[!a-z0-9_]*) echo "invalid schema name" >&2; exit 2 ;;
esac

psql --no-psqlrc --quiet -v ON_ERROR_STOP=1 --set=schema="$schema" <<SQL
\\set QUIET on
BEGIN;
SET LOCAL search_path TO :"schema";

CREATE TEMP TABLE payee_directory_import (
    scheme_name     TEXT,
    identification  TEXT,
    holder_name     TEXT,
    account_type    TEXT,
    account_status  TEXT,
    updated_at      TEXT
) ON COMMIT DROP;

\\copy payee_directory_import FROM '$csv' WITH (FORMAT csv, HEADER true)

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

\\set QUIET off
SELECT (SELECT count(*) FROM payee_directory_normalised)                               AS rows_in_file,
       (SELECT count(*) FROM payee_directory_result WHERE action = 'inserted')         AS inserted,
       (SELECT count(*) FROM payee_directory_result WHERE action = 'updated')          AS updated,
       (SELECT count(*) FROM payee_directory_normalised)
         - (SELECT count(*) FROM payee_directory_result)                               AS unchanged_or_stale;
COMMIT;
SQL
