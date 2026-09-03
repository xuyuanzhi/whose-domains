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
  : > "$fixture/calls.log"
  write_metadata "$fixture" web readiness http://127.0.0.1:8080/api/readyz
  write_metadata "$fixture" admin readiness http://127.0.0.1:8082/api/readyz
  set_current "$fixture" web
  set_current "$fixture" admin

  cat > "$fixture/fake-bin/systemctl" <<'EOF'
#!/bin/sh
printf 'systemctl %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
[ "${WESITE_TEST_INACTIVE_SERVICE:-}" != "${3:-}" ]
EOF
  cat > "$fixture/fake-bin/curl" <<'EOF'
#!/bin/sh
url=''
for argument in "$@"; do url="$argument"; done
printf 'curl %s\n' "$url" >> "$WESITE_TEST_CALL_LOG"
if [ "${WESITE_TEST_FAIL_URL:-}" = "$url" ]; then
  exit 22
fi
if [ "$url" = "${WESITE_TEST_LEGACY_URL:-}" ]; then
  printf '<html>legacy application</html>\n200'
  exit 0
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
    WESITE_TEST_CALL_LOG="$fixture/calls.log" \
    WESITE_TEST_INACTIVE_SERVICE="${WESITE_TEST_INACTIVE_SERVICE:-}" \
    WESITE_TEST_FAIL_URL="${WESITE_TEST_FAIL_URL:-}" \
    WESITE_TEST_LEGACY_URL="${WESITE_TEST_LEGACY_URL:-}" \
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

test_global_status_fails_when_admin_is_inactive_but_web_remains_healthy() {
  local fixture="$TEST_ROOT/admin-inactive"
  local output
  make_fixture "$fixture"

  if output="$(WESITE_TEST_INACTIVE_SERVICE=wesite-admin.service run_check "$fixture" 2>&1)"; then
    fail 'inactive deployed admin application was accepted'
  fi
  [[ "$output" == *'FAIL application unhealthy: admin'* ]] \
    || fail 'inactive admin was not reported unhealthy'
  [[ "$output" == *'PASS application healthy: web'* ]] \
    || fail 'admin failure prevented the independent web health check'
  grep -Fq 'systemctl is-active --quiet wesite-web.service' "$fixture/calls.log" \
    || fail 'inactive admin prevented the web service health check'
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

test_global_status_uses_deployed_legacy_health_contract() {
  local fixture="$TEST_ROOT/legacy-contract"
  local output
  make_fixture "$fixture"
  rm "$fixture/apps/admin/current"
  write_metadata "$fixture" web legacy-http-200 http://127.0.0.1:8080/legacy-health

  output="$(WESITE_TEST_LEGACY_URL=http://127.0.0.1:8080/legacy-health run_check "$fixture" 2>&1)"

  [[ "$output" == *'All Whose.Domains services are healthy.'* ]] \
    || fail 'legacy metadata health contract was rejected'
  grep -Fq 'curl http://127.0.0.1:8080/legacy-health' "$fixture/calls.log" \
    || fail 'global status did not use the recorded legacy health URL'
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

test_dangling_current_link_is_unhealthy_not_undeployed() {
  local fixture="$TEST_ROOT/dangling-current"
  local output
  make_fixture "$fixture"
  rm "$fixture/apps/admin/current"
  ln -s "$fixture/apps/admin/releases/missing" "$fixture/apps/admin/current"

  if output="$(run_check "$fixture" 2>&1)"; then
    fail 'dangling admin current link was accepted as undeployed'
  fi
  [[ "$output" == *'FAIL application unhealthy: admin'* ]] \
    || fail 'dangling admin current link was not reported unhealthy'
  [[ "$output" != *'admin not deployed'* ]] \
    || fail 'dangling admin current link was reported as undeployed'
}

test_global_status_succeeds_when_both_deployed_apps_pass
test_global_status_fails_when_deployed_app_is_unhealthy
test_global_status_fails_when_admin_is_inactive_but_web_remains_healthy
test_undeployed_app_is_reported_without_failing_healthy_deployed_app
test_global_status_uses_deployed_legacy_health_contract
test_entirely_undeployed_host_fails_with_clear_message
test_dangling_current_link_is_unhealthy_not_undeployed
printf 'Service health-check tests passed: 7.\n'
