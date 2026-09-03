#!/usr/bin/env bash
set -euo pipefail

ROOT_PREFIX="${WESITE_ROOT_PREFIX:-}"
SWAP_FILE="${WESITE_SWAP_FILE:-/swapfile}"
SWAP_SIZE="${WESITE_SWAP_SIZE:-2G}"
SWAP_PATH="$ROOT_PREFIX$SWAP_FILE"
FSTAB_PATH="$ROOT_PREFIX/etc/fstab"
FSTAB_ENTRY="$SWAP_FILE none swap sw 0 0"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

if [[ -z "$ROOT_PREFIX" && "$(id -u)" -ne 0 ]]; then
  fail 'Run this script as root'
fi
[[ "$SWAP_FILE" == /* ]] || fail 'WESITE_SWAP_FILE must be an absolute path'
[[ -f "$FSTAB_PATH" ]] || fail "fstab not found: $FSTAB_PATH"

ensure_fstab_entry() {
  if ! grep -Fqx "$FSTAB_ENTRY" "$FSTAB_PATH"; then
    printf '%s\n' "$FSTAB_ENTRY" >> "$FSTAB_PATH"
  fi
}

if swapon --show=NAME --noheadings | awk '{$1=$1};1' | grep -Fqx "$SWAP_FILE"; then
  ensure_fstab_entry
  printf 'Swap is already active: %s\n' "$SWAP_FILE"
  exit 0
fi

[[ ! -e "$SWAP_PATH" ]] || fail "$SWAP_PATH already exists but is not active; inspect it manually instead of overwriting it"

SIZE_BYTES="$(numfmt --from=iec "$SWAP_SIZE")" || fail "Invalid WESITE_SWAP_SIZE: $SWAP_SIZE"
PARENT_DIR="$(dirname "$SWAP_PATH")"
AVAILABLE_BYTES="$(df -B1 --output=avail "$PARENT_DIR" | tail -n 1 | tr -d ' ')"
RESERVE_BYTES=$((256 * 1024 * 1024))
(( AVAILABLE_BYTES > SIZE_BYTES + RESERVE_BYTES )) || fail "Not enough free disk space for $SWAP_SIZE swap plus a 256 MiB reserve"

if ! fallocate -l "$SWAP_SIZE" "$SWAP_PATH"; then
  dd if=/dev/zero of="$SWAP_PATH" bs=1M count=$((SIZE_BYTES / 1024 / 1024)) status=progress
fi
chmod 0600 "$SWAP_PATH"
mkswap "$SWAP_PATH"
swapon "$SWAP_PATH"
ensure_fstab_entry

printf 'Swap enabled: file=%s size=%s\n' "$SWAP_FILE" "$SWAP_SIZE"
