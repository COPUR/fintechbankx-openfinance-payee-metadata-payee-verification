# Migration Granularity Notes

- Repository: `fintechbankx-openfinance-payee-verification-service`
- Source monorepo: `enterprise-loan-management-system`
- Sync date: `2026-03-15`
- Sync branch: `chore/granular-source-sync-20260313`

## Applied Rules

- dir: `services/openfinance-confirmation-of-payee-service` -> `.`
- file: `api/openapi/confirmation-of-payee-service.yaml` -> `api/openapi/confirmation-of-payee-service.yaml`
- dir: `infra/terraform/services/confirmation-of-payee-service` -> `infra/terraform/confirmation-of-payee-service`
- file: `docs/architecture/open-finance/capabilities/hld/confirmation-of-payee-hld.md` -> `docs/hld/confirmation-of-payee-hld.md`
- file: `docs/architecture/open-finance/capabilities/test-suites/confirmation-of-payee-test-suite.md` -> `docs/test-suites/confirmation-of-payee-test-suite.md`

## Notes

- This is an extraction seed for bounded-context split migration.
- Follow-up refactoring may be needed to remove residual cross-context coupling.
- Build artifacts and local machine files are excluded by policy.

