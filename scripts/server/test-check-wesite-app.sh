#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
CHECK_SCRIPT="$REPOSITORY_ROOT/scripts/server/check-wesite-app.sh"
APP_FUNCTIONS="$REPOSITORY_ROOT/scripts/server/wesite-app-functions.sh"
TEST_ROOT="$(mktemp -d)"
CALL_LOG="$TEST_ROOT/calls.log"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

reset_calls() {
  : > "$CALL_LOG"
}

assert_no_other_app() {
  local other_service="$1"
  local other_port="$2"
  ! grep -Fq "$other_service" "$CALL_LOG" || fail "called the other application service: $other_service"
  ! grep -Fq "$other_port" "$CALL_LOG" || fail "called the other application health endpoint: $other_port"
}

write_metadata() {
  local release_dir="$1"
  local app="$2"
  local version="$3"
  local status="$4"
  local health_mode="$5"
  local health_url="$6"

  mkdir -p "$release_dir"
  printf '%s\n' "$app" > "$release_dir/APP"
  printf '%s\n' "$version" > "$release_dir/VERSION"
  printf '%064d\n' 0 > "$release_dir/SHA256"
  printf '%s\n' '2026-09-03T12:34:56Z' > "$release_dir/DEPLOYED_AT"
  printf '%s\n' "$status" > "$release_dir/STATUS"
  printf '%s\n' "$health_mode" > "$release_dir/HEALTH_MODE"
  printf '%s\n' "$health_url" > "$release_dir/HEALTH_URL"
}

set_current() {
  local app="$1"
  local version="$2"
  ln -sfn "$TEST_ROOT/apps/$app/releases/$version" "$TEST_ROOT/apps/$app/current"
}

run_check() {
  local app="$1"
  env \
    PATH="$TEST_ROOT/fake-bin:$PATH" \
    WESITE_APPS_BASE_DIR="$TEST_ROOT/apps" \
    WESITE_TEST_CALL_LOG="$CALL_LOG" \
    WESITE_TEST_RESPONSE_MODE="${WESITE_TEST_RESPONSE_MODE:-up}" \
    bash "$CHECK_SCRIPT" "$app"
}

mkdir -p "$TEST_ROOT/apps/web/releases" "$TEST_ROOT/apps/admin/releases" "$TEST_ROOT/fake-bin"

cat > "$TEST_ROOT/fake-bin/systemctl" <<'EOF'
#!/bin/sh
printf 'systemctl %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
[ "${WESITE_TEST_INACTIVE_SERVICE:-}" != "${3:-}" ]
EOF

cat > "$TEST_ROOT/fake-bin/curl" <<'EOF'
#!/bin/sh
url=''
for argument in "$@"; do
  url="$argument"
done
printf 'curl %s\n' "$url" >> "$WESITE_TEST_CALL_LOG"
case "${WESITE_TEST_RESPONSE_MODE:-up}" in
  up) printf '{"status":"UP"}\n200' ;;
  html) printf '<html>legacy application</html>\n200' ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/fake-bin/systemctl" "$TEST_ROOT/fake-bin/curl"

write_metadata "$TEST_ROOT/apps/web/releases/v1" web v1 successful readiness http://127.0.0.1:8080/api/readyz
write_metadata "$TEST_ROOT/apps/admin/releases/v1" admin v1 successful readiness http://127.0.0.1:8082/api/readyz
set_current web v1
set_current admin v1

reset_calls
run_check web
grep -Fq 'systemctl is-active --quiet wesite-web.service' "$CALL_LOG" || fail 'web service was not checked'
grep -Fq 'http://127.0.0.1:8080/api/readyz' "$CALL_LOG" || fail 'web health URL was not checked'
assert_no_other_app wesite-admin.service 8082

reset_calls
run_check admin
grep -Fq 'systemctl is-active --quiet wesite-admin.service' "$CALL_LOG" || fail 'admin service was not checked'
grep -Fq 'http://127.0.0.1:8082/api/readyz' "$CALL_LOG" || fail 'admin health URL was not checked'
assert_no_other_app wesite-web.service 8080

reset_calls
if run_check invalid; then
  fail 'invalid APP was accepted'
fi
[[ ! -s "$CALL_LOG" ]] || fail 'invalid APP called a service or endpoint'

reset_calls
if WESITE_TEST_RESPONSE_MODE=html run_check web; then
  fail 'strict release accepted HTML'
fi
grep -Fq 'wesite-web.service' "$CALL_LOG" || fail 'strict web check did not call web'
assert_no_other_app wesite-admin.service 8082

printf 'legacy-http-200\n' > "$TEST_ROOT/apps/web/releases/v1/HEALTH_MODE"
reset_calls
WESITE_TEST_RESPONSE_MODE=html run_check web || fail 'legacy release rejected non-empty HTTP 200 response'
assert_no_other_app wesite-admin.service 8082
printf 'readiness\n' > "$TEST_ROOT/apps/web/releases/v1/HEALTH_MODE"

