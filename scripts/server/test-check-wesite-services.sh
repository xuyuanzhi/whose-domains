#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
CHECK_SCRIPT="$REPOSITORY_ROOT/scripts/server/check-wesite-services.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

write_metadata() {
  local fixture="$1"
  local app="$2"
  local health_mode="$3"
  local health_url="$4"
  local release="$fixture/apps/$app/releases/v1"

  mkdir -p "$release"
  printf '%s\n' "$app" > "$release/APP"
  printf '%s\n' v1 > "$release/VERSION"
  printf '%064d\n' 0 > "$release/SHA256"
  printf '%s\n' '2026-09-03T12:34:56Z' > "$release/DEPLOYED_AT"
  printf '%s\n' successful > "$release/STATUS"
  printf '%s\n' "$health_mode" > "$release/HEALTH_MODE"
  printf '%s\n' "$health_url" > "$release/HEALTH_URL"
}

set_current() {
  local fixture="$1"
  local app="$2"
  ln -sfn "$fixture/apps/$app/releases/v1" "$fixture/apps/$app/current"
}

make_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/fake-bin"
  write_metadata "$fixture" web readiness http://127.0.0.1:8080/api/readyz
  write_metadata "$fixture" admin readiness http://127.0.0.1:8082/api/readyz
  set_current "$fixture" web
  set_current "$fixture" admin

  cat > "$fixture/fake-bin/systemctl" <<'EOF'
#!/bin/sh
[ "${WESITE_TEST_INACTIVE_SERVICE:-}" != "${3:-}" ]
EOF
  cat > "$fixture/fake-bin/curl" <<'EOF'
#!/bin/sh
url=''
for argument in "$@"; do url="$argument"; done
if [ "${WESITE_TEST_FAIL_URL:-}" = "$url" ]; then
  exit 22
fi
printf '{"status":"UP"}\n200'
EOF
  chmod +x "$fixture/fake-bin/systemctl" "$fixture/fake-bin/curl"
}

run_check() {
  local fixture="$1"
  env \
    PATH="$fixture/fake-bin:$PATH" \
    WESITE_APPS_BASE_DIR="$fixture/apps" \
    WESITE_TEST_INACTIVE_SERVICE="${WESITE_TEST_INACTIVE_SERVICE:-}" \
    WESITE_TEST_FAIL_URL="${WESITE_TEST_FAIL_URL:-}" \
    bash "$CHECK_SCRIPT"
}

test_global_status_succeeds_when_both_deployed_apps_pass() {
  local fixture="$TEST_ROOT/both-healthy"
  local output
  make_fixture "$fixture"

  output="$(run_check "$fixture")"
  [[ "$output" == *'All Whose.Domains services are healthy.'* ]] || fail 'healthy deployed applications were not accepted'
}

test_global_status_fails_when_deployed_app_is_unhealthy() {
  local fixture="$TEST_ROOT/web-unhealthy"
  make_fixture "$fixture"

  if WESITE_TEST_FAIL_URL=http://127.0.0.1:8080/api/readyz run_check "$fixture"; then
    fail 'unhealthy deployed web application was accepted'
  fi
}

test_undeployed_app_is_reported_without_failing_healthy_deployed_app() {
  local fixture="$TEST_ROOT/web-only"
  local output
  make_fixture "$fixture"
  rm "$fixture/apps/admin/current"

  output="$(run_check "$fixture" 2>&1)"
  [[ "$output" == *'admin not deployed'* ]] || fail 'missing admin current was not reported as not deployed'
  [[ "$output" == *'All Whose.Domains services are healthy.'* ]] || fail 'healthy deployed web application failed because admin is absent'
}

test_entirely_undeployed_host_fails_with_clear_message() {
  local fixture="$TEST_ROOT/none-deployed"
  local output
  make_fixture "$fixture"
  rm "$fixture/apps/admin/current" "$fixture/apps/web/current"

  if output="$(run_check "$fixture" 2>&1)"; then
    fail 'entirely undeployed host was accepted'
  fi
  [[ "$output" == *'no applications are deployed'* ]] || fail 'entirely undeployed host did not explain the failure'
}

test_global_status_succeeds_when_both_deployed_apps_pass
test_global_status_fails_when_deployed_app_is_unhealthy
test_undeployed_app_is_reported_without_failing_healthy_deployed_app
test_entirely_undeployed_host_fails_with_clear_message
printf 'Service health-check tests passed: 4.\n'
