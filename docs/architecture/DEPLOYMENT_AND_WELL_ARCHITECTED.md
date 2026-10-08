# svc-of-payee-verification: deployment and AWS Well-Architected view

Status: Proposed. Describes what this repository provisions and configures; it
is not evidence that anything is deployed or compliant.

## Runtime shape

| Piece | Where | Defined in |
|---|---|---|
| Container `payee-verification-service` (Java 23, Spring Boot 3.3, user 10001) | EKS, namespace `open-finance`, Istio sidecar | `Dockerfile`, `deploy/helm/payee-verification-service` |
| API `POST /open-finance/v1/confirmation-of-payee/confirmation` on 8080; health/metrics on 8081 | behind the Istio ingress gateway | `api/openapi/confirmation-of-payee-service.yaml` |
| Aurora PostgreSQL Serverless v2 `db_of_payee_verification_<env>`, schema `sc_of_payee_verification` | private subnets, two or more AZs | `deploy/terraform/main.tf`, Flyway `src/main/resources/db/migration` |
| Event `OpenFinance.PayeeVerification.VerificationCompleted.v1` on `evt.of.payee.verification-completed.v1` | Amazon MSK (IAM auth) through the outbox relay | `api/asyncapi/svc-of-payee-verification.yaml` |
| Payee directory (projection of core-banking accounts) | `payee_directory_entry`, loaded by `db/import` | ADR-0001 |

## Reliability

- Three replicas minimum in production, spread across zones (`DoNotSchedule`
  on `topology.kubernetes.io/zone`), PDB `minAvailable: 2`, rolling updates
  with `maxUnavailable: 0`, graceful shutdown (25 s) after a 10 s preStop.
- Aurora Serverless v2 with a reader in a second AZ (`aurora_instance_count`
  >= 2 in prod) for failover; PITR via `backup_retention_days` (35 in prod).
- Kafka is off the request path: decisions and events commit in one database
  transaction and the relay publishes later. A broker outage grows
  `outbox_pending_events` but does not fail verifications. Relay failures
  follow ADR-021 decision 4: payload errors park the row and the relay goes
  on; every other error stops the batch without marking a row and retries
  with back-off, never parking by itself; `outbox_oldest_pending_age_seconds`
  pages the owning squad and only an operator parks such a row, with a reason
  (`db/ops/park-outbox-event.sh`).
- A repeated request (same TPP, same `X-FAPI-Interaction-ID`) returns the
  stored decision, so client retries after a timeout are safe; concurrent
  duplicates are resolved by the unique key.
- Readiness includes the database; liveness does not, so a database outage
  takes pods out of service without restart loops.

## Security

- OAuth2 resource server: issuer and `aud = svc-of-payee-verification`
  validated; TPP tokens must be DPoP-bound and carry a valid proof (RFC 9449:
  `cnf.jkt`, `htm`/`htu` (`htu` against `DPOP_PUBLIC_BASE_URL` plus the
  request path, never Host or `X-Forwarded-*`, which are not trusted:
  `forward-headers-strategy: none`), `iat` window, `ath`, single-use `jti` stored in
  PostgreSQL so replicas share replay protection). There is no exemption: no
  internal service calls this API, so every caller needs DPoP, and a token
  with realm role `service` is refused with 403 `SERVICE_TOKEN_NOT_ALLOWED`
  even when DPoP-bound. The TPP id is taken from
  the token, never from the body.
- Mesh-wide STRICT mTLS and default-deny come from the platform; the chart
  ships no PeerAuthentication/DestinationRule. A NetworkPolicy limits ingress
  to the gateway and Prometheus, and egress to DNS, istiod, OTel,
  PostgreSQL, Kafka and 443.
- Pods: non-root, read-only root filesystem, all capabilities dropped,
  RuntimeDefault seccomp.
