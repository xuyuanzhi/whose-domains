#!/usr/bin/env bash
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=wesite-health-functions.sh
source "$SCRIPT_DIR/wesite-health-functions.sh"

ADMIN_SERVICE="${WESITE_ADMIN_SERVICE:-wesite-admin.service}"
WEB_SERVICE="${WESITE_WEB_SERVICE:-wesite-web.service}"
ADMIN_HEALTH_URL="${WESITE_ADMIN_HEALTH_URL:-http://127.0.0.1:8082/api/readyz}"
WEB_HEALTH_URL="${WESITE_WEB_HEALTH_URL:-http://127.0.0.1:8080/api/readyz}"
FAILURES=0

check_service() {
  local service="$1"
  if systemctl is-active --quiet "$service"; then
    printf 'PASS service active: %s\n' "$service"
  else
    printf 'FAIL service inactive: %s\n' "$service" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

check_url() {
  local name="$1"
  local url="$2"
  if wesite_readiness_check "$url"; then
    printf 'PASS endpoint: %s %s\n' "$name" "$url"
  else
    printf 'FAIL endpoint: %s %s\n' "$name" "$url" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

check_service "$ADMIN_SERVICE"
check_service "$WEB_SERVICE"
check_url admin "$ADMIN_HEALTH_URL"
check_url web "$WEB_HEALTH_URL"

if (( FAILURES > 0 )); then
  printf 'Whose.Domains health checks failed: %d.\n' "$FAILURES" >&2
  exit 1
fi

printf 'All Whose.Domains services are healthy.\n'
