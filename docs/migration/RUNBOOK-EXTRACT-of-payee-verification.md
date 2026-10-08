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
- The sample seed (`db/seed`) is for local/dev/CI only
  (`PAYEE_DIRECTORY_SEED_ENABLED=true`).
- Rehearsal: `scripts/migration/verify-migration.sh` (migrations, seed twice,
  import twice, changed and stale rows, rejected file, insert-only and
  idempotency constraints). CI job `deploy/data-migration-rehearsal`.

### DBA bootstrap (once per environment)

1. `terraform apply` in `deploy/terraform` with `environments/<env>.tfvars`.
2. With the RDS-managed admin secret (`master_user_secret_arn`), create role
   `payee_verification_app` (LOGIN, no superuser) owning schema
   `sc_of_payee_verification`, and an import role with INSERT/UPDATE on
   `payee_directory_entry` only (after the first Flyway run).
3. Write `{"username": "payee_verification_app", "password": "<generated>"}` to
   Secrets Manager `<env>/payee-verification-service/db-app`.
4. Install the chart with `serviceAccount.roleArn`, `config.DB_URL` and
   `externalSecret.remoteSecretName` from the Terraform outputs. Flyway creates
   the schema objects on first start.

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

- Alert on `outbox_pending_events` growth (relay or MSK down), Aurora ACU and
  connection alarms, 5xx rate and p99 latency.
- Reprocessing: events are at-least-once; consumers de-duplicate on `eventId`.
- Retention: outbox rows 7 days; decisions are kept (evidence) until the
  owning squad sets a retention period; deleting old decisions is allowed,
  updating them is blocked by a trigger.

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