- Data: KMS-encrypted Aurora, snapshots, logs and Secrets Manager secrets.
  Four database roles (`db/bootstrap/bootstrap-roles.sql`): the service runs
  as `payee_verification_app` (`db-app`; SELECT on the directory, SELECT/INSERT
  on decisions, the outbox and DPoP replay DML; owns nothing), Flyway runs in
  the `migrate` init container as the schema owner `payee_verification_migrate`
  (`db-migration`, never mounted into the service container), and the directory
  import runs as `payee_verification_import` (`db-import`; SELECT/INSERT/UPDATE
  on `payee_directory_entry` only); operators park outbox rows as
  `payee_verification_ops` (`db-ops`, never synced into the cluster; EXECUTE on
  the SECURITY DEFINER `park_outbox_event` and read access to row ids and park
  state only). Secrets are synced by External Secrets from
  ClusterSecretStore `aws-secrets-manager`; the IRSA role produces only to
  `evt.of.payee.*`.
- Audit: every insert and update of `payee_directory_entry` lands in the
  append-only `payee_directory_entry_history` (login role, `application_name`,
  time, old/new type, status and `updated_at`; holder names only as SHA-256
  digests), written by a `SECURITY DEFINER` trigger and protected against
  UPDATE, DELETE and TRUNCATE.
- Personal data: holder names stay in `payee_directory_entry` (column comment
  marks them PII); the history keeps only their digests (also marked PII). Decisions store a SHA-256 account reference, never the
  typed name, the holder name or the account number; events carry the same
  facts; logs carry ids only. The holder name is returned only for a
  CloseMatch; unknown accounts read as `Closed` (no account enumeration).
  Open point: an unsalted SHA-256 of an IBAN can be brute-forced within one
  bank's number space; consider an HMAC with a KMS-held key (see report).

## Performance efficiency

- One indexed lookup (`uq_payee_directory_account`) plus one insert of a
  decision and one outbox row per request; directory hits are cached per pod
  for `COP_DIRECTORY_CACHE_TTL` (30 s), misses are not cached.
- Virtual threads; Hikari pool `DB_POOL_MAX` (10) per pod; HPA on CPU 60 % and
  memory 80 %, 3 to 12 replicas. Connection alarm at 100 matches
  12 x 10 with headroom.
- Producer: idempotent, acks=all, lz4, linger 5 ms; the relay sends in
  insertion order under an advisory lock.

## Cost optimisation

- Aurora Serverless v2 scales from 0.5 ACU; dev uses one instance and a
  2 ACU ceiling. Prod ceiling 8 ACU (`aurora_max_capacity`), alarmed at 85 %.
- Outbox rows are purged after 7 days; DPoP replay rows after their window.
- Dev runs two smaller replicas (250m / 512Mi).

## Operational excellence

- Every change runs `ci/build`, `ci/test` (`./gradlew check`: unit, web,
  ArchUnit, PostgreSQL integration tests, 85 % line coverage) and
  `Deployability` (image non-root check, Helm lint/render, Terraform
  fmt/validate, data-migration rehearsal).
- Metrics: `http_server_requests_seconds_*`, `hikaricp_*`, `jvm_*`,
  `outbox_pending_events`, `outbox_oldest_pending_age_seconds`,
  `outbox_parked_rows` (rows parked now), counters
  `outbox_send_failures_total{exception}` and
  `outbox_parked_events_total{exception}` (one increment per parked row,
  relay or operator; the platform alert `OutboxEventsParked` fires on any
  increase over 15 minutes and the service ships no parked alert rule; tags
  carry no payee identifiers or account numbers); traces via
  OTLP to the platform collector; logs carry `traceId`, `spanId`, `requestId`.
- Runbook: `docs/migration/RUNBOOK-EXTRACT-of-payee-verification.md`.

## Sustainability

- Scale-to-demand (HPA, Serverless v2 ACUs) instead of peak-sized capacity.
- Insert-only narrow decision rows (no payload copies), short outbox
  retention, lz4-compressed events.

## Not verified here

`terraform validate` and `docker build` were not run in the authoring
environment (registry and Docker unavailable); CI runs both. No load test,
failover drill or penetration test has been run.
