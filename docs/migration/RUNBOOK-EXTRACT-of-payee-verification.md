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
| Depends on | core-banking export for the directory (ADR-0001); platform Keycloak, MSK; platform mesh PR #11 (gateway route); `ClusterSecretStore` `aws-secrets-manager` |

## 1. Source in the monolith

| Monolith element | Where | Here |
|---|---|---|
| `ConfirmationOfPayeeController` | `open-finance-context/open-finance-infrastructure/.../payeeverification/infrastructure/rest` | `infrastructure/web/PayeeVerificationController` (same path and JSON shape; security, headers and errors differ, see section 3) |
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
   `psql "host=<writer> dbname=db_of_payee_verification_<env> user=<admin> sslmode=verify-full sslrootcert=<rds-ca-dir>/global-bundle.pem" -v ON_ERROR_STOP=1 -f db/bootstrap/bootstrap-roles.sql`.
   It creates four LOGIN roles without passwords and lets only the owner role
   create the schema. Set each password with `\password <role>` and store
   `{"username","password"}` in the matching secret:

   | Role | Secret (Terraform output) | Used by | Privileges |
   |---|---|---|---|
   | `payee_verification_migrate` | `<env>/payee-verification-service/db-migration` (`migration_db_secret_name`) | Flyway, `migrate` init container | owns `sc_of_payee_verification` |
   | `payee_verification_app` | `<env>/payee-verification-service/db-app` (`app_db_secret_name`) | service container | SELECT directory; SELECT/INSERT decisions; SELECT/INSERT/UPDATE/DELETE outbox; SELECT/INSERT/DELETE DPoP replay; owns nothing |
   | `payee_verification_import` | `<env>/payee-verification-service/db-import` (`import_db_secret_name`) | operator running the import | SELECT/INSERT/UPDATE on `payee_directory_entry`, TEMPORARY |
   | `payee_verification_ops` | `<env>/payee-verification-service/db-ops` (`ops_db_secret_name`; filled by the DBA, never synced into the cluster) | operator parking outbox rows (`db/ops/park-outbox-event.sh`) | EXECUTE on `park_outbox_event` (SECURITY DEFINER, V9); SELECT on the outbox ids, topic, timestamps and park columns and on `payee_verification (verification_id, tpp_id)`; no payloads, no writes |

   Flyway grants the table privileges (`V6__grant_least_privilege.sql`, and
   `V9__park_outbox_event_ops_role.sql` for the ops role); if a role was created
   after the first deploy, re-run that file with psql as
   `payee_verification_migrate`.
3. Install the chart with `serviceAccount.roleArn`, `config.DB_URL`,
   `externalSecret.remoteSecretName` and `externalSecret.migrationRemoteSecretName`
   from the Terraform outputs. `jdbc_url` uses `sslmode=verify-full` with
   `sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem`; the chart mounts
   the platform ConfigMap `rds-ca-bundle` there in both containers and refuses
   any other `config.DB_URL`. Precondition: `kubectl -n open-finance get
   configmap rds-ca-bundle` exists (platform trust-manager); without it the pod
   stays in ContainerCreating. The `migrate` init container applies the
   migrations and exits; the service container starts with Flyway off.
4. Import the directory as `payee_verification_import`
   (`PGUSER=payee_verification_import db/import/import-payee-directory.sh <export.csv>`).
   Every change is recorded in `payee_directory_entry_history`.

## 3. Go-live (one way)

The monolith's `ConfirmationOfPayeeController` answered from an in-memory
sample directory (three hard-coded accounts) and kept no decisions, so there
is no real data to compare and nothing to fall back to. Go-live is one way:
no shadow traffic, no weighted split, no route back to the monolith.

### Dependencies (all must be in place before step 3)

| Dependency | Why |
|---|---|
| Platform mesh PR #11 | gateway route `/open-finance/v1/confirmation-of-payee/**` to `payee-verification-service.open-finance.svc.cluster.local:8080`, switched in one step |
| `ClusterSecretStore` `aws-secrets-manager` | External Secrets Operator syncs `db-app` (service) and `db-migration` (init container); without it the pods never start |
| Platform mesh NetworkPolicy for `open-finance` | the mesh repository owns NetworkPolicy (platform cicd-templates `1dd1138` turns chart policies off by default). The chart's own policy is opt-in: set `networkPolicy.enabled=true` and `networkPolicy.databaseCidrs` (Aurora subnets) only where the mesh repository does not cover the namespace; it then needs mesh sign-off |
| Keycloak client setup | TPP tokens with `aud = svc-of-payee-verification` and DPoP binding (`cnf.jkt`) |
| `DPOP_PUBLIC_BASE_URL` | the public gateway origin TPPs sign in the DPoP `htu`; the chart does not render and the service does not start without it |
| DBA bootstrap, Terraform and the first directory import (section 2) | roles, grants, secrets, Aurora, directory rows |
| Topic `evt.of.payee.verification-completed.v1` in the topic catalog | only before step 4 (relay) |

