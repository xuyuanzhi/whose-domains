#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
DEPLOY_SCRIPT="$REPOSITORY_ROOT/scripts/server/deploy-wesite-app.sh"
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

write_metadata() {
  local release_dir="$1"
  local app="$2"
  local version="$3"
  local health_url="$4"
  mkdir -p "$release_dir"
  printf '%s\n' "$app" > "$release_dir/APP"
  printf '%s\n' "$version" > "$release_dir/VERSION"
  printf '%064d\n' 0 > "$release_dir/SHA256"
  printf '%s\n' '2026-09-03T12:34:56Z' > "$release_dir/DEPLOYED_AT"
  printf '%s\n' successful > "$release_dir/STATUS"
  printf '%s\n' readiness > "$release_dir/HEALTH_MODE"
  printf '%s\n' "$health_url" > "$release_dir/HEALTH_URL"
}

make_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/apps/web/releases/v0" \
    "$fixture/apps/web/releases/v1" \
    "$fixture/apps/admin/releases/v0" \
    "$fixture/apps/admin/releases/v1" \
    "$fixture/incoming" "$fixture/outside" "$fixture/fake-bin"

  write_metadata "$fixture/apps/web/releases/v0" web v0 http://127.0.0.1:8080/api/readyz
  write_metadata "$fixture/apps/web/releases/v1" web v1 http://127.0.0.1:8080/api/readyz
  write_metadata "$fixture/apps/admin/releases/v0" admin v0 http://127.0.0.1:8082/api/readyz
  write_metadata "$fixture/apps/admin/releases/v1" admin v1 http://127.0.0.1:8082/api/readyz
  printf 'old web v0\n' > "$fixture/apps/web/releases/v0/wesite-web.jar"
  printf 'old web v1\n' > "$fixture/apps/web/releases/v1/wesite-web.jar"
  printf 'old admin v0\n' > "$fixture/apps/admin/releases/v0/wesite-admin.jar"
  printf 'old admin v1\n' > "$fixture/apps/admin/releases/v1/wesite-admin.jar"
  ln -s "$fixture/apps/web/releases/v1" "$fixture/apps/web/current"
  ln -s "$fixture/apps/web/releases/v0" "$fixture/apps/web/previous"
  ln -s "$fixture/apps/admin/releases/v1" "$fixture/apps/admin/current"
  ln -s "$fixture/apps/admin/releases/v0" "$fixture/apps/admin/previous"
  printf 'new web immutable bytes\n' > "$fixture/incoming/web.jar"
  printf 'new admin immutable bytes\n' > "$fixture/incoming/admin.jar"
  printf 'outside bytes\n' > "$fixture/outside/outside.jar"
  : > "$fixture/calls.log"

  cat > "$fixture/fake-bin/systemctl" <<'EOF'
#!/bin/sh
printf 'systemctl %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
if [ "${1:-}" = restart ] \
    && [ "${2:-}" = "${WESITE_TEST_SIGNAL_ON_SERVICE:-}" ] \
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
if [ "${WESITE_TEST_FAIL_FIRST_URL:-}" = "$url" ] \
    && [ ! -e "$WESITE_TEST_HEALTH_FAILED" ]; then
  : > "$WESITE_TEST_HEALTH_FAILED"
  printf '{"status":"DOWN"}\n200'
  exit 0
fi
case "${WESITE_TEST_CURL_MODE:-up}" in
  up) printf '{"status":"UP"}\n200' ;;
  html) printf '<html>legacy application</html>\n200' ;;
  *) exit 64 ;;
esac
EOF

  cat > "$fixture/fake-bin/unzip" <<'EOF'
#!/bin/sh
case "${1:-}" in
  -tqq)
    printf 'unzip -tqq %s\n' "${2:-}" >> "$WESITE_TEST_CALL_LOG"
    [ "${WESITE_TEST_ARCHIVE_MODE:-valid}" != corrupt ] || exit 9
    if [ -n "${WESITE_TEST_MUTATE_SOURCE:-}" ] \
        && [ ! -e "$WESITE_TEST_SOURCE_MUTATED" ]; then
      printf 'attacker replaced upload\n' > "$WESITE_TEST_MUTATE_SOURCE"
      : > "$WESITE_TEST_SOURCE_MUTATED"
    fi
    ;;
  -p)
    printf 'unzip -p %s %s\n' "${2:-}" "${3:-}" >> "$WESITE_TEST_CALL_LOG"
    case "${WESITE_TEST_MANIFEST_APP:-web}" in
      web) start_class=info.wesite.web.App ;;
      admin) start_class=info.wesite.admin.App ;;
      wrong) start_class=example.WrongApp ;;
      *) exit 65 ;;
    esac
    printf 'Manifest-Version: 1.0\r\nStart-Class: %s\r\n\r\n' "$start_class"
    ;;
  *) exit 64 ;;
