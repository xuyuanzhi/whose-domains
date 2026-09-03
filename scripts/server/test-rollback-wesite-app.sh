#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
ROLLBACK_SCRIPT="$REPOSITORY_ROOT/scripts/server/rollback-wesite-app.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_link_target() {
  local link="$1"
  local expected="$2"
  local actual
  actual="$(realpath -e "$link")"
  [[ "$actual" == "$expected" ]] || fail "$link points to $actual, expected $expected"
}

assert_no_call_for() {
  local fixture="$1"
  local forbidden="$2"
  ! grep -Fq "$forbidden" "$fixture/calls.log" 2>/dev/null \
    || fail "unexpected call containing: $forbidden"
}

link_inode() {
  stat -c '%i' -- "$1"
}

write_metadata() {
  local release_dir="$1"
  local app="$2"
  local version="$3"
  local health_url="$4"
  local status="${5:-successful}"
  mkdir -p "$release_dir"
  printf '%s\n' "$app" > "$release_dir/APP"
  printf '%s\n' "$version" > "$release_dir/VERSION"
  printf '%064d\n' 0 > "$release_dir/SHA256"
  printf '%s\n' '2026-09-03T12:34:56Z' > "$release_dir/DEPLOYED_AT"
  printf '%s\n' "$status" > "$release_dir/STATUS"
  printf '%s\n' readiness > "$release_dir/HEALTH_MODE"
  printf '%s\n' "$health_url" > "$release_dir/HEALTH_URL"
}

make_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/apps/web/releases" "$fixture/apps/admin/releases" "$fixture/outside" "$fixture/fake-bin"
  write_metadata "$fixture/apps/web/releases/v0" web v0 http://127.0.0.1:8080/rollback-v0
  write_metadata "$fixture/apps/web/releases/v1" web v1 http://127.0.0.1:8080/current-v1
  write_metadata "$fixture/apps/admin/releases/v0" admin v0 http://127.0.0.1:8082/rollback-v0
  write_metadata "$fixture/apps/admin/releases/v1" admin v1 http://127.0.0.1:8082/current-v1
  ln -s "$fixture/apps/web/releases/v1" "$fixture/apps/web/current"
  ln -s "$fixture/apps/web/releases/v0" "$fixture/apps/web/previous"
  ln -s "$fixture/apps/admin/releases/v1" "$fixture/apps/admin/current"
  ln -s "$fixture/apps/admin/releases/v0" "$fixture/apps/admin/previous"
  : > "$fixture/calls.log"

  cat > "$fixture/fake-bin/systemctl" <<'EOF'
#!/bin/sh
printf 'systemctl %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
if [ "${1:-}" = restart ] && [ "${2:-}" = "${WESITE_TEST_SIGNAL_ON_SERVICE:-}" ] && [ ! -e "$WESITE_TEST_SIGNAL_SENT" ]; then
  : > "$WESITE_TEST_SIGNAL_SENT"
  kill -TERM "$PPID"
fi
exit 0
EOF

  cat > "$fixture/fake-bin/curl" <<'EOF'
#!/bin/sh
url=''
for argument in "$@"; do url="$argument"; done
printf 'curl %s\n' "$url" >> "$WESITE_TEST_CALL_LOG"
if [ "$url" = "${WESITE_TEST_FAIL_FIRST_URL:-}" ] && [ ! -e "$WESITE_TEST_HEALTH_FAILED" ]; then
  : > "$WESITE_TEST_HEALTH_FAILED"
  printf '{"status":"DOWN"}\n200'
  exit 0
fi
printf '{"status":"UP"}\n200'
EOF

  cat > "$fixture/fake-bin/mv" <<'EOF'
#!/bin/sh
destination=''
for argument in "$@"; do destination="$argument"; done
/usr/bin/mv "$@" || exit $?
if [ "$destination" = "${WESITE_TEST_SIGNAL_AFTER_PREVIOUS:-}" ] \
    && [ ! -e "$WESITE_TEST_PREVIOUS_SIGNAL_SENT" ]; then
  : > "$WESITE_TEST_PREVIOUS_SIGNAL_SENT"
  kill -TERM "$PPID"
