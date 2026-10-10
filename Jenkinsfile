pipeline {
    agent any
    options { timestamps() }

    environment {
        SERVICE_DIR = '.'
        IMAGE_NAME = 'payee-verification-service:' + (env.GIT_COMMIT ?: 'local')
        STRICT_DEPRECATED_ROOTS = 'true'
    }

    stages {
        stage('Validate Workspace') {
            steps {
                sh '''
                  set -euo pipefail
                  test -f "${SERVICE_DIR}/build.gradle"
                  test -x ./gradlew
                '''
            }
        }
        stage('Repository Governance') {
            steps {
                sh '''
                  set -euo pipefail
                  export STRICT_DEPRECATED_ROOTS="${STRICT_DEPRECATED_ROOTS:-true}"
                  bash tools/validation/validate-repo-governance.sh

                  python3 -m venv .venv-validation
                  . .venv-validation/bin/activate
                  python -m pip install --upgrade pip coverage
                  python -m coverage run --source=tools/validation -m unittest discover -s tools/validation/tests -p 'test_*.py'
                  python -m coverage report --include='*/repo_governance_validator.py' --fail-under=90
                '''
            }
        }
        stage('Quality Gate') {
            steps {
                // The PostgreSQL integration tests fail (not skip) on Jenkins without TEST_DB_URL.
                // Use the agent's TEST_DB_* settings if present, otherwise a throwaway postgres:16 container.
                sh '''
                  set -euo pipefail
                  if [ -z "${TEST_DB_URL:-}" ]; then
                    if ! command -v docker >/dev/null 2>&1; then
                      echo "Quality Gate needs PostgreSQL 16: set TEST_DB_URL, TEST_DB_USERNAME and TEST_DB_PASSWORD on the agent, or install docker." >&2
                      exit 1
                    fi
                    db_name="qg-$(echo "${BUILD_TAG:-local}" | tr -c 'a-zA-Z0-9_.-' '-')"
                    db_password="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \\n')"
                    docker run -d --rm --name "$db_name" -e POSTGRES_USER=qg -e POSTGRES_PASSWORD="$db_password" \\
                      -e POSTGRES_DB=db_of_payee_verification_test -p 127.0.0.1::5432 postgres:16-alpine >/dev/null
                    trap 'docker rm -f "$db_name" >/dev/null 2>&1 || true' EXIT
                    for _ in $(seq 1 30); do
                      docker exec "$db_name" pg_isready -U qg -d db_of_payee_verification_test >/dev/null 2>&1 && break
                      sleep 2
                    done
                    docker exec "$db_name" pg_isready -U qg -d db_of_payee_verification_test >/dev/null || { echo "PostgreSQL container did not become ready" >&2; exit 1; }
                    db_port="$(docker port "$db_name" 5432/tcp | head -n 1 | sed 's/.*://')"
                    export TEST_DB_URL="jdbc:postgresql://127.0.0.1:${db_port}/db_of_payee_verification_test"
                    export TEST_DB_USERNAME=qg TEST_DB_PASSWORD="$db_password"
                  fi
                  ./gradlew -p "${SERVICE_DIR}" --no-daemon clean check
                  # AsyncAPI contract gate (ADR-019 section 5), as ci/test runs it.
                  git rev-parse --verify -q origin/main >/dev/null || git fetch --no-tags origin +refs/heads/main:refs/remotes/origin/main
                  BASE_REF=origin/main ./scripts/ci/asyncapi-gate.sh
                '''
            }
        }
        stage('Security Gate') {
            steps {
                sh '''
                  set -euo pipefail
                  mkdir -p "${SERVICE_DIR}/build/reports/security"
                  ./gradlew -p "${SERVICE_DIR}" --no-daemon dependencies > "${SERVICE_DIR}/build/reports/security/dependencies.txt"
                  command -v trivy >/dev/null 2>&1
                  trivy fs --exit-code 1 --severity HIGH,CRITICAL "${SERVICE_DIR}"
                  command -v gitleaks >/dev/null 2>&1
                  gitleaks detect --no-git --source "${SERVICE_DIR}" --exit-code 1
                '''
            }
        }
        stage('Build Image') {
            steps {
                sh '''
                  set -euo pipefail
                  if command -v docker >/dev/null 2>&1; then
                    DOCKERFILE="${SERVICE_DIR}/Dockerfile"
                    if [ -f "${DOCKERFILE}" ]; then
                      docker build -t "${IMAGE_NAME}" -f "${DOCKERFILE}" "${SERVICE_DIR}"
                    else
                      docker build -t "${IMAGE_NAME}" "${SERVICE_DIR}"
                    fi
                  else
                    echo "docker not installed; skipping image build"
                  fi
                '''
            }
        }
        stage('Sign & Publish Image') {
            when {
                expression { return env.PUBLISH_IMAGE == 'true' }
            }
            steps {
                sh '''
                  set -euo pipefail

                  if command -v cosign >/dev/null 2>&1 && [ -n "${COSIGN_KEY:-}" ]; then
                    cosign sign --key "${COSIGN_KEY}" "${IMAGE_NAME}"
                  else
                    echo "cosign key not configured; skipping image signing"
                  fi

                  if command -v docker >/dev/null 2>&1 && [ -n "${DOCKER_REGISTRY:-}" ] && [ -n "${DOCKER_USERNAME:-}" ] && [ -n "${DOCKER_PASSWORD:-}" ]; then
                    echo "${DOCKER_PASSWORD}" | docker login "${DOCKER_REGISTRY}" --username "${DOCKER_USERNAME}" --password-stdin
                    docker push "${IMAGE_NAME}"
                  else
                    echo "registry credentials not configured; skipping image publish"
                  fi
                '''
            }
        }
    }
}
