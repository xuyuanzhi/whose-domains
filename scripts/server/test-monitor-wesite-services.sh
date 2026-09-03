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

make_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/fake-bin" "$fixture/state"
  : > "$fixture/restarts.log"

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
if [ "$url" = "${WESITE_TEST_FAIL_URL:-}" ]; then
  exit 22
fi
printf '{"status":"UP"}\n200'
EOF
  chmod +x "$fixture/fake-bin/systemctl" "$fixture/fake-bin/curl"
}

run_monitor() {
  local fixture="$1"
  env \
    PATH="$fixture/fake-bin:$PATH" \
    WESITE_HEALTH_STATE_DIR="$fixture/state" \
    WESITE_DEPLOY_LOCK_FILE="$fixture/deploy.lock" \
    WESITE_HEALTH_FAILURE_THRESHOLD=3 \
    WESITE_TEST_RESTART_LOG="$fixture/restarts.log" \
    WESITE_TEST_INACTIVE_SERVICE="${WESITE_TEST_INACTIVE_SERVICE:-}" \
    WESITE_TEST_FAIL_URL="${WESITE_TEST_FAIL_URL:-}" \
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

test_three_consecutive_failures_restart_only_the_failed_service
test_success_resets_the_consecutive_failure_counter
test_monitor_skips_checks_while_deployment_lock_is_held
printf 'Service monitor tests passed: 3.\n'