fi
EOF
  chmod +x "$fixture/fake-bin/systemctl" "$fixture/fake-bin/curl" "$fixture/fake-bin/mv"
}

run_rollback() {
  local fixture="$1"
  local app="$2"
  env \
    PATH="$fixture/fake-bin:$PATH" \
    WESITE_APPS_BASE_DIR="$fixture/apps" \
    WESITE_DEPLOY_LOCK_FILE="$fixture/deploy.lock" \
    WESITE_HEALTH_ATTEMPTS=1 \
    WESITE_HEALTH_INTERVAL_SECONDS=0 \
    WESITE_TEST_CALL_LOG="$fixture/calls.log" \
    WESITE_TEST_FAIL_FIRST_URL="${WESITE_TEST_FAIL_FIRST_URL:-}" \
    WESITE_TEST_HEALTH_FAILED="$fixture/health-failed" \
    WESITE_TEST_SIGNAL_ON_SERVICE="${WESITE_TEST_SIGNAL_ON_SERVICE:-}" \
    WESITE_TEST_SIGNAL_SENT="$fixture/signal-sent" \
    WESITE_TEST_SIGNAL_AFTER_PREVIOUS="${WESITE_TEST_SIGNAL_AFTER_PREVIOUS:-}" \
    WESITE_TEST_PREVIOUS_SIGNAL_SENT="$fixture/previous-signal-sent" \
    bash "$ROLLBACK_SCRIPT" "$app"
}

test_successful_web_rollback_is_isolated() {
  local fixture="$TEST_ROOT/web-success"
  make_fixture "$fixture"
  run_rollback "$fixture" web

  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v0"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v1"
  assert_link_target "$fixture/apps/admin/previous" "$fixture/apps/admin/releases/v0"
  grep -Fq 'systemctl restart wesite-web.service' "$fixture/calls.log" || fail 'Web service was not restarted'
  assert_no_call_for "$fixture" wesite-admin.service
  assert_no_call_for "$fixture" 8082
}

test_successful_admin_rollback_is_isolated() {
  local fixture="$TEST_ROOT/admin-success"
  make_fixture "$fixture"
  run_rollback "$fixture" admin

  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v0"
  assert_link_target "$fixture/apps/admin/previous" "$fixture/apps/admin/releases/v1"
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v0"
  grep -Fq 'systemctl restart wesite-admin.service' "$fixture/calls.log" || fail 'Admin service was not restarted'
  assert_no_call_for "$fixture" wesite-web.service
  assert_no_call_for "$fixture" 8080
}

test_validation_rejections_fail_closed() {
  local fixture="$TEST_ROOT/validation"
  make_fixture "$fixture"

  if run_rollback "$fixture" invalid; then fail 'invalid APP accepted'; fi
  rm -f "$fixture/apps/web/previous"
  if run_rollback "$fixture" web; then fail 'missing previous accepted'; fi
  ln -s "$fixture/apps/admin/releases/v0" "$fixture/apps/web/previous"
  if run_rollback "$fixture" web; then fail 'previous outside selected application accepted'; fi
  rm -f "$fixture/apps/web/previous"
  ln -s "$fixture/apps/web/releases/v0" "$fixture/apps/web/previous"
  printf '%s\n' failed > "$fixture/apps/web/releases/v0/STATUS"
  if run_rollback "$fixture" web; then fail 'failed previous release accepted'; fi

  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v0"
  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v1"
  assert_link_target "$fixture/apps/admin/previous" "$fixture/apps/admin/releases/v0"
  ! grep -Fq 'systemctl ' "$fixture/calls.log" || fail 'validation rejection called systemctl'
}

test_contended_lock_fails_without_changes() {
  local fixture="$TEST_ROOT/lock-contention"
  local holder
  make_fixture "$fixture"
  (
    exec 8> "$fixture/deploy.lock"
    flock 8
    : > "$fixture/lock-acquired"
    sleep 30
  ) &
  holder=$!
  for _ in $(seq 1 100); do
    [[ -e "$fixture/lock-acquired" ]] && break
    sleep 0.01
  done
  [[ -e "$fixture/lock-acquired" ]] || fail 'test lock holder did not start'
  if run_rollback "$fixture" web; then
    kill "$holder" 2>/dev/null || true
    fail 'contended lock accepted rollback'
  fi
  kill "$holder" 2>/dev/null || true
  wait "$holder" 2>/dev/null || true
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v0"
  assert_no_call_for "$fixture" systemctl
}

