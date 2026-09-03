#!/usr/bin/env bash
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=wesite-app-functions.sh
source "$SCRIPT_DIR/wesite-app-functions.sh"

usage() {
  printf 'Usage: check-wesite-app.sh {web|admin}\n' >&2
  exit 64
}

[[ $# -eq 1 ]] || usage

if wesite_check_app "$1"; then
  printf 'Application healthy: %s\n' "$1"
  exit 0
fi

if ! wesite_select_app "$1"; then
  usage
fi

printf 'Application unhealthy: %s\n' "$WESITE_SELECTED_NAME" >&2
exit 1
