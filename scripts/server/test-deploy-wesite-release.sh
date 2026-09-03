#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
DEPLOY_SCRIPT="$REPOSITORY_ROOT/scripts/server/deploy-wesite-release.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_file_equals() {
  local expected="$1"
  local actual="$2"
  cmp -s "$expected" "$actual" || fail "$actual does not match $expected"
}

assert_link_target() {
  local link="$1"
  local expected="$2"
  local actual
  actual="$(readlink -f "$link")"
  [[ "$actual" == "$expected" ]] || fail "$link points to $actual, expected $expected"
}

make_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/base/releases/v1" "$fixture/fake-bin" "$fixture/input"
  printf 'old web\n' > "$fixture/base/releases/v1/wesite-web.jar"
  printf 'old admin\n' > "$fixture/base/releases/v1/wesite-admin.jar"
  ln -s "$fixture/base/releases/v1" "$fixture/base/current"
  printf 'new web\n' > "$fixture/input/wesite-web.jar"
  printf 'new admin\n' > "$fixture/input/wesite-admin.jar"

  cat > "$fixture/fake-bin/systemctl" <<'EOF'
#!/bin/sh
printf 'systemctl %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
if [ -n "${WESITE_TEST_SIGNAL_ON_SERVICE:-}" ] \
    && [ "${2:-}" = "$WESITE_TEST_SIGNAL_ON_SERVICE" ] \
    && [ ! -e "$WESITE_TEST_SIGNAL_SENT" ]; then
  : > "$WESITE_TEST_SIGNAL_SENT"
  kill -TERM "$PPID"
fi
exit 0
EOF

  cat > "$fixture/fake-bin/curl" <<'EOF'
#!/bin/sh
url=''
for argument in "$@"; do
  url="$argument"
done
printf 'curl %s\n' "$url" >> "$WESITE_TEST_CALL_LOG"
if [ "${WESITE_TEST_FAIL_URL:-}" = "$url" ]; then
  exit 22
fi
printf '{"status":"UP"}\n200'
EOF
  chmod +x "$fixture/fake-bin/systemctl" "$fixture/fake-bin/curl"
}

run_deploy() {
  local fixture="$1"
  local version="$2"
  env \
    PATH="$fixture/fake-bin:$PATH" \
    WESITE_BASE_DIR="$fixture/base" \
    WESITE_HEALTH_ATTEMPTS=1 \
    WESITE_HEALTH_INTERVAL_SECONDS=0 \
    WESITE_DEPLOY_LOCK_FILE="${WESITE_TEST_LOCK_FILE:-$fixture/deploy.lock}" \
    WESITE_TEST_CALL_LOG="$fixture/calls.log" \
    WESITE_TEST_FAIL_URL="${WESITE_TEST_FAIL_URL:-}" \
    WESITE_TEST_SIGNAL_ON_SERVICE="${WESITE_TEST_SIGNAL_ON_SERVICE:-}" \
    WESITE_TEST_SIGNAL_SENT="$fixture/signal-sent" \
    bash "$DEPLOY_SCRIPT" \
      "$version" \
      "$fixture/input/wesite-web.jar" \
      "$fixture/input/wesite-admin.jar"
}

test_successful_deployment_switches_release_and_checks_services_in_order() {
  local fixture="$TEST_ROOT/success"
  make_fixture "$fixture"

  run_deploy "$fixture" v2

  assert_link_target "$fixture/base/current" "$fixture/base/releases/v2"
  assert_link_target "$fixture/base/previous" "$fixture/base/releases/v1"
  assert_file_equals "$fixture/input/wesite-web.jar" "$fixture/base/releases/v2/wesite-web.jar"
  assert_file_equals "$fixture/input/wesite-admin.jar" "$fixture/base/releases/v2/wesite-admin.jar"
  [[ "$(stat -c '%a' "$fixture/base/releases/v2")" == 755 ]] || fail 'release directory must be traversable by the service user'
  [[ "$(stat -c '%a' "$fixture/base/releases/v2/wesite-web.jar")" == 444 ]] || fail 'web JAR must be read-only and readable by the service user'
  [[ "$(stat -c '%a' "$fixture/base/releases/v2/wesite-admin.jar")" == 444 ]] || fail 'admin JAR must be read-only and readable by the service user'

  cat > "$fixture/expected-calls.log" <<'EOF'
systemctl restart wesite-admin.service
curl http://127.0.0.1:8082/api/readyz
systemctl restart wesite-web.service
curl http://127.0.0.1:8080/api/readyz
EOF
  assert_file_equals "$fixture/expected-calls.log" "$fixture/calls.log"
}