test_unhealthy_target_restores_current_and_keeps_previous() {
  local fixture="$TEST_ROOT/unhealthy"
  local previous_inode
  make_fixture "$fixture"
  previous_inode="$(link_inode "$fixture/apps/web/previous")"
  if WESITE_TEST_FAIL_FIRST_URL=http://127.0.0.1:8080/rollback-v0 run_rollback "$fixture" web; then
    fail 'unhealthy rollback target accepted'
  fi
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v0"
  [[ "$(link_inode "$fixture/apps/web/previous")" == "$previous_inode" ]] \
    || fail 'unhealthy target replaced the unchanged previous link'
  [[ "$(grep -Fc 'systemctl restart wesite-web.service' "$fixture/calls.log")" -eq 2 ]] \
    || fail 'unhealthy target and restored current were not each restarted once'
  grep -Fq 'curl http://127.0.0.1:8080/current-v1' "$fixture/calls.log" \
    || fail 'restored current was not checked with its recorded health URL'
  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v1"
  assert_link_target "$fixture/apps/admin/previous" "$fixture/apps/admin/releases/v0"
  assert_no_call_for "$fixture" wesite-admin.service
  assert_no_call_for "$fixture" 8082
}

test_sigterm_restores_selected_pair_without_touching_other_app() {
  local fixture="$TEST_ROOT/signal"
  make_fixture "$fixture"
  if WESITE_TEST_SIGNAL_ON_SERVICE=wesite-web.service run_rollback "$fixture" web; then
    fail 'rollback unexpectedly succeeded after SIGTERM'
  fi
  [[ -e "$fixture/signal-sent" ]] || fail 'signal fixture did not run'
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v0"
  [[ "$(grep -Fc 'systemctl restart wesite-web.service' "$fixture/calls.log")" -eq 2 ]] \
    || fail 'SIGTERM recovery did not restart the original current release'
  grep -Fq 'curl http://127.0.0.1:8080/current-v1' "$fixture/calls.log" \
    || fail 'SIGTERM recovery did not check the original current health URL'
  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v1"
  assert_link_target "$fixture/apps/admin/previous" "$fixture/apps/admin/releases/v0"
  assert_no_call_for "$fixture" wesite-admin.service
  assert_no_call_for "$fixture" 8082
}

test_sigterm_after_previous_commit_restores_entry_pair() {
  local fixture="$TEST_ROOT/previous-signal"
  make_fixture "$fixture"
  if WESITE_TEST_SIGNAL_AFTER_PREVIOUS="$fixture/apps/web/previous" run_rollback "$fixture" web; then
    fail 'rollback unexpectedly succeeded after SIGTERM during previous commit'
  fi
  [[ -e "$fixture/previous-signal-sent" ]] || fail 'previous commit signal fixture did not run'
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v0"
  [[ "$(grep -Fc 'systemctl restart wesite-web.service' "$fixture/calls.log")" -eq 2 ]] \
    || fail 'post-previous-commit recovery did not restart the original current release'
  grep -Fq 'curl http://127.0.0.1:8080/current-v1' "$fixture/calls.log" \
    || fail 'post-previous-commit recovery did not check the original current URL'
  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v1"
  assert_link_target "$fixture/apps/admin/previous" "$fixture/apps/admin/releases/v0"
  assert_no_call_for "$fixture" wesite-admin.service
  assert_no_call_for "$fixture" 8082
}

test_successful_web_rollback_is_isolated
test_successful_admin_rollback_is_isolated
test_validation_rejections_fail_closed
test_contended_lock_fails_without_changes
test_unhealthy_target_restores_current_and_keeps_previous
test_sigterm_restores_selected_pair_without_touching_other_app
test_sigterm_after_previous_commit_restores_entry_pair
printf 'Independent rollback script tests passed: 7 scenarios.\n'
