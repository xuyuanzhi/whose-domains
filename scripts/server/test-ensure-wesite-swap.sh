#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SWAP_SCRIPT="$REPOSITORY_ROOT/scripts/server/ensure-wesite-swap.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

mkdir -p "$TEST_ROOT/root/etc" "$TEST_ROOT/fake-bin"
: > "$TEST_ROOT/root/etc/fstab"

cat > "$TEST_ROOT/fake-bin/mkswap" <<'EOF'
#!/bin/sh
printf 'mkswap %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
exit 0
EOF

cat > "$TEST_ROOT/fake-bin/swapon" <<'EOF'
#!/bin/sh
if [ "${1:-}" = '--show=NAME' ]; then
  if [ -f "$WESITE_TEST_SWAP_ACTIVE" ]; then
    printf '%s\n' "$WESITE_SWAP_FILE"
  fi
  exit 0
fi
printf 'swapon %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
: > "$WESITE_TEST_SWAP_ACTIVE"
EOF
chmod +x "$TEST_ROOT/fake-bin/mkswap" "$TEST_ROOT/fake-bin/swapon"

run_script() {
  env \
    PATH="$TEST_ROOT/fake-bin:$PATH" \
    WESITE_ROOT_PREFIX="$TEST_ROOT/root" \
    WESITE_SWAP_FILE=/swapfile \
    WESITE_SWAP_SIZE=1M \
    WESITE_TEST_CALL_LOG="$TEST_ROOT/calls.log" \
    WESITE_TEST_SWAP_ACTIVE="$TEST_ROOT/swap-active" \
    bash "$SWAP_SCRIPT"
}

run_script
run_script

[[ -f "$TEST_ROOT/root/swapfile" ]] || fail 'swap file was not created'
[[ "$(stat -c '%a' "$TEST_ROOT/root/swapfile")" == 600 ]] || fail 'swap file permissions are not 0600'
[[ "$(grep -Fxc '/swapfile none swap sw 0 0' "$TEST_ROOT/root/etc/fstab")" == 1 ]] || fail 'fstab entry is missing or duplicated'

cat > "$TEST_ROOT/expected-calls.log" <<EOF
mkswap $TEST_ROOT/root/swapfile
swapon $TEST_ROOT/root/swapfile
EOF
cmp -s "$TEST_ROOT/expected-calls.log" "$TEST_ROOT/calls.log" || fail 'swap commands were not idempotent'

printf 'Swap setup tests passed: 1.\n'
