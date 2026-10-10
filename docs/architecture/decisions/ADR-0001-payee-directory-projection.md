# ADR-0001: The payee directory is a local projection of core-banking account data

- Status: Proposed (owning squad: Payee and Metadata Squad)
- Date: 2026-10-08
- Service: `svc-of-payee-verification`

## Context

Confirmation of Payee compares the name a payer typed with the name of the
holder of the target account and checks that the account can receive
payments. The account-holder names and account statuses are owned by core
banking. No FinTechBankX repository owns account data yet: there is no
accounts service and no account event stream (`evt.*.account.*`) to project.

Until now the service matched against a hard-coded map
(`SeededPayeeDirectoryAdapter`), and the monolith's open-finance context used
an in-memory map (`InMemoryPayeeDirectoryAdapter`). Neither is deployable.

The check sits on the payment path (HLD target: TTLB <= 300 ms, 99.99 %), so
it should not depend on a synchronous call to core banking per request.

## Decision

1. The service keeps its own read-only projection, table
   `sc_of_payee_verification.payee_directory_entry` (scheme, identification,
   holder name, account type, status, `updated_at`), and matches only against
   it. It never writes the table from the API.
2. Until an accounts service exists, the projection is loaded from a
   core-banking export with `db/import/import-payee-directory.sh`: an
   idempotent upsert that inserts new accounts, updates rows only when the
   export row is at least as recent and different, and rejects the whole file
   on one invalid row. Closures are delivered as `account_status = CLOSED`;
   rows are never deleted by the import. When core banking signs off a
   complete export, `--full --as-of <instant>` also closes every ACTIVE
   account missing from it (unless changed after `<instant>`), guarded by
   `--max-close-percent` (default 10). `updated_at` must carry an explicit
   offset; a value without one rejects the file.
3. The sample rows used by local, dev and CI runs live in a separate Flyway
   location (`db/seed`) that is applied only when
   `PAYEE_DIRECTORY_SEED_ENABLED=true`. Staging and production never enable it.
4. `holder_name` is personal data (column comment `PII ... confidential-personal`).
   It is compared in memory and returned to the TPP only for a `CloseMatch`; it
   is never logged, stored in `payee_verification`, or published in events.
5. When an accounts service publishes account facts, a Kafka consumer
   (group `cg.svc-of-payee-verification.account-projection.v1`) replaces the
   import. The table, the out-port `PayeeDirectoryPort` and the matcher do not
   change. That switch is a new ADR that supersedes point 2.

## Consequences

- The service can run, scale and be tested without core banking online.
- The directory is only as fresh as the last import. A status change
  (for example an account closed today) is visible after the next import plus
  the in-process cache TTL (`COP_DIRECTORY_CACHE_TTL`, default 30 s).
  **Gap:** the import cadence and the export job on the core-banking side are
  not defined yet; the owning squad must agree them with core banking before
  production use.
- The service database holds a copy of holder names. It is a confidential
  personal-data store: encrypted with the service KMS key, reachable only from
  the service's pods, credentials in Secrets Manager, backups retained per the
  Terraform `backup_retention_days`.
- The import writes as `payee_verification_import` (SELECT/INSERT/UPDATE on
  `payee_directory_entry` only, secret `db-import`), never as the application
  role, which can only read the directory. Every insert and update is
  recorded in the append-only `payee_directory_entry_history` (login role from
  `session_user`, `application_name`, time, old and new type, status and
  `updated_at`). Holder names are not copied into the history: it keeps a
  SHA-256 of the old and new name, enough to show that and when a name
  changed, and the digests are classified like the name itself.

## Alternatives considered

- **Synchronous call to core banking per request.** Freshest data, but couples
  the payment path's latency and availability to core banking. Rejected for
  now; can be a fallback adapter behind `PayeeDirectoryPort`.
- **Read core-banking tables directly.** Shared-database anti-pattern;
  rejected by the repository's ownership rules.
- **Keep the seeded map.** Not deployable.

## Reversibility

Reversible: the projection is rebuildable from the source at any time, and the
port boundary keeps the matcher independent of how the data arrives.
