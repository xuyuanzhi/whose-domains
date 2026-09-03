#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
MONITOR_SCRIPT="$REPOSITORY_ROOT/scripts/server/monitor-wesite-services.sh"
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
  local release_dir="$1"
  local app="$2"
  local health_mode="$3"
  local health_url="$4"

  mkdir -p "$release_dir"
  printf '%s\n' "$app" > "$release_dir/APP"
  printf '%s\n' 'v1' > "$release_dir/VERSION"
  printf '%064d\n' 0 > "$release_dir/SHA256"
  printf '%s\n' '2026-09-03T12:34:56Z' > "$release_dir/DEPLOYED_AT"
  printf '%s\n' successful > "$release_dir/STATUS"
  printf '%s\n' "$health_mode" > "$release_dir/HEALTH_MODE"
  printf '%s\n' "$health_url" > "$release_dir/HEALTH_URL"
}

set_current() {
  local fixture="$1"
  local app="$2"
  ln -sfn "$fixture/apps/$app/releases/v1" "$fixture/apps/$app/current"
}

make_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/fake-bin" "$fixture/state"
  : > "$fixture/restarts.log"
  : > "$fixture/calls.log"

  write_metadata "$fixture/apps/web/releases/v1" web readiness http://127.0.0.1:8080/api/readyz
  write_metadata "$fixture/apps/admin/releases/v1" admin readiness http://127.0.0.1:8082/api/readyz
  set_current "$fixture" web
  set_current "$fixture" admin

  cat > "$fixture/fake-bin/systemctl" <<'EOF'
#!/bin/sh
case "${1:-}" in
  is-active)
    [ "${3:-}" != "${WESITE_TEST_INACTIVE_SERVICE:-}" ]
    ;;
  restart)
    printf '%s\n' "${2:-}" >> "$WESITE_TEST_RESTART_LOG"
    ;;
  *)
    exit 64
    ;;
esac
EOF

  cat > "$fixture/fake-bin/curl" <<'EOF'
#!/bin/sh
url=''
for argument in "$@"; do url="$argument"; done
printf '%s\n' "$url" >> "$WESITE_TEST_CALL_LOG"
if [ "$url" = "${WESITE_TEST_FAIL_URL:-}" ]; then
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

run_monitor() {
  local fixture="$1"
  env \
    PATH="$fixture/fake-bin:$PATH" \
    WESITE_APPS_BASE_DIR="$fixture/apps" \
    WESITE_HEALTH_STATE_DIR="$fixture/state" \
    WESITE_DEPLOY_LOCK_FILE="$fixture/deploy.lock" \
    WESITE_HEALTH_FAILURE_THRESHOLD=3 \
    WESITE_TEST_RESTART_LOG="$fixture/restarts.log" \
    WESITE_TEST_CALL_LOG="$fixture/calls.log" \
    WESITE_TEST_INACTIVE_SERVICE="${WESITE_TEST_INACTIVE_SERVICE:-}" \
    WESITE_TEST_FAIL_URL="${WESITE_TEST_FAIL_URL:-}" \
    WESITE_TEST_LEGACY_URL="${WESITE_TEST_LEGACY_URL:-}" \
    bash "$MONITOR_SCRIPT"
}

test_three_consecutive_failures_restart_only_the_failed_service() {
  local fixture="$TEST_ROOT/threshold"
  make_fixture "$fixture"

  WESITE_TEST_FAIL_URL=http://127.0.0.1:8080/api/readyz run_monitor "$fixture"
  WESITE_TEST_FAIL_URL=http://127.0.0.1:8080/api/readyz run_monitor "$fixture"
  [[ ! -s "$fixture/restarts.log" ]] || fail 'service restarted before reaching the threshold'

  WESITE_TEST_FAIL_URL=http://127.0.0.1:8080/api/readyz run_monitor "$fixture"

  [[ "$(cat "$fixture/restarts.log")" == 'wesite-web.service' ]] \
    || fail 'third web readiness failure did not restart only the web service'
}

test_success_resets_the_consecutive_failure_counter() {
  local fixture="$TEST_ROOT/reset"
  make_fixture "$fixture"

  WESITE_TEST_FAIL_URL=http://127.0.0.1:8082/api/readyz run_monitor "$fixture"
  WESITE_TEST_FAIL_URL=http://127.0.0.1:8082/api/readyz run_monitor "$fixture"
  run_monitor "$fixture"
  WESITE_TEST_FAIL_URL=http://127.0.0.1:8082/api/readyz run_monitor "$fixture"
  WESITE_TEST_FAIL_URL=http://127.0.0.1:8082/api/readyz run_monitor "$fixture"

  [[ ! -s "$fixture/restarts.log" ]] || fail 'a healthy check did not reset the failure counter'
}