esac
EOF
  chmod +x "$fixture/fake-bin/systemctl" "$fixture/fake-bin/curl" "$fixture/fake-bin/unzip"
}

run_deploy() {
  local fixture="$1"
  local app="$2"
  local version="$3"
  local jar="$4"
  local default_url
  case "$app" in
    web) default_url=http://127.0.0.1:8080/api/readyz ;;
    admin) default_url=http://127.0.0.1:8082/api/readyz ;;
    *) default_url=http://127.0.0.1:8080/api/readyz ;;
  esac

  env \
    PATH="$fixture/fake-bin:$PATH" \
    WESITE_APPS_BASE_DIR="$fixture/apps" \
    WESITE_INCOMING_DIR="$fixture/incoming" \
    WESITE_DEPLOY_LOCK_FILE="${WESITE_TEST_LOCK_FILE:-$fixture/deploy.lock}" \
    WESITE_HEALTH_ATTEMPTS=1 \
    WESITE_HEALTH_INTERVAL_SECONDS=0 \
    WESITE_HEALTH_RESPONSE_MODE="${WESITE_TEST_HEALTH_MODE:-readiness}" \
    WESITE_APP_HEALTH_URL="${WESITE_TEST_HEALTH_URL:-$default_url}" \
    WESITE_TEST_CALL_LOG="$fixture/calls.log" \
    WESITE_TEST_ARCHIVE_MODE="${WESITE_TEST_ARCHIVE_MODE:-valid}" \
    WESITE_TEST_MANIFEST_APP="${WESITE_TEST_MANIFEST_APP:-$app}" \
    WESITE_TEST_FAIL_FIRST_URL="${WESITE_TEST_FAIL_FIRST_URL:-}" \
    WESITE_TEST_HEALTH_FAILED="$fixture/health-failed" \
    WESITE_TEST_CURL_MODE="${WESITE_TEST_CURL_MODE:-up}" \
    WESITE_TEST_SIGNAL_ON_SERVICE="${WESITE_TEST_SIGNAL_ON_SERVICE:-}" \
    WESITE_TEST_SIGNAL_SENT="$fixture/signal-sent" \
    WESITE_TEST_MUTATE_SOURCE="${WESITE_TEST_MUTATE_SOURCE:-}" \
    WESITE_TEST_SOURCE_MUTATED="$fixture/source-mutated" \
    SUDO_USER="${WESITE_TEST_SUDO_USER:-}" \
    bash "$DEPLOY_SCRIPT" "$app" "$version" "$jar"
}