test_failed_health_check_restores_previous_release() {
  local fixture="$TEST_ROOT/rollback"
  make_fixture "$fixture"

  if WESITE_TEST_FAIL_URL='http://127.0.0.1:8080/api/readyz' run_deploy "$fixture" v2; then
    fail 'deployment unexpectedly succeeded when the web health check failed'
  fi

  assert_link_target "$fixture/base/current" "$fixture/base/releases/v1"
  [[ ! -e "$fixture/base/previous" ]] || fail 'failed deployment must not replace the previous link'

  cat > "$fixture/expected-calls.log" <<'EOF'
systemctl restart wesite-admin.service
curl http://127.0.0.1:8082/api/readyz
systemctl restart wesite-web.service
curl http://127.0.0.1:8080/api/readyz
systemctl restart wesite-admin.service
curl http://127.0.0.1:8082/api/readyz
systemctl restart wesite-web.service
curl http://127.0.0.1:8080/api/readyz
EOF
  assert_file_equals "$fixture/expected-calls.log" "$fixture/calls.log"
}

test_unsafe_version_is_rejected_without_switching_current() {
  local fixture="$TEST_ROOT/unsafe-version"
  make_fixture "$fixture"

  if run_deploy "$fixture" '../v2'; then
    fail 'deployment unexpectedly accepted a path-traversal version'
  fi

  assert_link_target "$fixture/base/current" "$fixture/base/releases/v1"
  [[ ! -e "$fixture/calls.log" ]] || fail 'services were called for an unsafe version'
}

test_contended_deployment_lock_rejects_second_release() {
  local fixture="$TEST_ROOT/lock-contention"
  local lock_holder
  local deploy_status
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

  if WESITE_TEST_LOCK_FILE="$fixture/deploy.lock" run_deploy "$fixture" v2; then
    deploy_status=0
  else
    deploy_status=$?
  fi
  kill "$lock_holder" 2>/dev/null || true
  wait "$lock_holder" 2>/dev/null || true

  [[ "$deploy_status" -ne 0 ]] || fail 'deployment ignored an already-held release lock'
  assert_link_target "$fixture/base/current" "$fixture/base/releases/v1"
  [[ ! -e "$fixture/base/releases/v2" ]] || fail 'contended deployment created a release'
}

test_termination_after_switch_restores_previous_release() {
  local fixture="$TEST_ROOT/signal-rollback"
  make_fixture "$fixture"

  if WESITE_TEST_SIGNAL_ON_SERVICE=wesite-admin.service run_deploy "$fixture" v2; then
    fail 'deployment unexpectedly succeeded after SIGTERM'
  fi

  assert_link_target "$fixture/base/current" "$fixture/base/releases/v1"
  [[ "$(grep -c '^systemctl restart wesite-admin.service$' "$fixture/calls.log")" -ge 2 ]] \
    || fail 'signal rollback did not restart the previous admin release'
  grep -q '^systemctl restart wesite-web.service$' "$fixture/calls.log" \
    || fail 'signal rollback did not restart the previous web release'
}

test_failed_first_release_removes_current_and_stops_both_services() {
  local fixture="$TEST_ROOT/first-release-failure"
  make_fixture "$fixture"
  rm -f "$fixture/base/current"
  rm -rf "$fixture/base/releases/v1"

  if WESITE_TEST_FAIL_URL='http://127.0.0.1:8080/api/readyz' run_deploy "$fixture" v2; then
    fail 'first deployment unexpectedly succeeded when the web health check failed'
  fi

  [[ ! -e "$fixture/base/current" ]] || fail 'failed first deployment left current in place'
  grep -q '^systemctl stop wesite-web.service$' "$fixture/calls.log" \
    || fail 'failed first deployment did not stop web'
  grep -q '^systemctl stop wesite-admin.service$' "$fixture/calls.log" \
    || fail 'failed first deployment did not stop admin'
}

test_successful_deployment_switches_release_and_checks_services_in_order
test_failed_health_check_restores_previous_release
test_unsafe_version_is_rejected_without_switching_current
test_termination_after_switch_restores_previous_release
test_contended_deployment_lock_rejects_second_release
test_failed_first_release_removes_current_and_stops_both_services
printf 'Deployment script tests passed: 6.\n'