test_monitor_skips_checks_while_deployment_lock_is_held() {
  local fixture="$TEST_ROOT/lock"
  local lock_holder
  make_fixture "$fixture"

  (
    exec 8> "$fixture/deploy.lock"
    flock 8
    : > "$fixture/lock-acquired"
    sleep 30
  ) &
  lock_holder=$!
  for _ in $(seq 1 100); do
    [[ -e "$fixture/lock-acquired" ]] && break
    sleep 0.01
  done
  [[ -e "$fixture/lock-acquired" ]] || fail 'test could not acquire deployment lock'

  WESITE_TEST_FAIL_URL=http://127.0.0.1:8080/api/readyz run_monitor "$fixture"
  kill "$lock_holder" 2>/dev/null || true
  wait "$lock_holder" 2>/dev/null || true

  [[ ! -e "$fixture/state/web.failures" ]] || fail 'monitor changed state during deployment'
  [[ ! -s "$fixture/restarts.log" ]] || fail 'monitor restarted a service during deployment'
}

test_web_only_deployment_skips_admin_without_state_or_restart() {
  local fixture="$TEST_ROOT/web-only"
  local output
  make_fixture "$fixture"
  rm "$fixture/apps/admin/current"

  output="$(run_monitor "$fixture" 2>&1)"

  [[ "$output" == *'admin not deployed'* ]] || fail 'web-only deployment did not report admin as not deployed'
  [[ ! -e "$fixture/state/admin.failures" ]] || fail 'undeployed admin received a failure-state file'
  [[ ! -s "$fixture/restarts.log" ]] || fail 'undeployed admin was restarted'
}

test_admin_only_deployment_skips_web_without_state_or_restart() {
  local fixture="$TEST_ROOT/admin-only"
  local output
  make_fixture "$fixture"
  rm "$fixture/apps/web/current"

  output="$(run_monitor "$fixture" 2>&1)"

  [[ "$output" == *'web not deployed'* ]] || fail 'admin-only deployment did not report web as not deployed'
  [[ ! -e "$fixture/state/web.failures" ]] || fail 'undeployed web received a failure-state file'
  [[ ! -s "$fixture/restarts.log" ]] || fail 'undeployed web was restarted'
}

test_monitor_uses_deployed_release_health_contract() {
  local fixture="$TEST_ROOT/health-contract"
  make_fixture "$fixture"
  write_metadata "$fixture/apps/web/releases/v1" web legacy-http-200 http://127.0.0.1:8080/legacy-health

  WESITE_TEST_LEGACY_URL=http://127.0.0.1:8080/legacy-health run_monitor "$fixture"

  grep -Fxq 'http://127.0.0.1:8080/legacy-health' "$fixture/calls.log" \
    || fail 'monitor did not use web release metadata health URL'
  [[ ! -e "$fixture/state/web.failures" ]] \
    || fail 'legacy metadata health mode was treated as a readiness failure'
  [[ ! -s "$fixture/restarts.log" ]] \
    || fail 'legacy metadata health mode restarted web'
}

test_dangling_current_link_counts_as_failure_and_restarts() {
  local fixture="$TEST_ROOT/dangling-current"
  local output
  make_fixture "$fixture"
  rm "$fixture/apps/admin/current"
  ln -s "$fixture/apps/admin/releases/missing" "$fixture/apps/admin/current"

  output="$(run_monitor "$fixture" 2>&1)"
  run_monitor "$fixture" >/dev/null 2>&1
  run_monitor "$fixture" >/dev/null 2>&1

  [[ "$output" != *'admin not deployed'* ]] \
    || fail 'dangling admin current link was reported as undeployed'
  grep -Fxq 'wesite-admin.service' "$fixture/restarts.log" \
    || fail 'dangling admin current link did not reach recovery threshold'
}

test_three_consecutive_failures_restart_only_the_failed_service
test_success_resets_the_consecutive_failure_counter
test_monitor_skips_checks_while_deployment_lock_is_held
test_web_only_deployment_skips_admin_without_state_or_restart
test_admin_only_deployment_skips_web_without_state_or_restart
test_monitor_uses_deployed_release_health_contract
test_dangling_current_link_counts_as_failure_and_restarts
printf 'Service monitor tests passed: 7.\n'