rm "$TEST_ROOT/apps/web/current"
reset_calls
if run_check web; then
  fail 'missing current link was accepted'
fi
[[ ! -s "$CALL_LOG" ]] || fail 'missing current link called a service or endpoint'
set_current web v1

ln -sfn "$TEST_ROOT/apps/admin/releases/v1" "$TEST_ROOT/apps/web/current"
reset_calls
if run_check web; then
  fail 'current target outside the selected app tree was accepted'
fi
[[ ! -s "$CALL_LOG" ]] || fail 'outside current target called a service or endpoint'
set_current web v1

rm "$TEST_ROOT/apps/web/releases/v1/HEALTH_URL"
reset_calls
if run_check web; then
  fail 'missing release metadata was accepted'
fi
[[ ! -s "$CALL_LOG" ]] || fail 'missing metadata called a service or endpoint'
printf 'http://127.0.0.1:8080/api/readyz\n' > "$TEST_ROOT/apps/web/releases/v1/HEALTH_URL"

printf 'deploying\n' > "$TEST_ROOT/apps/web/releases/v1/STATUS"
reset_calls
if run_check web; then
  fail 'non-success current release was accepted'
fi
[[ ! -s "$CALL_LOG" ]] || fail 'non-success release called a service or endpoint'
printf 'successful\n' > "$TEST_ROOT/apps/web/releases/v1/STATUS"

printf 'unsafe-mode\n' > "$TEST_ROOT/apps/web/releases/v1/HEALTH_MODE"
reset_calls
if run_check web; then
  fail 'unsafe health mode was accepted'
fi
[[ ! -s "$CALL_LOG" ]] || fail 'unsafe health mode called a service or endpoint'
printf 'readiness\n' > "$TEST_ROOT/apps/web/releases/v1/HEALTH_MODE"

reset_calls
if WESITE_TEST_INACTIVE_SERVICE=wesite-web.service run_check web; then
  fail 'inactive selected service was accepted'
fi
grep -Fq 'wesite-web.service' "$CALL_LOG" || fail 'inactive selected service was not checked'
! grep -Fq 'curl ' "$CALL_LOG" || fail 'inactive selected service called its health endpoint'
assert_no_other_app wesite-admin.service 8082

printf 'admin\n' > "$TEST_ROOT/apps/web/releases/v1/APP"
reset_calls
if run_check web; then
  fail 'mismatched metadata APP was accepted'
fi
[[ ! -s "$CALL_LOG" ]] || fail 'mismatched APP called a service or endpoint'
printf 'web\n' > "$TEST_ROOT/apps/web/releases/v1/APP"

printf 'other\n' > "$TEST_ROOT/apps/web/releases/v1/VERSION"
if run_check web; then
  fail 'mismatched metadata VERSION was accepted'
fi
printf 'v1\n' > "$TEST_ROOT/apps/web/releases/v1/VERSION"

printf 'not-a-checksum\n' > "$TEST_ROOT/apps/web/releases/v1/SHA256"
if run_check web; then
  fail 'unsafe SHA256 metadata was accepted'
fi
printf '%064d\n' 0 > "$TEST_ROOT/apps/web/releases/v1/SHA256"

printf 'not-a-timestamp\n' > "$TEST_ROOT/apps/web/releases/v1/DEPLOYED_AT"
if run_check web; then
  fail 'unsafe DEPLOYED_AT metadata was accepted'
fi
printf '%s\n' '2026-09-03T12:34:56Z' > "$TEST_ROOT/apps/web/releases/v1/DEPLOYED_AT"

printf 'http://127.0.0.1:8082/api/readyz\n' > "$TEST_ROOT/apps/web/releases/v1/HEALTH_URL"
if run_check web; then
  fail 'health URL on the wrong port was accepted'
fi
printf 'http://127.0.0.1:8080/api/readyz\n' > "$TEST_ROOT/apps/web/releases/v1/HEALTH_URL"

# shellcheck source=/dev/null
source "$APP_FUNCTIONS"
printf 'deploying\n' > "$TEST_ROOT/apps/web/releases/v1/STATUS"
reset_calls
PATH="$TEST_ROOT/fake-bin:$PATH" WESITE_TEST_CALL_LOG="$CALL_LOG" \
  WESITE_APPS_BASE_DIR="$TEST_ROOT/apps" \
  wesite_check_release_health web "$TEST_ROOT/apps/web/releases/v1" \
  || fail 'transaction-owned deploying release was rejected'
grep -Fq 'wesite-web.service' "$CALL_LOG" || fail 'deploying release did not check web service'
assert_no_other_app wesite-admin.service 8082

printf 'Single application health-check tests passed: 15.\n'
