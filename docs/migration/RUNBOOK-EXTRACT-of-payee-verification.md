# RUNBOOK-EXTRACT-of-payee-verification

Extraction of Confirmation of Payee from `enterprise-loan-management-system`
into `svc-of-payee-verification` (this repository). Status: Proposed.

| Field | Value |
|---|---|
| Context / service | `of` / `svc-of-payee-verification` |
| Slice | Confirmation of Payee: name matching, account-status check, decision record |
| Owned data | `db_of_payee_verification_<env>`, schema `sc_of_payee_verification`: `payee_directory_entry` (projection), `payee_verification`, `outbox_event`, `dpop_proof_replay` |
| API | `POST /open-finance/v1/confirmation-of-payee/confirmation` (`api/openapi/confirmation-of-payee-service.yaml`) |
| Events | `evt.of.payee.verification-completed.v1` (`api/asyncapi/svc-of-payee-verification.yaml`) |
| Depends on | core-banking export for the directory (ADR-0001); platform Keycloak, MSK |

## 1. Source in the monolith

| Monolith element | Where | Here |
|---|---|---|
| `ConfirmationOfPayeeController` | `open-finance-context/open-finance-infrastructure/.../payeeverification/infrastructure/rest` | `infrastructure/web/PayeeVerificationController` (same path and payload) |
| `ConfirmationOfPayeeService` | `open-finance-application/.../payeeverification/application` | `application/VerifyPayeeService` + `domain/model/PayeeVerification` |
| `IbanValidator`, `ConfirmationDecisionPolicy`, `LevenshteinNameSimilarityAdapter` | domain / infrastructure | `domain/service/IbanValidator`, `domain/service/PayeeNameMatcher` |
| `InMemoryPayeeDirectoryAdapter`, `InMemoryPayeeDirectoryCacheAdapter` | in-memory | `payee_directory_entry` + `JpaPayeeDirectoryAdapter` + `CachingPayeeDirectoryAdapter` |
| `InMemoryPayeeAuditLogAdapter` (audit only in memory and logs) | in-memory | `payee_verification` (durable, no names) + outbox event |
| `services/openfinance-confirmation-of-payee-service` | launcher shell only | replaced by this repository |

## 2. Data split

- The monolith holds no persistent CoP data (directory and audit were in
  memory), so there is **no backfill of decisions**: history starts at cutover.
- The directory is loaded from core banking with
  `db/import/import-payee-directory.sh <export.csv>` (upsert, re-runnable;
  see ADR-0001). Run it before cutover and on the agreed schedule after it.
  With a complete, signed-off export use `--full --as-of <export instant>`:
  ACTIVE accounts missing from the file are closed as of that instant (rows
  changed later are left alone), and the run is rolled back if it would close
  more than 10 % of the active accounts (`--max-close-percent`).
  Every `updated_at` must carry an offset (`Z` or `+hh:mm`).
- The sample seed (`db/seed`) is for local/dev/CI only
  (`PAYEE_DIRECTORY_SEED_ENABLED=true`).
- Rehearsal: `scripts/migration/verify-migration.sh` (migrations, seed twice,
  import twice, changed and stale rows, rejected file, insert-only and
  idempotency constraints). CI job `deploy/data-migration-rehearsal`.

### DBA bootstrap (once per environment)

1. `terraform apply` in `deploy/terraform` with `environments/<env>.tfvars`.
2. With the RDS-managed admin secret (`master_user_secret_arn`), before the
   first deploy:
   `psql "host=<writer> dbname=db_of_payee_verification_<env> user=<admin> sslmode=require" -v ON_ERROR_STOP=1 -f db/bootstrap/bootstrap-roles.sql`.
   It creates three LOGIN roles without passwords and lets only the owner role
   create the schema. Set each password with `\password <role>` and store
   `{"username","password"}` in the matching secret:

   | Role | Secret (Terraform output) | Used by | Privileges |
   |---|---|---|---|
   | `payee_verification_migrate` | `<env>/payee-verification-service/db-migration` (`migration_db_secret_name`) | Flyway, `migrate` init container | owns `sc_of_payee_verification` |
   | `payee_verification_app` | `<env>/payee-verification-service/db-app` (`app_db_secret_name`) | service container | SELECT directory; SELECT/INSERT decisions; SELECT/INSERT/UPDATE/DELETE outbox; SELECT/INSERT/DELETE DPoP replay; owns nothing |
   | `payee_verification_import` | `<env>/payee-verification-service/db-import` (`import_db_secret_name`) | operator running the import | SELECT/INSERT/UPDATE on `payee_directory_entry`, TEMPORARY |

   Flyway grants the table privileges (`V6__grant_least_privilege.sql`); if a
   role was created after the first deploy, re-run that file with psql as
   `payee_verification_migrate`.
3. Install the chart with `serviceAccount.roleArn`, `config.DB_URL`,
   `externalSecret.remoteSecretName` and `externalSecret.migrationRemoteSecretName`
   from the Terraform outputs. The `migrate` init container applies the
   migrations and exits; the service container starts with Flyway off.
