#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=wesite-app-functions.sh
source "$SCRIPT_DIR/wesite-app-functions.sh"

usage() {
  printf 'Usage: deploy-wesite-app.sh {web|admin} VERSION JAR\n' >&2
  exit 64
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ $# -eq 3 ]] || usage

APP="$1"
VERSION="$2"
INPUT_JAR="$3"
wesite_select_app "$APP" || usage

case "$VERSION" in
  ''|*[!A-Za-z0-9._-]*) fail 'VERSION may contain only letters, numbers, dot, underscore, and hyphen' ;;
esac

HEALTH_MODE="${WESITE_HEALTH_RESPONSE_MODE:-readiness}"
HEALTH_URL="${WESITE_APP_HEALTH_URL:-$WESITE_SELECTED_DEFAULT_HEALTH_URL}"
HEALTH_ATTEMPTS="${WESITE_HEALTH_ATTEMPTS:-30}"
HEALTH_INTERVAL_SECONDS="${WESITE_HEALTH_INTERVAL_SECONDS:-2}"
LOCK_FILE="${WESITE_DEPLOY_LOCK_FILE:-/run/lock/wesite/wesite-deploy.lock}"
APP_BASE="$WESITE_SELECTED_BASE"
RELEASES_DIR="$APP_BASE/releases"
CURRENT_LINK="$APP_BASE/current"
PREVIOUS_LINK="$APP_BASE/previous"
RELEASE_DIR="$RELEASES_DIR/$VERSION"

case "$HEALTH_MODE" in
  readiness|legacy-http-200) ;;
  *) fail 'invalid health response mode' ;;
esac
case "$WESITE_SELECTED_NAME:$HEALTH_URL" in
  web:http://127.0.0.1:8080/*|admin:http://127.0.0.1:8082/*) ;;
  *) fail 'invalid application health URL' ;;
