# fintechbankx-openfinance-payee-metadata-payee-verification

Bu repository, FinTechBankX DDD/EDA dönüşümünde **svc-of-payee-verification** servis yetkinliğinin kaynak kodunu, kontratlarını ve operasyonel guardrail'lerini içerir.

## Sorumluluk ve Sahiplik
| Alan | Değer |
|---|---|
| Organizasyon Modeli | Spotify Model (Tribe/Squad) |
| Tribe | Open Finance Tribe |
| Squad | Payee and Metadata Squad |
| Repo Kümesi (Capability) | open_finance |
| Service ID | svc-of-payee-verification |
| Bounded Context | payee_verification |
| Wave | 1 |
| Mimari Yaklaşım | DDD + Hexagonal + Event-Driven |

## Sorumluluk Sınırları
- Bu repo kendi bounded context domain modelinin tek yetkili sahibidir.
- Domain kuralları altyapıdan bağımsız tutulur; entegrasyonlar port/adapter katmanında yönetilir.
- API/Event kontratları geriye dönük uyumluluk kontrolleri ile korunur.
- Güvenlik guardrail'leri (mTLS, token doğrulama, idempotency, log hijyeni) CI/CD ile zorlanır.

## Kapsam
### In Scope
- payee_verification bağlamına ait uygulama kodu, testler ve otomasyon.
- Bu servise ait OpenAPI/AsyncAPI veya şema artefaktları.
- Bu servisin çalışma zamanı operasyonları (gözlemlenebilirlik, release, rollback).

### Out of Scope
- Diğer bounded context'lerin iş kuralları ve veri sahipliği.
- Paylaşımlı DB anti-pattern'i; cross-context doğrudan tablo erişimi.
- Platform dışı gizli bilgi/anahtar yönetimi (merkezi policy dışında local hardcode).

## Mühendislik Standartları
- **TDD öncelikli** geliştirme, birim test + entegrasyon testi.
- **Clean Architecture**: Domain katmanı framework bağımsız.
- **12-Factor** ve environment-driven configuration.
- **FAPI odaklı güvenlik** (OIDC/OAuth2, mTLS, DPoP gereksinimleri ilgili servislerde).
- **PII güvenliği**: loglarda maskeleme, secret'ların source/env içine yazılmaması.

## Branching ve Release Akışı
- Uzun ömürlü branch'ler: `main`, `dev`, `staging`, `local`.
- Feature branch kuralı: `codex/<kisa-aciklama>`.
- Release yaklaşımı: PR + required status checks + tag tabanlı sürümleme.

## Dokümantasyon ve Referanslar
- [Enterprise Architecture Hub](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture)
- [Secure Microservices Architecture](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/architecture/overview/SECURE_MICROSERVICES_ARCHITECTURE.md)
- [Service Data Ownership Matrix](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_DATA_OWNERSHIP_MATRIX.md)
- [Service API Contracts Index](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_API_CONTRACTS_INDEX.md)
- [Transformation Plan](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/MICROSERVICES_TRANSFORMATION_PLAN.md)
- [Capability Map (PUML)](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/puml/service-mesh/enterprise-capability-map.puml)
- [Bu Repo Dokümantasyonu](./docs)

## Güvenlik ve Uyumluluk Notları
- Gerçek secret değerleri repo veya `.env` içinde tutulmaz.
- Secret üretim/rotasyon olayları merkezi log/SIEM'e taşınır.
- CI pipeline, anonimlik ve local-path sızıntısı kontrollerini bloklayıcı olarak çalıştırır.

## Katkı
- Katkı süreci için `CONTRIBUTING.md` ve squad runbook'ları izlenmelidir.
- PR'larda mimari kararlar ADR veya backlog referansı ile ilişkilendirilmelidir.

## Run and deploy

Service `svc-of-payee-verification` (slug `payee-verification-service`) answers
`POST /open-finance/v1/confirmation-of-payee/confirmation`
([OpenAPI](api/openapi/confirmation-of-payee-service.yaml)), records every
decision in PostgreSQL schema `sc_of_payee_verification` without names, and
publishes `OpenFinance.PayeeVerification.VerificationCompleted.v1` on
`evt.of.payee.verification-completed.v1` through a transactional outbox
([AsyncAPI](api/asyncapi/svc-of-payee-verification.yaml)).

Build and test (Java 23):

```bash
./gradlew check                      # unit, web, ArchUnit tests + 85 % line coverage
TEST_DB_URL=jdbc:postgresql://localhost:5432/<db> TEST_DB_USERNAME=<user> TEST_DB_PASSWORD=<pw> \
  ./gradlew check                    # also runs the PostgreSQL integration tests
```

Run locally against PostgreSQL with the sample directory:

```bash
DB_URL=jdbc:postgresql://localhost:5432/db_of_payee_verification_local DB_USERNAME=<user> \
SPRING_DATASOURCE_PASSWORD=<pw> PAYEE_DIRECTORY_SEED_ENABLED=true OUTBOX_RELAY_ENABLED=false \
OIDC_ISSUER_URI=<issuer> OIDC_JWK_SET_URI=<jwks> ./gradlew bootRun
```

Callers need an access token with `aud` = `svc-of-payee-verification`; TPP
tokens must be DPoP-bound (`Authorization: DPoP <token>` plus a `DPoP` proof).

Deploy:

| What | Where |
|---|---|
| Container image (non-root, ports 8080/8081) | [Dockerfile](Dockerfile) |
| Helm chart (namespace `open-finance`) | [deploy/helm/payee-verification-service](deploy/helm/payee-verification-service/values.yaml) |
| AWS resources (Aurora PostgreSQL Serverless v2, KMS, secret, IRSA, MSK policy) | [deploy/terraform](deploy/terraform/main.tf) |
| Payee directory import from core banking | [db/import/import-payee-directory.sh](db/import/import-payee-directory.sh), [ADR-0001](docs/architecture/decisions/ADR-0001-payee-directory-projection.md) |
| Migration rehearsal | [scripts/migration/verify-migration.sh](scripts/migration/verify-migration.sh) |
| Well-Architected view | [DEPLOYMENT_AND_WELL_ARCHITECTED.md](docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md) |
| Extraction and cutover runbook | [RUNBOOK-EXTRACT-of-payee-verification.md](docs/migration/RUNBOOK-EXTRACT-of-payee-verification.md) |

## Cell-Based Architecture

This repository participates in the FinTechBankX cell-based resilience program.

- Plan: \
- Backlog: \

<!-- cell-architecture-start -->
## Cell-Based Architecture

This repository participates in the FinTechBankX cell-based resilience program.

- Plan: docs/architecture/CELL_BASED_ARCHITECTURE_IMPLEMENTATION_PLAN.md
- Backlog: docs/project-management/CELL_ARCHITECTURE_BACKLOG_BOARD.md
<!-- cell-architecture-end -->