4. Import the directory as `payee_verification_import`
   (`PGUSER=payee_verification_import db/import/import-payee-directory.sh <export.csv>`).
   Every change is recorded in `payee_directory_entry_history`.

## 3. Cutover

| Step | Action | Verification | Rollback |
|---|---|---|---|
| 1 | Deploy with `OUTBOX_RELAY_ENABLED=false`; import the directory | readiness green; directory row count equals the export | uninstall the release; drop the database |
| 2 | Shadow: gateway mirrors CoP traffic to the service (Istio `mirror`), responses compared offline | outcome agreement on the same inputs; p99 < 300 ms | remove the mirror |
| 3 | Route `/open-finance/v1/confirmation-of-payee/**` to `payee-verification-service.open-finance.svc.cluster.local:8080` at the ingress gateway (weighted 10 %, 50 %, 100 %) | 4xx/5xx rate, `http_server_requests_seconds` p99, decision rows growing | set the weight back to the monolith |
| 4 | Enable the relay (`OUTBOX_RELAY_ENABLED=true`) once `evt.of.payee.verification-completed.v1` exists in the topic catalog | `outbox_pending_events` returns to ~0 | relay off; events wait in the outbox |
| 5 | Remove the CoP route and code from the monolith after one release cycle without rollback | no traffic on the old route | redeploy the previous monolith release |

No dual writes at any step: only the service records decisions.

## 4. Operations

- Alerts: Aurora ACU and connection alarms, 5xx rate and p99 latency, and the
  outbox alerts below.
- Reprocessing: events are at-least-once; consumers de-duplicate on `eventId`.

### Outbox relay failures (ADR-021 decision 4)

The relay is off unless `OUTBOX_RELAY_ENABLED=true` (the chart sets it
explicitly). On a send failure it applies ADR-021 decision 4:

| Error | Relay behaviour |
|---|---|
| Payload error: `RecordTooLargeException`, `SerializationException`, `InvalidTopicException` | parks the row (`parked_by = 'relay'`, `parked_reason` = the class) and continues with the next row |
| Anything else: authorization (`TopicAuthorizationException`, `SaslAuthenticationException`, `ClusterAuthorizationException`), broker unavailable, timeouts, a producer that cannot be built, unclassified | stops the batch without marking any row (ordering kept), retries with back-off from 1 s doubling to 60 s; never parks the row, however long the error lasts |

Metrics (Prometheus names), tagged by exception class only, never by
identifiers or messages:

| Metric | Meaning |
|---|---|
| `outbox_oldest_pending_age_seconds` | age of the oldest row neither published nor parked |
| `outbox_send_failures_total{exception=...}` | failed sends by exception class |
| `outbox_pending_events` | rows waiting to be sent |
| `outbox_parked_events` | rows taken out of the relay (relay or operator) |

Alerts:

- **Page the owning squad** when `outbox_oldest_pending_age_seconds` is above
  900 (15 minutes) for 5 minutes: the relay is stuck on a row. Look at
  `outbox_send_failures_total` by `exception` and the relay's WARN log (event
  id and exception class), then fix the cause (topic ACL, credentials, broker,
  topic missing from the catalog). The relay resumes by itself.
- Ticket when `outbox_parked_events` increases: a payload error the code must fix.

Parking by hand: only an operator may park a row the relay keeps retrying,
once the squad has decided the event will not be sent. Run, as the schema
owner (`<env>/payee-verification-service/db-migration`, break-glass):

    PGPASSWORD=... db/ops/park-outbox-event.sh "host=<writer> dbname=db_of_payee_verification_<env> user=payee_verification_migrate sslmode=require" <event-id> "<incident or ticket>: <why>"

It calls `park_outbox_event(event_id, reason)` (`V7__outbox_parking.sql`),
which records `parked_at`, the reason and the login role in `parked_by`, and
refuses a blank reason or an unknown, published or already parked row.
Parked rows are not purged. Find stuck rows with
`select event_id, created_at from sc_of_payee_verification.outbox_event where published_at is null and parked_at is null order by created_seq limit 5;`.
- Retention: outbox rows 7 days; decisions are kept (evidence) until the
  owning squad sets a retention period; deleting old decisions is allowed
  (as the schema owner; the runtime role cannot delete them), updating them
  is blocked by a trigger.

## 5. Acceptance checklist

- [x] Builds and tests standalone (`./gradlew check`, PostgreSQL ITs in `ci/test`)
- [x] Own schema and Flyway migrations; Hibernate validates against them
- [x] Decision and event in one transaction (outbox); relay with one active publisher
- [x] Idempotent on (TPP, `X-FAPI-Interaction-ID`)
- [x] DPoP and audience enforced; TPP id from the token
- [x] Migration, seed and import rehearsed in CI
- [ ] Core-banking export job and import schedule agreed (ADR-0001 gap)
- [ ] Topic `evt.of.payee.verification-completed.v1` added to the platform topic catalog
- [ ] Gateway route and mirror configured (platform mesh repository)