### Steps

| Step | Action | Check before going on |
|---|---|---|
| 1 | Deploy the chart with `OUTBOX_RELAY_ENABLED=false`; run the first directory import (`--full --as-of`) | readiness green; directory row count equals the export |
| 2 | Parity check below, against the service directly (port-forward), as the dedicated parity TPP client (`<parity-client-id>`) | passes; exactly one new decision for `<parity-client-id>` |
| 3 | Merge and apply mesh PR #11: the gateway route switches to the service in one step | parity check, again as `<parity-client-id>`, passes through the gateway; rollback triggers stay clear for 30 minutes |
| 3a | Park the parity events (below) so the relay never publishes them | `go-live parity` parked rows = N (2 when steps 2 and 3 each ran once); no unpublished, unparked row left for `<parity-client-id>` |
| 4 | Once the topic is in the catalog, set `OUTBOX_RELAY_ENABLED=true` | `outbox_pending_events` returns to ~0; `outbox_oldest_pending_age_seconds` stays below 60; `outbox_parked_events_total{exception="OperatorPark"}` rose by exactly N on the relay's first run (it counts the step 3a parks once, so the platform alert `OutboxEventsParked` fires once: acknowledge it against this change); `outbox_parked_rows` is N plus any payload-error parks |
| 5 | Remove the CoP route and code from the monolith after one release without rollback | no traffic on the old route |

No dual writes at any step: only the service records decisions.

### Parity requests are real decisions: park their events before step 4

Every parity request that returns `200` records a decision and writes an
`evt.of.payee.verification-completed.v1` row to the outbox; with the relay off
(steps 1 to 3) it waits there, and step 4 would publish it to real consumers.
So the parity check runs only as a dedicated parity TPP client
(`<parity-client-id>`, a Keycloak client registered for go-live and disabled
afterwards; never a real TPP), and its events are parked before step 4.

Expected count N: one decision per parity run (the repeated
`X-FAPI-Interaction-ID` returns the stored decision; the `400` and `401` cases
record nothing), so N = 2 when step 2 and step 3 each ran once. Record N in the
change ticket; every extra parity run adds one.

Step 3a, as the operator (see "Parking by hand" for the credential):

    sql="select o.event_id from sc_of_payee_verification.outbox_event o
           join sc_of_payee_verification.payee_verification v on v.verification_id::text = o.aggregate_id
          where v.tpp_id = '<parity-client-id>' and o.published_at is null and o.parked_at is null
          order by o.created_seq"
    psql "<conninfo>" -Atc "$sql" | while read -r event_id; do
      db/ops/park-outbox-event.sh "<conninfo>" "$event_id" "go-live parity"
    done
    psql "<conninfo>" -Atc "select count(*) from sc_of_payee_verification.outbox_event where parked_reason = 'go-live parity'"   # = N
    psql "<conninfo>" -Atc "$sql" | wc -l                                                                                      # = 0

The parity decisions stay in `payee_verification` (insert-only evidence); their
parked outbox rows are kept (parked rows are not purged).

### Parity check: response shape and headers only

The directory differs on purpose (core-banking export instead of three
samples), so the check compares structure, not values: a DPoP-bound TPP token
with a valid proof gets `200` with `Data.AccountStatus`, `Data.NameMatched` and
`Data.MatchedName` (only for `CloseMatch`); headers `X-FAPI-Interaction-ID`
(echoed) and `Cache-Control: no-store`; the same `X-FAPI-Interaction-ID` again
returns the stored decision; missing `X-FAPI-Interaction-ID` or a malformed
body gives `400`; no `DPoP` proof gives `401`.

Responses are **not** identical to the monolith's:

