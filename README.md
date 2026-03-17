# Payee Verification Service

## Scope
Confirmation of Payee runtime service for Open Finance name verification.

## Phase-2 Implementation (Wave 1)
- Hexagonal slice implemented:
  - `domain`: match logic and status model
  - `application`: `VerifyPayeeUseCase` and service orchestration
  - `infrastructure`: seeded adapter + REST controller
- Mandatory protected-operation headers enforced:
  - `Authorization`
  - `DPoP`
  - `X-FAPI-Interaction-ID`
- Observability baseline:
  - request trace correlation (`X-Trace-Id`)
  - custom HTTP request timer metric
  - structured completion log line

## API
- Endpoint: `POST /open-finance/v1/confirmation-of-payee/confirmation`
- Contract: `/api/openapi/confirmation-of-payee-service.yaml`



## Publication Guardrails

- Follow [Publication Guardrails](docs/publication/PUBLICATION_GUARDRAILS.md).
- Do not commit local paths, personal identifiers, or secrets.
