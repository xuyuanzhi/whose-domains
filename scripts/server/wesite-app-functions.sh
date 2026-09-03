#!/usr/bin/env bash

WESITE_APP_FUNCTIONS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=wesite-health-functions.sh
source "$WESITE_APP_FUNCTIONS_DIR/wesite-health-functions.sh"

wesite_select_app() {
  case "${1:-}" in
    web)
      WESITE_SELECTED_NAME=web
      WESITE_SELECTED_BASE="${WESITE_APPS_BASE_DIR:-/usr/java/apps}/web"
      WESITE_SELECTED_SERVICE="${WESITE_WEB_SERVICE:-wesite-web.service}"
      WESITE_SELECTED_JAR_NAME=wesite-web.jar
      WESITE_SELECTED_START_CLASS=info.wesite.web.App
      WESITE_SELECTED_DEFAULT_HEALTH_URL=http://127.0.0.1:8080/api/readyz
      ;;
    admin)
      WESITE_SELECTED_NAME=admin
      WESITE_SELECTED_BASE="${WESITE_APPS_BASE_DIR:-/usr/java/apps}/admin"
      WESITE_SELECTED_SERVICE="${WESITE_ADMIN_SERVICE:-wesite-admin.service}"
      WESITE_SELECTED_JAR_NAME=wesite-admin.jar
      WESITE_SELECTED_START_CLASS=info.wesite.admin.App
      WESITE_SELECTED_DEFAULT_HEALTH_URL=http://127.0.0.1:8082/api/readyz
      ;;
    *) return 64 ;;
  esac

  export WESITE_SELECTED_NAME WESITE_SELECTED_BASE WESITE_SELECTED_SERVICE
  export WESITE_SELECTED_JAR_NAME WESITE_SELECTED_START_CLASS
  export WESITE_SELECTED_DEFAULT_HEALTH_URL
}

wesite_read_metadata_file() {
  local path="$1"
  local line=''
  local line_count=0

  [[ -f "$path" && ! -L "$path" ]] || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_count=$((line_count + 1))
    (( line_count == 1 )) || return 1
    WESITE_METADATA_VALUE="$line"
  done < "$path"
  (( line_count == 1 ))
}

wesite_release_directory_is_selected() {
  local release_dir="$1"
  local release_root
  local release_real

  release_root="$(realpath -e "$WESITE_SELECTED_BASE/releases")" || return 1
  release_real="$(realpath -e "$release_dir")" || return 1
  [[ -d "$release_real" && "$release_real" == "$release_root"/* ]]
}

wesite_load_release_metadata() {
  local release_dir="$1"
  local release_name
  local app
  local version
  local sha256
  local deployed_at
  local status
  local health_mode
  local health_url
  local selected_port
  local normalized_deployed_at

  [[ -n "${WESITE_SELECTED_NAME:-}" ]] || return 1
  [[ -d "$release_dir" ]] || return 1
  release_name="$(basename "$release_dir")"

  wesite_read_metadata_file "$release_dir/APP" || return 1
  app="$WESITE_METADATA_VALUE"
  wesite_read_metadata_file "$release_dir/VERSION" || return 1
  version="$WESITE_METADATA_VALUE"
  wesite_read_metadata_file "$release_dir/SHA256" || return 1
  sha256="$WESITE_METADATA_VALUE"
  wesite_read_metadata_file "$release_dir/DEPLOYED_AT" || return 1
  deployed_at="$WESITE_METADATA_VALUE"
  wesite_read_metadata_file "$release_dir/STATUS" || return 1
  status="$WESITE_METADATA_VALUE"
  wesite_read_metadata_file "$release_dir/HEALTH_MODE" || return 1
  health_mode="$WESITE_METADATA_VALUE"
  wesite_read_metadata_file "$release_dir/HEALTH_URL" || return 1
  health_url="$WESITE_METADATA_VALUE"

  [[ "$app" == "$WESITE_SELECTED_NAME" ]] || return 1
  [[ "$version" == "$release_name" && "$version" =~ ^[A-Za-z0-9._-]+$ ]] || return 1
  [[ "$sha256" =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ "$deployed_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || return 1
  normalized_deployed_at="$(LC_ALL=C TZ=UTC0 date -u -d "$deployed_at" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)" || return 1
  [[ "$normalized_deployed_at" == "$deployed_at" ]] || return 1
  case "$status" in deploying|successful|failed) ;; *) return 1 ;; esac
  case "$health_mode" in readiness|legacy-http-200) ;; *) return 1 ;; esac

  case "$WESITE_SELECTED_NAME" in
    web) selected_port=8080 ;;
    admin) selected_port=8082 ;;
    *) return 1 ;;
  esac
  [[ "$health_url" =~ ^http://127[.]0[.]0[.]1:${selected_port}/[^[:space:]?#]*$ ]] || return 1

  WESITE_RELEASE_STATUS="$status"
  WESITE_RELEASE_HEALTH_MODE="$health_mode"
  WESITE_RELEASE_HEALTH_URL="$health_url"
  export WESITE_RELEASE_STATUS WESITE_RELEASE_HEALTH_MODE WESITE_RELEASE_HEALTH_URL
}

wesite_check_release_health() {
  local app="$1"
  local release_dir="$2"

  wesite_select_app "$app" || return $?
  wesite_release_directory_is_selected "$release_dir" || return 1
  wesite_load_release_metadata "$release_dir" || return 1
  case "$WESITE_RELEASE_STATUS" in
    deploying)
      [[ "$WESITE_RELEASE_HEALTH_MODE" == readiness ]] || return 1
      [[ "$WESITE_RELEASE_HEALTH_URL" == "$WESITE_SELECTED_DEFAULT_HEALTH_URL" ]] || return 1
      ;;
    successful) ;;
    *) return 1 ;;
  esac

  systemctl is-active --quiet "$WESITE_SELECTED_SERVICE" || return 1
  wesite_readiness_check "$WESITE_RELEASE_HEALTH_URL" "$WESITE_RELEASE_HEALTH_MODE"
}

wesite_check_app() {
  local app="$1"
  local current_link
  local current_target

  wesite_select_app "$app" || return $?
  current_link="$WESITE_SELECTED_BASE/current"
  [[ -L "$current_link" ]] || return 1
  current_target="$(realpath -e "$current_link")" || return 1
  wesite_release_directory_is_selected "$current_target" || return 1
  wesite_load_release_metadata "$current_target" || return 1
  [[ "$WESITE_RELEASE_STATUS" == successful ]] || return 1

  systemctl is-active --quiet "$WESITE_SELECTED_SERVICE" || return 1
  wesite_readiness_check "$WESITE_RELEASE_HEALTH_URL" "$WESITE_RELEASE_HEALTH_MODE"
}
