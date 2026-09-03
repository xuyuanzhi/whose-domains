#!/usr/bin/env bash
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=wesite-app-functions.sh
source "$SCRIPT_DIR/wesite-app-functions.sh"

FAILURE_THRESHOLD="${WESITE_HEALTH_FAILURE_THRESHOLD:-3}"
STATE_DIR="${WESITE_HEALTH_STATE_DIR:-/run/wesite-health}"
LOCK_FILE="${WESITE_DEPLOY_LOCK_FILE:-/run/lock/wesite-deploy.lock}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ "$FAILURE_THRESHOLD" =~ ^[1-9][0-9]*$ ]] \
  || fail "WESITE_HEALTH_FAILURE_THRESHOLD must be a positive integer"
[[ "$STATE_DIR" == /* ]] || fail "WESITE_HEALTH_STATE_DIR must be an absolute path"
[[ "$LOCK_FILE" == /* ]] || fail "WESITE_DEPLOY_LOCK_FILE must be an absolute path"
command -v flock >/dev/null 2>&1 || fail "flock is required"

install -d -m 0750 "$STATE_DIR"
exec 9> "$LOCK_FILE"
if ! flock -n 9; then
  printf 'Health recovery skipped: deployment lock is held.\n'
  exit 0
fi

read_failure_count() {
  local state_file="$1"
  local count=0
  if [[ -f "$state_file" ]]; then
    count="$(<"$state_file")"
  fi
  if [[ ! "$count" =~ ^[0-9]+$ ]]; then
    count=0
  fi
  printf '%s' "$count"
}

write_failure_count() {
  local state_file="$1"
  local count="$2"
  local temporary_file="${state_file}.tmp.$$"
  printf '%s\n' "$count" > "$temporary_file"
  mv -f "$temporary_file" "$state_file"
}

check_app() {
  local app="$1"
  local service
  local state_file
  local count

  wesite_select_app "$app" || return 1
  if [[ ! -e "$WESITE_SELECTED_BASE/current" ]]; then
    printf 'Health monitor skipped: %s not deployed.\n' "$app"
    return 0
  fi

  service="$WESITE_SELECTED_SERVICE"
  state_file="$STATE_DIR/${app}.failures"
  if wesite_check_app "$app"; then
    rm -f "$state_file"
    printf 'Health monitor passed: %s %s\n' "$app" "$service"
    return 0
  fi

  count="$(read_failure_count "$state_file")"
  count=$((count + 1))
  printf 'Health monitor failure %d/%d: %s %s\n' \
    "$count" "$FAILURE_THRESHOLD" "$app" "$service" >&2

  if (( count < FAILURE_THRESHOLD )); then
    write_failure_count "$state_file" "$count"
    return 0
  fi

  printf 'Restarting %s after %d consecutive readiness failures.\n' \
    "$service" "$count" >&2
  if systemctl restart "$service"; then
    rm -f "$state_file"
    return 0
  fi

  write_failure_count "$state_file" "$count"
  printf 'Failed to restart %s.\n' "$service" >&2
  return 1
}

FAILURES=0
for app in admin web; do
  check_app "$app" || FAILURES=$((FAILURES + 1))
done

(( FAILURES == 0 )) || exit 1
