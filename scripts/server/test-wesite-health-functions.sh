#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
HEALTH_FUNCTIONS="$REPOSITORY_ROOT/scripts/server/wesite-health-functions.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

mkdir -p "$TEST_ROOT/fake-bin"
cat > "$TEST_ROOT/fake-bin/curl" <<'EOF'
#!/bin/sh
case "${WESITE_TEST_CURL_MODE:-up}" in
  up) printf '{"status":"UP"}\n200' ;;
  redirect) printf '<html>login</html>\n302' ;;
  unauthenticated) printf '{"code":401,"message":"login"}\n200' ;;
  html) printf '<html>legacy application</html>\n200' ;;
  empty) printf '\n200' ;;
  transport-error) exit 7 ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/fake-bin/curl"

# shellcheck source=/dev/null
source "$HEALTH_FUNCTIONS"

PATH="$TEST_ROOT/fake-bin:$PATH"
export PATH

WESITE_TEST_CURL_MODE=up wesite_readiness_check http://127.0.0.1/readyz \
  || fail 'a strict 200 UP readiness response was rejected'

for mode in redirect unauthenticated html empty transport-error; do
  if WESITE_TEST_CURL_MODE="$mode" wesite_readiness_check http://127.0.0.1/readyz; then
    fail "$mode response was accepted as ready"
  fi
done

WESITE_HEALTH_RESPONSE_MODE=legacy-http-200 \
  WESITE_TEST_CURL_MODE=html \
  wesite_readiness_check http://127.0.0.1/ \
  || fail 'legacy HTTP 200 mode rejected a non-empty 200 response'

for mode in redirect empty; do
  if WESITE_HEALTH_RESPONSE_MODE=legacy-http-200 \
      WESITE_TEST_CURL_MODE="$mode" \
      wesite_readiness_check http://127.0.0.1/; then
    fail "legacy mode accepted a $mode response"
  fi
done

printf 'Strict readiness function tests passed: 9.\n'
