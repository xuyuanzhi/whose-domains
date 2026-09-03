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

mkdir -p "$TEST_ROOT/fake-bin"
cat > "$TEST_ROOT/fake-bin/systemctl" <<'EOF'
#!/bin/sh
[ "${WESITE_TEST_INACTIVE_SERVICE:-}" != "${3:-}" ]
EOF
cat > "$TEST_ROOT/fake-bin/curl" <<'EOF'
#!/bin/sh
url=''
for argument in "$@"; do url="$argument"; done
if [ "${WESITE_TEST_FAIL_URL:-}" = "$url" ]; then
  exit 22
fi
printf '{"status":"UP"}\n200'
EOF
chmod +x "$TEST_ROOT/fake-bin/systemctl" "$TEST_ROOT/fake-bin/curl"

OUTPUT="$(env PATH="$TEST_ROOT/fake-bin:$PATH" bash "$CHECK_SCRIPT")"
[[ "$OUTPUT" == *'All Whose.Domains services are healthy.'* ]] || fail 'healthy services were not accepted'

if env PATH="$TEST_ROOT/fake-bin:$PATH" WESITE_TEST_INACTIVE_SERVICE=wesite-admin.service bash "$CHECK_SCRIPT"; then
  fail 'inactive admin service was accepted'
fi

if env PATH="$TEST_ROOT/fake-bin:$PATH" WESITE_TEST_FAIL_URL=http://127.0.0.1:8080/api/readyz bash "$CHECK_SCRIPT"; then
  fail 'failed web health endpoint was accepted'
fi

printf 'Service health-check tests passed: 3.\n'
