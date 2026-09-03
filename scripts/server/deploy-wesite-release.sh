#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=wesite-health-functions.sh
source "$SCRIPT_DIR/wesite-health-functions.sh"

usage() {
  cat >&2 <<'EOF'
Usage: deploy-wesite-release.sh VERSION WEB_JAR ADMIN_JAR

Installs one immutable release, switches /usr/java/current atomically,
restarts admin then web, and rolls back the symlink when a health check fails.
EOF
  exit 64
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ $# -eq 3 ]] || usage

VERSION="$1"
WEB_JAR="$2"
ADMIN_JAR="$3"
BASE_DIR="${WESITE_BASE_DIR:-/usr/java}"
RELEASES_DIR="$BASE_DIR/releases"
CURRENT_LINK="$BASE_DIR/current"
PREVIOUS_LINK="$BASE_DIR/previous"
ADMIN_SERVICE="${WESITE_ADMIN_SERVICE:-wesite-admin.service}"
WEB_SERVICE="${WESITE_WEB_SERVICE:-wesite-web.service}"
ADMIN_HEALTH_URL="${WESITE_ADMIN_HEALTH_URL:-http://127.0.0.1:8082/api/readyz}"
WEB_HEALTH_URL="${WESITE_WEB_HEALTH_URL:-http://127.0.0.1:8080/api/readyz}"
HEALTH_ATTEMPTS="${WESITE_HEALTH_ATTEMPTS:-30}"
HEALTH_INTERVAL_SECONDS="${WESITE_HEALTH_INTERVAL_SECONDS:-2}"
LOCK_FILE="${WESITE_DEPLOY_LOCK_FILE:-/run/lock/wesite-deploy.lock}"

case "$VERSION" in
  ''|*[!A-Za-z0-9._-]*) fail "VERSION may contain only letters, numbers, dot, underscore, and hyphen" ;;
esac
[[ -f "$WEB_JAR" ]] || fail "Web JAR not found: $WEB_JAR"
[[ -f "$ADMIN_JAR" ]] || fail "Admin JAR not found: $ADMIN_JAR"
[[ "$HEALTH_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || fail "WESITE_HEALTH_ATTEMPTS must be a positive integer"
[[ "$HEALTH_INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || fail "WESITE_HEALTH_INTERVAL_SECONDS must be a non-negative integer"
[[ "$LOCK_FILE" == /* ]] || fail "WESITE_DEPLOY_LOCK_FILE must be an absolute path"
command -v flock >/dev/null 2>&1 || fail "flock is required"

mkdir -p "$BASE_DIR" "$RELEASES_DIR"
exec 9> "$LOCK_FILE"
flock -n 9 || fail "Another Whose.Domains deployment or health recovery is already running"

RELEASE_DIR="$RELEASES_DIR/$VERSION"
[[ ! -e "$RELEASE_DIR" ]] || fail "Release already exists and will not be overwritten: $RELEASE_DIR"

OLD_TARGET=''
if [[ -L "$CURRENT_LINK" ]]; then
  OLD_TARGET="$(readlink -f "$CURRENT_LINK")"
elif [[ -e "$CURRENT_LINK" ]]; then
  fail "$CURRENT_LINK exists but is not a symbolic link"
fi

TEMP_RELEASE="$RELEASES_DIR/.${VERSION}.tmp.$$"
NEXT_LINK="$BASE_DIR/.current.${VERSION}.tmp.$$"
PREVIOUS_NEXT=''
CURRENT_SWITCHED=0

cleanup_artifacts() {
  rm -rf "$TEMP_RELEASE"
  rm -f "$NEXT_LINK"
  if [[ -n "$PREVIOUS_NEXT" ]]; then
    rm -f "$PREVIOUS_NEXT"
  fi
}

switch_current() {
  local target="$1"
  ln -s "$target" "$NEXT_LINK"
  mv -Tf "$NEXT_LINK" "$CURRENT_LINK"
}

wait_for_health() {
  local service="$1"
  local url="$2"
  local attempt=1
  while (( attempt <= HEALTH_ATTEMPTS )); do
    if wesite_readiness_check "$url"; then
      printf 'Health check passed: %s %s\n' "$service" "$url"
      return 0
    fi
    if (( attempt < HEALTH_ATTEMPTS )); then
      sleep "$HEALTH_INTERVAL_SECONDS"
    fi
    ((attempt += 1))
  done
  printf 'Health check failed: %s %s\n' "$service" "$url" >&2
  return 1
}

restart_and_check() {
  local service="$1"
  local url="$2"
  systemctl restart "$service" && wait_for_health "$service" "$url"
}

restore_previous_release() {
  local reason="$1"
  printf 'Deployment failed: %s\n' "$reason" >&2
  if [[ -n "$OLD_TARGET" && -d "$OLD_TARGET" ]]; then
    printf 'Rolling back current release to %s\n' "$OLD_TARGET" >&2
    if switch_current "$OLD_TARGET"; then
      CURRENT_SWITCHED=0
      restart_and_check "$ADMIN_SERVICE" "$ADMIN_HEALTH_URL" || true
      restart_and_check "$WEB_SERVICE" "$WEB_HEALTH_URL" || true
    else
      printf 'Rollback link switch failed; stopping both application services.\n' >&2
      systemctl stop "$WEB_SERVICE" || true
      systemctl stop "$ADMIN_SERVICE" || true
    fi
  else
    printf 'No previous release is available; stopping both application services.\n' >&2
    rm -f "$CURRENT_LINK"
    CURRENT_SWITCHED=0
    systemctl stop "$WEB_SERVICE" || true
    systemctl stop "$ADMIN_SERVICE" || true
  fi
}

on_exit() {
  local status=$?
  trap - EXIT HUP INT TERM
  set +e
  if (( status != 0 && CURRENT_SWITCHED == 1 )); then
    restore_previous_release "deployment command exited with status $status"
  fi
  cleanup_artifacts
  exit "$status"
}

trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

install -d -m 0755 "$TEMP_RELEASE"
install -m 0444 "$WEB_JAR" "$TEMP_RELEASE/wesite-web.jar"
install -m 0444 "$ADMIN_JAR" "$TEMP_RELEASE/wesite-admin.jar"
printf '%s\n' "$VERSION" > "$TEMP_RELEASE/VERSION"
mv "$TEMP_RELEASE" "$RELEASE_DIR"

CURRENT_SWITCHED=1
switch_current "$RELEASE_DIR"
if ! restart_and_check "$ADMIN_SERVICE" "$ADMIN_HEALTH_URL"; then
  fail "$ADMIN_SERVICE did not become ready"
fi
if ! restart_and_check "$WEB_SERVICE" "$WEB_HEALTH_URL"; then
  fail "$WEB_SERVICE did not become ready"
fi

if [[ -n "$OLD_TARGET" && -d "$OLD_TARGET" ]]; then
  PREVIOUS_NEXT="$BASE_DIR/.previous.${VERSION}.tmp.$$"
  ln -s "$OLD_TARGET" "$PREVIOUS_NEXT"
  mv -Tf "$PREVIOUS_NEXT" "$PREVIOUS_LINK"
fi

CURRENT_SWITCHED=0
printf 'Deployment succeeded: version=%s release=%s\n' "$VERSION" "$RELEASE_DIR"
