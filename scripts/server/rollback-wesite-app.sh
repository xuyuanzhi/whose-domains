#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=wesite-app-functions.sh
source "$SCRIPT_DIR/wesite-app-functions.sh"

usage() {
  printf 'Usage: rollback-wesite-app.sh {web|admin}\n' >&2
  exit 64
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ $# -eq 1 ]] || usage
APP="$1"
wesite_select_app "$APP" || usage

HEALTH_ATTEMPTS="${WESITE_HEALTH_ATTEMPTS:-30}"
HEALTH_INTERVAL_SECONDS="${WESITE_HEALTH_INTERVAL_SECONDS:-2}"
LOCK_FILE="${WESITE_DEPLOY_LOCK_FILE:-/run/lock/wesite-deploy.lock}"
APP_BASE="$WESITE_SELECTED_BASE"
CURRENT_LINK="$APP_BASE/current"
PREVIOUS_LINK="$APP_BASE/previous"

[[ "$HEALTH_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || fail 'WESITE_HEALTH_ATTEMPTS must be a positive integer'
[[ "$HEALTH_INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || fail 'WESITE_HEALTH_INTERVAL_SECONDS must be a non-negative integer'
[[ "$LOCK_FILE" == /* ]] || fail 'WESITE_DEPLOY_LOCK_FILE must be an absolute path'
command -v flock >/dev/null 2>&1 || fail 'flock is required'

exec 9> "$LOCK_FILE"
flock -n 9 || fail 'Another Whose.Domains deployment or health recovery is already running'

read_successful_link() {
  local link="$1"
  local description="$2"
  local target

  [[ -L "$link" ]] || fail "$description release link is missing or is not a symbolic link"
  target="$(realpath -e "$link")" || fail "$description release link is broken"
  wesite_release_directory_is_selected "$target" \
    || fail "$description release is outside the selected application"
  wesite_load_release_metadata "$target" || fail "$description release metadata is invalid"
  [[ "$WESITE_RELEASE_STATUS" == successful ]] || fail "$description release is not successful"
  printf '%s' "$target"
}

ORIGINAL_CURRENT_TARGET="$(read_successful_link "$CURRENT_LINK" current)"
wesite_load_release_metadata "$ORIGINAL_CURRENT_TARGET" || fail 'current release metadata is invalid'
ORIGINAL_CURRENT_MODE="$WESITE_RELEASE_HEALTH_MODE"
ORIGINAL_CURRENT_URL="$WESITE_RELEASE_HEALTH_URL"
ORIGINAL_PREVIOUS_TARGET="$(read_successful_link "$PREVIOUS_LINK" previous)"
wesite_load_release_metadata "$ORIGINAL_PREVIOUS_TARGET" || fail 'previous release metadata is invalid'
TARGET_MODE="$WESITE_RELEASE_HEALTH_MODE"
TARGET_URL="$WESITE_RELEASE_HEALTH_URL"

CURRENT_NEXT="$APP_BASE/.current.rollback.tmp.$$"
PREVIOUS_NEXT="$APP_BASE/.previous.rollback.tmp.$$"
CURRENT_RESTORE="$APP_BASE/.current.restore.tmp.$$"
PREVIOUS_RESTORE="$APP_BASE/.previous.restore.tmp.$$"
TRANSACTION_ACTIVE=0

cleanup_artifacts() {
  rm -f -- "$CURRENT_NEXT" "$PREVIOUS_NEXT" "$CURRENT_RESTORE" "$PREVIOUS_RESTORE"
}

switch_link() {
  local target="$1"
  local temporary_link="$2"
  local destination_link="$3"

  rm -f -- "$temporary_link"
  ln -s "$target" "$temporary_link"
  mv -Tf "$temporary_link" "$destination_link"
}

wait_for_health() {
  local url="$1"
  local mode="$2"
  local attempt=1

  while (( attempt <= HEALTH_ATTEMPTS )); do
    if wesite_readiness_check "$url" "$mode"; then
      return 0
    fi
    if (( attempt < HEALTH_ATTEMPTS )); then
      sleep "$HEALTH_INTERVAL_SECONDS"
    fi
    attempt=$((attempt + 1))
  done
  return 1
}

restart_and_check_recorded_contract() {
  local release="$1"
  local mode="$2"
  local url="$3"

  wesite_release_directory_is_selected "$release" || return 1
  wesite_load_release_metadata "$release" || return 1
  [[ "$WESITE_RELEASE_STATUS" == successful ]] || return 1
  [[ "$WESITE_RELEASE_HEALTH_MODE" == "$mode" ]] || return 1
  [[ "$WESITE_RELEASE_HEALTH_URL" == "$url" ]] || return 1
  systemctl restart "$WESITE_SELECTED_SERVICE" || return 1
  wait_for_health "$url" "$mode"
}

restore_original_pair() {
  switch_link "$ORIGINAL_CURRENT_TARGET" "$CURRENT_RESTORE" "$CURRENT_LINK" || return 1
  switch_link "$ORIGINAL_PREVIOUS_TARGET" "$PREVIOUS_RESTORE" "$PREVIOUS_LINK"
}

restore_original_current_health() {
  restart_and_check_recorded_contract \
    "$ORIGINAL_CURRENT_TARGET" "$ORIGINAL_CURRENT_MODE" "$ORIGINAL_CURRENT_URL"
}

on_exit() {
  local status=$?
  trap - EXIT HUP INT TERM
  set +e
  if (( status != 0 && TRANSACTION_ACTIVE == 1 )); then
    printf 'Rollback failed or was interrupted; restoring %s.\n' "$WESITE_SELECTED_NAME" >&2
    if ! restore_original_pair; then
      printf 'ERROR: original %s release links could not be restored\n' "$WESITE_SELECTED_NAME" >&2
    fi
    if ! restore_original_current_health; then
      printf 'ERROR: restored %s release did not become healthy\n' "$WESITE_SELECTED_NAME" >&2
    fi
  fi
  cleanup_artifacts
  exit "$status"
}

trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

TRANSACTION_ACTIVE=1
switch_link "$ORIGINAL_PREVIOUS_TARGET" "$CURRENT_NEXT" "$CURRENT_LINK"
restart_and_check_recorded_contract "$ORIGINAL_PREVIOUS_TARGET" "$TARGET_MODE" "$TARGET_URL" \
  || fail "$WESITE_SELECTED_SERVICE did not become ready after rollback"
switch_link "$ORIGINAL_CURRENT_TARGET" "$PREVIOUS_NEXT" "$PREVIOUS_LINK"
TRANSACTION_ACTIVE=0

printf 'Rollback succeeded: app=%s release=%s\n' \
  "$WESITE_SELECTED_NAME" "$ORIGINAL_PREVIOUS_TARGET"