assert_success_metadata() {
  local release="$1"
  local app="$2"
  local version="$3"
  local mode="$4"
  local url="$5"
  local jar_name="$6"
  local expected_sha
  expected_sha="$(sha256sum "$release/$jar_name" | awk '{print $1}')"
  [[ "$(cat "$release/APP")" == "$app" ]] || fail 'APP metadata mismatch'
  [[ "$(cat "$release/VERSION")" == "$version" ]] || fail 'VERSION metadata mismatch'
  [[ "$(cat "$release/SHA256")" == "$expected_sha" ]] || fail 'SHA256 metadata mismatch'
  [[ "$(cat "$release/DEPLOYED_AT")" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
    || fail 'DEPLOYED_AT metadata is not UTC second precision'
  [[ "$(cat "$release/STATUS")" == successful ]] || fail 'STATUS metadata mismatch'
  [[ "$(cat "$release/HEALTH_MODE")" == "$mode" ]] || fail 'HEALTH_MODE metadata mismatch'
  [[ "$(cat "$release/HEALTH_URL")" == "$url" ]] || fail 'HEALTH_URL metadata mismatch'
  [[ "$(find "$release" -maxdepth 1 -type f | wc -l)" -eq 8 ]] \
    || fail 'release must contain one JAR and exactly seven metadata files'
}

test_successful_web_deploy_is_isolated() {
  local fixture="$TEST_ROOT/web-success"
  make_fixture "$fixture"
  run_deploy "$fixture" web v2 "$fixture/incoming/web.jar"

  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v2"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v1"
  assert_link_target "$fixture/apps/admin/previous" "$fixture/apps/admin/releases/v0"
  cmp -s "$fixture/incoming/web.jar" "$fixture/apps/web/releases/v2/wesite-web.jar" \
    || fail 'published Web JAR differs from staged JAR'
  assert_success_metadata "$fixture/apps/web/releases/v2" web v2 readiness \
    http://127.0.0.1:8080/api/readyz wesite-web.jar
  grep -Fq 'systemctl restart wesite-web.service' "$fixture/calls.log" \
    || fail 'Web service was not restarted'
  assert_no_call_for "$fixture" wesite-admin.service
  assert_no_call_for "$fixture" 8082
}

test_successful_admin_deploy_is_isolated() {
  local fixture="$TEST_ROOT/admin-success"
  make_fixture "$fixture"
  run_deploy "$fixture" admin v2 "$fixture/incoming/admin.jar"

  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v2"
  assert_link_target "$fixture/apps/admin/previous" "$fixture/apps/admin/releases/v1"
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v0"
  assert_success_metadata "$fixture/apps/admin/releases/v2" admin v2 readiness \
    http://127.0.0.1:8082/api/readyz wesite-admin.jar
  grep -Fq 'systemctl restart wesite-admin.service' "$fixture/calls.log" \
    || fail 'Admin service was not restarted'
  assert_no_call_for "$fixture" wesite-web.service
  assert_no_call_for "$fixture" 8080
}

test_failed_readiness_rolls_back_only_selected_app() {
  local fixture="$TEST_ROOT/readiness-rollback"
  make_fixture "$fixture"
  if WESITE_TEST_FAIL_FIRST_URL=http://127.0.0.1:8080/api/readyz \
      run_deploy "$fixture" web v2 "$fixture/incoming/web.jar"; then
    fail 'deployment unexpectedly succeeded after failed readiness'
  fi

  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v0"
  [[ "$(cat "$fixture/apps/web/releases/v2/STATUS")" == failed ]] \
    || fail 'failed candidate was not marked failed'
  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v1"
  assert_link_target "$fixture/apps/admin/previous" "$fixture/apps/admin/releases/v0"
  [[ "$(grep -Fc 'systemctl restart wesite-web.service' "$fixture/calls.log")" -eq 2 ]] \
    || fail 'Web candidate and restored release were not each restarted once'
  assert_no_call_for "$fixture" wesite-admin.service
}

test_failed_first_release_stops_only_selected_app() {
  local fixture="$TEST_ROOT/first-release-failure"
  make_fixture "$fixture"
  rm -f "$fixture/apps/admin/current" "$fixture/apps/admin/previous"
  if WESITE_TEST_FAIL_FIRST_URL=http://127.0.0.1:8082/api/readyz \
      run_deploy "$fixture" admin v2 "$fixture/incoming/admin.jar"; then
    fail 'first Admin release unexpectedly succeeded after failed readiness'
  fi

  [[ ! -e "$fixture/apps/admin/current" ]] || fail 'failed first release left Admin current'
  [[ "$(cat "$fixture/apps/admin/releases/v2/STATUS")" == failed ]] \
    || fail 'failed first candidate was not marked failed'
  grep -Fq 'systemctl stop wesite-admin.service' "$fixture/calls.log" \
    || fail 'failed first release did not stop Admin'
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_no_call_for "$fixture" wesite-web.service
}

test_validation_rejections_are_fail_closed() {
  local fixture="$TEST_ROOT/validation"
  local scenario
  make_fixture "$fixture"

  if run_deploy "$fixture" invalid v2 "$fixture/incoming/web.jar"; then fail 'invalid APP accepted'; fi
  if run_deploy "$fixture" web '../v2' "$fixture/incoming/web.jar"; then fail 'invalid VERSION accepted'; fi
  ln -s "$fixture/incoming/web.jar" "$fixture/incoming/web-link.jar"
  if run_deploy "$fixture" web v2 "$fixture/incoming/web-link.jar"; then fail 'source symlink accepted'; fi
  if run_deploy "$fixture" web v2 "$fixture/outside/outside.jar"; then fail 'outside source accepted'; fi

  for scenario in corrupt wrong; do
    rm -rf "$fixture/apps/web/releases/v2"
    if [[ "$scenario" == corrupt ]]; then
      if WESITE_TEST_ARCHIVE_MODE=corrupt run_deploy "$fixture" web v2 "$fixture/incoming/web.jar"; then
        fail 'corrupt ZIP accepted'
      fi
    else
      if WESITE_TEST_MANIFEST_APP=wrong run_deploy "$fixture" web v2 "$fixture/incoming/web.jar"; then
        fail 'wrong Start-Class accepted'
      fi
    fi
  done

  cp -R "$fixture/apps/web/releases/v1" "$fixture/apps/web/releases/v2"
  if run_deploy "$fixture" web v2 "$fixture/incoming/web.jar"; then fail 'existing version accepted'; fi
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/admin/current" "$fixture/apps/admin/releases/v1"
  ! grep -Fq 'systemctl ' "$fixture/calls.log" || fail 'validation rejection called systemctl'
}

test_contended_lock_rejects_deploy() {
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
  if WESITE_TEST_LOCK_FILE="$fixture/deploy.lock" \
      run_deploy "$fixture" web v2 "$fixture/incoming/web.jar"; then
    kill "$holder" 2>/dev/null || true
    fail 'contended lock accepted deployment'
  fi
  kill "$holder" 2>/dev/null || true
  wait "$holder" 2>/dev/null || true
  [[ ! -e "$fixture/apps/web/releases/v2" ]] || fail 'contended deploy created a release'
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
}

test_sigterm_after_switch_rolls_back() {
  local fixture="$TEST_ROOT/signal-rollback"
  make_fixture "$fixture"
  if WESITE_TEST_SIGNAL_ON_SERVICE=wesite-web.service \
      run_deploy "$fixture" web v2 "$fixture/incoming/web.jar"; then
    fail 'deployment unexpectedly succeeded after SIGTERM'
  fi
  assert_link_target "$fixture/apps/web/current" "$fixture/apps/web/releases/v1"
  assert_link_target "$fixture/apps/web/previous" "$fixture/apps/web/releases/v0"
  [[ "$(cat "$fixture/apps/web/releases/v2/STATUS")" == failed ]] \
    || fail 'terminated candidate was not marked failed'
  assert_no_call_for "$fixture" wesite-admin.service
}

test_legacy_overrides_require_direct_root() {
  local fixture="$TEST_ROOT/legacy-overrides"
  local legacy_url=http://127.0.0.1:8080/legacy
  make_fixture "$fixture"
  if WESITE_TEST_SUDO_USER=wesite-deploy \
      WESITE_TEST_HEALTH_MODE=legacy-http-200 \
      WESITE_TEST_HEALTH_URL="$legacy_url" \
      run_deploy "$fixture" web baseline-denied "$fixture/incoming/web.jar"; then
    fail 'unprivileged sudo caller accepted legacy overrides'
  fi
  [[ ! -e "$fixture/apps/web/releases/baseline-denied" ]] \
    || fail 'rejected legacy override created a release'

  WESITE_TEST_SUDO_USER='' \
    WESITE_TEST_HEALTH_MODE=legacy-http-200 \
    WESITE_TEST_HEALTH_URL="$legacy_url" \
    WESITE_TEST_CURL_MODE=html \
    run_deploy "$fixture" web baseline "$fixture/incoming/web.jar"
  assert_success_metadata "$fixture/apps/web/releases/baseline" web baseline \
    legacy-http-200 "$legacy_url" wesite-web.jar
}

test_private_copy_closes_source_toctou() {
  local fixture="$TEST_ROOT/private-copy"
  make_fixture "$fixture"
  cp "$fixture/incoming/web.jar" "$fixture/original-web.jar"
  WESITE_TEST_MUTATE_SOURCE="$fixture/incoming/web.jar" \
    run_deploy "$fixture" web v2 "$fixture/incoming/web.jar"

  [[ "$(cat "$fixture/incoming/web.jar")" == 'attacker replaced upload' ]] \
    || fail 'TOCTOU fixture did not mutate the unprivileged source'
  cmp -s "$fixture/original-web.jar" "$fixture/apps/web/releases/v2/wesite-web.jar" \
    || fail 'source mutation changed the published private copy'
  assert_success_metadata "$fixture/apps/web/releases/v2" web v2 readiness \
    http://127.0.0.1:8080/api/readyz wesite-web.jar
}

test_successful_web_deploy_is_isolated
test_successful_admin_deploy_is_isolated
test_failed_readiness_rolls_back_only_selected_app
test_failed_first_release_stops_only_selected_app
test_validation_rejections_are_fail_closed
test_contended_lock_rejects_deploy
test_sigterm_after_switch_rolls_back
test_legacy_overrides_require_direct_root
test_private_copy_closes_source_toctou
printf 'Independent deployment script tests passed: 9 scenarios.\n'
