#!/usr/bin/env bash
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=wesite-app-functions.sh
source "$SCRIPT_DIR/wesite-app-functions.sh"

FAILURES=0
DEPLOYED_APPS=0

check_app() {
  local app="$1"

  wesite_select_app "$app" || return 1
  if [[ ! -e "$WESITE_SELECTED_BASE/current" ]]; then
    printf 'Application %s not deployed.\n' "$app"
    return 0
  fi

  DEPLOYED_APPS=$((DEPLOYED_APPS + 1))
  if wesite_check_app "$app"; then
    printf 'PASS application healthy: %s\n' "$app"
  else
    printf 'FAIL application unhealthy: %s\n' "$app" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

for app in admin web; do
  check_app "$app"
done

if (( DEPLOYED_APPS == 0 )); then
  printf 'Whose.Domains health checks failed: no applications are deployed.\n' >&2
  exit 1
fi

if (( FAILURES > 0 )); then
  printf 'Whose.Domains health checks failed: %d.\n' "$FAILURES" >&2
  exit 1
fi

printf 'All Whose.Domains services are healthy.\n'
