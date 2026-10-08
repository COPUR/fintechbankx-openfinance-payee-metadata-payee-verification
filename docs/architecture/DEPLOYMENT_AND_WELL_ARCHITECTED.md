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
  `outbox_pending_events` but does not fail verifications.
- A repeated request (same TPP, same `X-FAPI-Interaction-ID`) returns the
  stored decision, so client retries after a timeout are safe; concurrent
  duplicates are resolved by the unique key.
- Readiness includes the database; liveness does not, so a database outage
  takes pods out of service without restart loops.

## Security

- OAuth2 resource server: issuer and `aud = svc-of-payee-verification`
  validated; TPP tokens must be DPoP-bound and carry a valid proof (RFC 9449:
  `cnf.jkt`, `htm`/`htu`, `iat` window, `ath`, single-use `jti` stored in
  PostgreSQL so replicas share replay protection). Internal callers with realm
  role `service` and no `cnf` are exempt (mesh mTLS). The TPP id is taken from
  the token, never from the body.
- Mesh-wide STRICT mTLS and default-deny come from the platform; the chart
  ships no PeerAuthentication/DestinationRule. A NetworkPolicy limits ingress
  to the gateway, `payments` and Prometheus, and egress to DNS, istiod, OTel,
  PostgreSQL, Kafka and 443.
- Pods: non-root, read-only root filesystem, all capabilities dropped,
  RuntimeDefault seccomp.
- Data: KMS-encrypted Aurora, snapshots, logs and Secrets Manager secret
  `<env>/payee-verification-service/db-app` (synced by External Secrets from
  ClusterSecretStore `aws-secrets-manager`). The IRSA role reads only that
  secret and produces only to `evt.of.payee.*`.
- Personal data: holder names stay in `payee_directory_entry` (column comment
  marks them PII). Decisions store a SHA-256 account reference, never the
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
  `outbox_pending_events{service="svc-of-payee-verification"}`; traces via
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
