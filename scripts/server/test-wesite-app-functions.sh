#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck source=wesite-app-functions.sh
source "$REPOSITORY_ROOT/scripts/server/wesite-app-functions.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

test_secure_lock_is_opened_without_truncation() {
  local directory="$TEST_ROOT/secure"
  local lock="$directory/deploy.lock"
  mkdir -m 0700 "$directory"
  printf '%s\n' preserved > "$lock"

  wesite_open_deployment_lock "$lock" || fail 'secure lock was rejected'
  flock -n 9 || fail 'secure lock descriptor could not be locked'
  [[ "$(cat "$lock")" == preserved ]] || fail 'opening the lock truncated it'
  exec 9>&-
}

test_symlink_lock_is_rejected_without_touching_target() {
  local directory="$TEST_ROOT/symlink"
  local target="$TEST_ROOT/target"
  mkdir -m 0700 "$directory"
  printf '%s\n' protected > "$target"
  ln -s "$target" "$directory/deploy.lock"

  if wesite_open_deployment_lock "$directory/deploy.lock"; then
    fail 'symbolic-link lock was accepted'
  fi
  [[ "$(cat "$target")" == protected ]] || fail 'symbolic-link target was modified'
}

test_writable_parent_is_rejected() {
  local directory="$TEST_ROOT/writable"
  mkdir -m 0777 "$directory"
  chmod 0777 "$directory"

  if wesite_open_deployment_lock "$directory/deploy.lock"; then
    fail 'group/world-writable lock parent was accepted'
  fi
  [[ ! -e "$directory/deploy.lock" ]] || fail 'rejected parent received a lock file'
}

test_secure_lock_is_opened_without_truncation
test_symlink_lock_is_rejected_without_touching_target
test_writable_parent_is_rejected
printf 'Application lock helper tests passed: 3.\n'
