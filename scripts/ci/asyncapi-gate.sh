#!/usr/bin/env bash
# Provider AsyncAPI gate in ci/test (ADR-019 section 5, adr-runbooks 2e7cf13):
#   1. `asyncapi validate` with @asyncapi/cli 2.13.0 (the version cicd-templates 4129ceb pins) on every
#      top-level api/asyncapi/*.yaml|yml; shared fragments such as common/event-envelope.yaml are not specs.
#   2. The catalog's breaking-change rules against BASE_REF (default origin/main), ASYNCAPI_DIR=api/asyncapi.
#      scripts/ci/asyncapi-breaking.mjs and scripts/ci/lib/asyncapi-model.mjs are copied unchanged from
#      fintechbankx-governance-api-contracts-asyncapi-catalog commit b0e31ee (branch
#      claude/governance-alignment-5f4h5h; the ASYNCAPI_DIR version, child of e47c327). Do not edit them
#      here; refresh them from the catalog. A spec that is not on BASE_REF yet counts as new.
# Needs node 22, git history with BASE_REF (actions/checkout fetch-depth: 0) and the yaml package 2.9.1,
# installed below into ./node_modules when missing (not committed; see .gitignore).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export ASYNCAPI_DIR=api/asyncapi
export BASE_REF="${BASE_REF:-origin/main}"
ASYNCAPI_CLI_VERSION=2.13.0

shopt -s nullglob
specs=("$ASYNCAPI_DIR"/*.yaml "$ASYNCAPI_DIR"/*.yml)
if [ "${#specs[@]}" -eq 0 ]; then
  echo "No AsyncAPI spec in $ASYNCAPI_DIR/: this service publishes events, so one is required." >&2
  exit 1
fi
for spec in "${specs[@]}"; do
  echo "asyncapi validate $spec (@asyncapi/cli $ASYNCAPI_CLI_VERSION)"
  npx -y "@asyncapi/cli@$ASYNCAPI_CLI_VERSION" validate "$spec"
done

if ! node --input-type=module -e "await import('yaml')" >/dev/null 2>&1; then
  npm install --no-save --no-package-lock --no-audit --no-fund yaml@2.9.1 >/dev/null
fi
node scripts/ci/asyncapi-breaking.mjs
