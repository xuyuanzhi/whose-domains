#!/usr/bin/env bash
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=wesite-health-functions.sh
source "$SCRIPT_DIR/wesite-health-functions.sh"

ADMIN_SERVICE="${WESITE_ADMIN_SERVICE:-wesite-admin.service}"
WEB_SERVICE="${WESITE_WEB_SERVICE:-wesite-web.service}"
ADMIN_HEALTH_URL="${WESITE_ADMIN_HEALTH_URL:-http://127.0.0.1:8082/api/readyz}"
WEB_HEALTH_URL="${WESITE_WEB_HEALTH_URL:-http://127.0.0.1:8080/api/readyz}"
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

is_healthy() {
  local service="$1"
  local url="$2"
  systemctl is-active --quiet "$service" \
    && wesite_readiness_check "$url"
}

check_service() {
  local name="$1"
  local service="$2"
  local url="$3"
  local state_file="$STATE_DIR/${name}.failures"
  local count

  if is_healthy "$service" "$url"; then
    rm -f "$state_file"
    printf 'Health monitor passed: %s %s\n' "$service" "$url"
    return 0
  fi

  count="$(read_failure_count "$state_file")"
  count=$((count + 1))
  printf 'Health monitor failure %d/%d: %s %s\n' \
    "$count" "$FAILURE_THRESHOLD" "$service" "$url" >&2

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
check_service admin "$ADMIN_SERVICE" "$ADMIN_HEALTH_URL" || FAILURES=$((FAILURES + 1))
check_service web "$WEB_SERVICE" "$WEB_HEALTH_URL" || FAILURES=$((FAILURES + 1))

(( FAILURES == 0 )) || exit 1