| Aspect | Monolith | This service |
|---|---|---|
| Directory | three hard-coded samples | the imported core-banking projection |
| TPP id | `x-fapi-financial-id` header, else `UNKNOWN_TPP` | from the access token, never from a header or the body |
| DPoP | header must be present; proof not validated | proof validated (RFC 9449: `htm`, `htu` against `DPOP_PUBLIC_BASE_URL`, `iat`, `ath`, single-use `jti`) and the token must be DPoP-bound |
| Service-role tokens | not distinguished | refused with `403` `SERVICE_TOKEN_NOT_ALLOWED`, even when DPoP-bound |
| Missing `Authorization` / `DPoP` | `500` (unhandled missing header) | `401` (`UNAUTHORIZED` for no token; `AUTH_HEADER_MISSING` with `WWW-Authenticate: DPoP` for no proof) |
| Missing `X-FAPI-Interaction-ID` | `500` | `400` `INVALID_REQUEST` |
| Repeated `X-FAPI-Interaction-ID` | evaluated again | stored decision returned; for another account `409` |
| `X-OF-Cache` | `HIT`/`MISS` | not sent |
| Decisions and events | memory and logs only | `payee_verification` rows and `evt.of.payee.verification-completed.v1` |

### Rollback triggers (any one, measured at the gateway)

| Trigger | Threshold |
|---|---|
| 5xx rate on the CoP route | above 1 % of requests for 5 minutes |
| p99 latency | above 300 ms for 10 minutes |
| Ready pods | below 2 for 5 minutes |
| `401`/`403` share | above 20 % of requests for 15 minutes after the switch (TPP client or `DPOP_PUBLIC_BASE_URL` misconfigured: fix the configuration first) |
| Directory content | row count differs from the signed-off export after an import |

### Rollback

There is no route back to the monolith. Roll back to the previous good state:

- **Bad release:** `helm rollback payee-verification-service <previous revision> -n open-finance`.
  Migrations are additive, so the previous image runs on the current schema.
- **Bad directory data:** re-import the previous signed-off export with
  `--full --as-of <its instant>` (`payee_directory_entry_history` shows what the
  bad import changed).
- **Event problems:** set `OUTBOX_RELAY_ENABLED=false`; decisions keep being
  recorded and events wait in the outbox.

### Behaviour changes in this branch (Proposed)

- `DPOP_PUBLIC_BASE_URL` is required. Originally an empty value made the `htu`
  check fall back to the request URL, and after the htu fix an unset value fell
  back to `http://localhost:8080`; now the chart fails to render and the service
  fails to start without it (migrate-only runs excepted).
- Tokens with realm role `service` are refused; no internal caller uses this API.
- A missing `X-FAPI-Interaction-ID` is `400`, not `401`.
- The outbox relay is off unless `OUTBOX_RELAY_ENABLED=true` (the chart sets it).
- Credential rotation: change the password in PostgreSQL and in its secret, then
  `helm upgrade ... --set externalSecret.rotation=<date>`; the changed
  `checksum/secret` rolls the pods onto the new credential.

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
| `outbox_parked_rows` | gauge: rows parked right now (relay or operator; formerly the gauge `outbox_parked_events`) |
| `outbox_parked_events_total{exception=...}` | counter, one increment per parked row: the payload error class, or `OperatorPark` for a park by `park_outbox_event` (counted once by the relay on its next run, column `park_counted`, V8) |

Alerts:

- **Page the owning squad** when `outbox_oldest_pending_age_seconds` is above
  900 (15 minutes) for 5 minutes: the relay is stuck on a row. Look at
  `outbox_send_failures_total` by `exception` and the relay's WARN log (event
  id and exception class), then fix the cause (topic ACL, credentials, broker,
  topic missing from the catalog). The relay resumes by itself.
- Parked rows: the platform alert **`OutboxEventsParked`** (any increase of
  `outbox_parked_events_total` over 15 minutes, warning, routed by squad with
  namespace fallback) covers them; this service ships no parked alert rule.
  `exception` names a payload error the code must fix, or `OperatorPark`.

Parking by hand: only an operator may park a row the relay keeps retrying,
once the squad has decided the event will not be sent. Run as the ops role
`payee_verification_ops` (`<env>/payee-verification-service/db-ops`); the
schema-owner credential is not needed:

    PGPASSWORD=... db/ops/park-outbox-event.sh "host=<writer> dbname=db_of_payee_verification_<env> user=payee_verification_ops sslmode=verify-full sslrootcert=<rds-ca-dir>/global-bundle.pem" <event-id> "<incident or ticket>: <why>"

It calls `park_outbox_event(event_id, reason)` (`V7__outbox_parking.sql`,
SECURITY DEFINER since V9, so the ops role needs no table rights), which
records `parked_at`, the reason and the operator's login (`session_user`,
here `payee_verification_ops`) in `parked_by`, and
refuses a blank reason or an unknown, published or already parked row.
The relay counts the park once on its next run
(`outbox_parked_events_total{exception="OperatorPark"}`). Rows parked before
V8 count as already counted. Parked rows are never relayed again, so there is
no replay path; one added later must reset `park_counted = false`.
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
- [ ] Gateway route switched in one step (platform mesh PR #11)