esac
[[ "$HEALTH_URL" != *[[:space:]?#]* ]] || fail 'invalid application health URL'
[[ "$HEALTH_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || fail 'WESITE_HEALTH_ATTEMPTS must be a positive integer'
[[ "$HEALTH_INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || fail 'WESITE_HEALTH_INTERVAL_SECONDS must be a non-negative integer'
[[ "$LOCK_FILE" == /* ]] || fail 'WESITE_DEPLOY_LOCK_FILE must be an absolute path'
command -v flock >/dev/null 2>&1 || fail 'flock is required'
command -v unzip >/dev/null 2>&1 || fail 'unzip is required'

if [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != root ]] \
    && [[ "$HEALTH_MODE" != readiness || "$HEALTH_URL" != "$WESITE_SELECTED_DEFAULT_HEALTH_URL" ]]; then
  fail 'legacy health overrides require direct root invocation'
fi
if [[ "$HEALTH_MODE" != readiness || "$HEALTH_URL" != "$WESITE_SELECTED_DEFAULT_HEALTH_URL" ]]; then
  (( EUID == 0 )) || fail 'legacy health overrides require direct root invocation'
fi

[[ -f "$INPUT_JAR" && ! -L "$INPUT_JAR" && -r "$INPUT_JAR" ]] || fail 'invalid staged JAR'
INCOMING_ROOT="$(realpath -e "${WESITE_INCOMING_DIR:-/var/lib/wesite-deploy/incoming}")"
INPUT_REAL="$(realpath -e "$INPUT_JAR")"
[[ "$INPUT_REAL" == "$INCOMING_ROOT"/* ]] || fail 'staged JAR is outside incoming root'
INPUT_LSTAT="$(LC_ALL=C stat -c '%F|%d:%i' -- "$INPUT_REAL")" \
  || fail 'invalid staged JAR'
case "$INPUT_LSTAT" in
  'regular file|'*) ;;
  *) fail 'invalid staged JAR' ;;
esac
exec {INPUT_FD}< "$INPUT_REAL" || fail 'cannot open staged JAR'
INPUT_FSTAT="$(LC_ALL=C stat -Lc '%F|%d:%i' -- "/proc/self/fd/$INPUT_FD")" \
  || fail 'cannot inspect opened staged JAR'
[[ "$INPUT_FSTAT" == "$INPUT_LSTAT" ]] || fail 'staged JAR changed while opening'
INPUT_FD_REAL="$(realpath -e "/proc/self/fd/$INPUT_FD")" \
  || fail 'opened staged JAR no longer resolves'
[[ "$INPUT_FD_REAL" == "$INCOMING_ROOT"/* ]] \
  || fail 'opened staged JAR is outside incoming root'

wesite_open_deployment_lock "$LOCK_FILE" \
  || fail 'deployment lock path is unsafe or unavailable'
flock -n 9 || fail 'Another Whose.Domains deployment or health recovery is already running'

install -d -m 0755 "$RELEASES_DIR"
[[ ! -e "$RELEASE_DIR" && ! -L "$RELEASE_DIR" ]] \
  || fail "Release already exists and will not be overwritten: $RELEASE_DIR"

OLD_TARGET=''
if [[ -L "$CURRENT_LINK" ]]; then
  OLD_TARGET="$(realpath -e "$CURRENT_LINK")" || fail 'current release link is broken'
  wesite_release_directory_is_selected "$OLD_TARGET" || fail 'current release is outside the selected application'
  wesite_load_release_metadata "$OLD_TARGET" || fail 'current release metadata is invalid'
  [[ "$WESITE_RELEASE_STATUS" == successful ]] || fail 'current release is not successful'
elif [[ -e "$CURRENT_LINK" ]]; then
  fail "$CURRENT_LINK exists but is not a symbolic link"
fi

PREVIOUS_ORIGINAL_STATE=missing
PREVIOUS_ORIGINAL_TARGET=''
if [[ -L "$PREVIOUS_LINK" ]]; then
  PREVIOUS_ORIGINAL_TARGET="$(realpath -e "$PREVIOUS_LINK")" \
    || fail 'previous release link is broken'
  wesite_release_directory_is_selected "$PREVIOUS_ORIGINAL_TARGET" \
    || fail 'previous release is outside the selected application'
  wesite_load_release_metadata "$PREVIOUS_ORIGINAL_TARGET" \
    || fail 'previous release metadata is invalid'
  [[ "$WESITE_RELEASE_STATUS" == successful ]] || fail 'previous release is not successful'
  PREVIOUS_ORIGINAL_STATE=target
elif [[ -e "$PREVIOUS_LINK" ]]; then
  fail "$PREVIOUS_LINK exists but is not a symbolic link"
fi

TEMP_RELEASE="$RELEASES_DIR/.${VERSION}.tmp.$$"
NEXT_LINK="$APP_BASE/.current.${VERSION}.tmp.$$"
PREVIOUS_NEXT="$APP_BASE/.previous.${VERSION}.tmp.$$"
PREVIOUS_RESTORE="$APP_BASE/.previous.rollback.${VERSION}.tmp.$$"
CURRENT_SWITCHED=0

cleanup_artifacts() {
  if [[ -n "$TEMP_RELEASE" ]]; then
    rm -rf -- "$TEMP_RELEASE"
  fi
  rm -f -- "$NEXT_LINK" "$PREVIOUS_NEXT" "$PREVIOUS_RESTORE"
}

switch_current() {
  local target="$1"
  rm -f -- "$NEXT_LINK"
  ln -s "$target" "$NEXT_LINK"
  mv -Tf "$NEXT_LINK" "$CURRENT_LINK"
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

restart_and_check_release() {
  local release="$1"
  wesite_load_release_metadata "$release" || return 1
  systemctl restart "$WESITE_SELECTED_SERVICE" || return 1
  wait_for_health "$WESITE_RELEASE_HEALTH_URL" "$WESITE_RELEASE_HEALTH_MODE"
}

restore_previous_state() {
  case "$PREVIOUS_ORIGINAL_STATE" in
    target)
      rm -f -- "$PREVIOUS_RESTORE"
      ln -s "$PREVIOUS_ORIGINAL_TARGET" "$PREVIOUS_RESTORE"
      mv -Tf "$PREVIOUS_RESTORE" "$PREVIOUS_LINK"
      ;;
    missing)
      rm -f -- "$PREVIOUS_LINK"
      ;;
    *) return 1 ;;
  esac
}

rollback_selected_app() {
  printf 'Deployment failed; rolling back %s.\n' "$WESITE_SELECTED_NAME" >&2
  printf '%s\n' failed > "$RELEASE_DIR/STATUS"
  if [[ -n "$OLD_TARGET" && -d "$OLD_TARGET" ]]; then
    if switch_current "$OLD_TARGET"; then
      CURRENT_SWITCHED=0
      restore_previous_state \
        || printf 'ERROR: previous release link could not be restored\n' >&2
      if ! wesite_load_release_metadata "$OLD_TARGET" \
          || [[ "$WESITE_RELEASE_STATUS" != successful ]] \
          || ! systemctl restart "$WESITE_SELECTED_SERVICE" \
          || ! wait_for_health "$WESITE_RELEASE_HEALTH_URL" "$WESITE_RELEASE_HEALTH_MODE"; then
        printf 'ERROR: restored %s release did not become healthy\n' "$WESITE_SELECTED_NAME" >&2
      fi
    else
      printf 'ERROR: rollback link switch failed; stopping %s\n' "$WESITE_SELECTED_NAME" >&2
      restore_previous_state \
        || printf 'ERROR: previous release link could not be restored\n' >&2
      systemctl stop "$WESITE_SELECTED_SERVICE" || true
    fi
  else
    rm -f -- "$CURRENT_LINK"
    CURRENT_SWITCHED=0
    restore_previous_state \
      || printf 'ERROR: previous release link could not be restored\n' >&2
    systemctl stop "$WESITE_SELECTED_SERVICE" || true
  fi
}

on_exit() {
  local status=$?
  trap - EXIT HUP INT TERM
  set +e
  if (( status != 0 && CURRENT_SWITCHED == 1 )); then
    rollback_selected_app
  fi
  cleanup_artifacts
  exit "$status"
}

trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

install -d -m 0755 "$TEMP_RELEASE"
install -m 0444 "/proc/self/fd/$INPUT_FD" "$TEMP_RELEASE/$WESITE_SELECTED_JAR_NAME"
exec {INPUT_FD}<&-
PRIVATE_JAR="$TEMP_RELEASE/$WESITE_SELECTED_JAR_NAME"
[[ -f "$PRIVATE_JAR" && ! -L "$PRIVATE_JAR" ]] || fail 'invalid private JAR copy'
unzip -tqq "$PRIVATE_JAR" || fail 'invalid JAR archive'
MANIFEST="$(unzip -p "$PRIVATE_JAR" META-INF/MANIFEST.MF | tr -d '\r')" \
  || fail 'JAR manifest is missing'
START_CLASS=''
START_CLASS_COUNT=0
while IFS= read -r manifest_line || [[ -n "$manifest_line" ]]; do
  case "$manifest_line" in
    'Start-Class: '*)
      START_CLASS_COUNT=$((START_CLASS_COUNT + 1))
      START_CLASS="${manifest_line#Start-Class: }"
      ;;
  esac
done <<< "$MANIFEST"
[[ "$START_CLASS_COUNT" -eq 1 && "$START_CLASS" == "$WESITE_SELECTED_START_CLASS" ]] \
  || fail 'JAR Start-Class does not match selected application'

SHA256="$(sha256sum "$PRIVATE_JAR" | awk '{print $1}')"
DEPLOYED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '%s\n' "$WESITE_SELECTED_NAME" > "$TEMP_RELEASE/APP"
printf '%s\n' "$VERSION" > "$TEMP_RELEASE/VERSION"
printf '%s\n' "$SHA256" > "$TEMP_RELEASE/SHA256"
printf '%s\n' "$DEPLOYED_AT" > "$TEMP_RELEASE/DEPLOYED_AT"
printf '%s\n' deploying > "$TEMP_RELEASE/STATUS"
printf '%s\n' "$HEALTH_MODE" > "$TEMP_RELEASE/HEALTH_MODE"
printf '%s\n' "$HEALTH_URL" > "$TEMP_RELEASE/HEALTH_URL"

mv -- "$TEMP_RELEASE" "$RELEASE_DIR"
TEMP_RELEASE=''
wesite_load_release_metadata "$RELEASE_DIR" || fail 'candidate release metadata is invalid'

CURRENT_SWITCHED=1
switch_current "$RELEASE_DIR"
restart_and_check_release "$RELEASE_DIR" \
  || fail "$WESITE_SELECTED_SERVICE did not become ready"

printf '%s\n' successful > "$RELEASE_DIR/STATUS"
if [[ -n "$OLD_TARGET" ]]; then
  ln -s "$OLD_TARGET" "$PREVIOUS_NEXT"
  mv -Tf "$PREVIOUS_NEXT" "$PREVIOUS_LINK"
fi
CURRENT_SWITCHED=0
printf 'Deployment succeeded: app=%s version=%s release=%s\n' \
  "$WESITE_SELECTED_NAME" "$VERSION" "$RELEASE_DIR"
