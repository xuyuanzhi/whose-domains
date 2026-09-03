#!/usr/bin/env bash
set -euo pipefail

DEPLOYMENT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
ROOT_PREFIX="${WESITE_ROOT_PREFIX:-}"
APP_USER="wesite"
APP_GROUP="wesite"
DEPLOY_USER="wesite-deploy"
DEPLOY_GROUP="wesite-deploy"
WEB_GROUP="${WESITE_WEB_GROUP:-www-data}"

REQUIRED_PAYLOADS=(
  SOURCE_COMMIT
  deploy/systemd/wesite-web.service
  deploy/systemd/wesite-admin.service
  deploy/systemd/wesite-health-monitor.service
  deploy/systemd/wesite-health-monitor.timer
  deploy/systemd/wesite.env.example
  deploy/config/wesite-web.application-prod.properties.example
  deploy/config/wesite-admin.application-prod.properties.example
  deploy/sudoers/wesite-deploy
  scripts/server/deploy-wesite-app.sh
  scripts/server/rollback-wesite-app.sh
  scripts/server/check-wesite-app.sh
  scripts/server/check-wesite-services.sh
  scripts/server/monitor-wesite-services.sh
  scripts/server/deploy-wesite-release.sh
  scripts/server/wesite-app-functions.sh
  scripts/server/wesite-health-functions.sh
  scripts/server/ensure-wesite-swap.sh
  scripts/server/install-wesite-systemd.sh
)

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

destination() {
  printf '%s%s' "$ROOT_PREFIX" "$1"
}

copy_if_missing() {
  local source="$1"
  local target="$2"
  local mode="$3"
  if [[ ! -e "$target" ]]; then
    install -m "$mode" "$source" "$target"
    printf 'Created %s; review it before starting the services.\n' "$target"
  else
    printf 'Preserved existing %s\n' "$target"
  fi
}

validate_bundle() {
  local source_commit_file="$DEPLOYMENT_ROOT/SOURCE_COMMIT"
  local manifest="$DEPLOYMENT_ROOT/SHA256SUMS"
  local source_commit
  local source_size
  local line
  local hash
  local separator
  local path
  local candidate
  local resolved
  local required
  local line_count=0
  local -a source_lines=()
  local -A manifest_paths=()

  [[ -f "$source_commit_file" && ! -L "$source_commit_file" ]] \
    || fail 'deployment bundle SOURCE_COMMIT is missing or unsafe'
  [[ "$(realpath -e -- "$source_commit_file")" == "$source_commit_file" ]] \
    || fail 'deployment bundle SOURCE_COMMIT is outside the deployment root'
  mapfile -t source_lines < "$source_commit_file"
  [[ "${#source_lines[@]}" -eq 1 ]] \
    || fail 'deployment bundle SOURCE_COMMIT must contain exactly one line'
  source_commit="${source_lines[0]}"
  [[ "$source_commit" =~ ^[0-9a-f]{7,64}$ ]] \
    || fail 'deployment bundle SOURCE_COMMIT is invalid'
  source_size="$(wc -c < "$source_commit_file")"
  [[ "$source_size" -eq $((${#source_commit} + 1)) ]] \
    || fail 'deployment bundle SOURCE_COMMIT must end with one newline'

  [[ -f "$manifest" && ! -L "$manifest" ]] \
    || fail 'deployment bundle SHA256SUMS is missing or unsafe'
  [[ "$(realpath -e -- "$manifest")" == "$manifest" ]] \
    || fail 'deployment bundle SHA256SUMS is outside the deployment root'

  while IFS= read -r line || [[ -n "$line" ]]; do
    line_count=$((line_count + 1))
    [[ "${#line}" -ge 67 ]] \
      || fail "malformed SHA256SUMS line: $line_count"
    hash="${line:0:64}"
    separator="${line:64:2}"
    path="${line:66}"
    [[ "$hash" =~ ^[[:xdigit:]]{64}$ ]] \
      || fail "malformed SHA256SUMS hash: $line_count"
    [[ "$separator" == '  ' || "$separator" == ' *' ]] \
      || fail "malformed SHA256SUMS separator: $line_count"
    [[ -n "$path" && "$path" != /* && "$path" != SHA256SUMS ]] \
      || fail "unsafe SHA256SUMS path: $line_count"
    [[ "$path" =~ ^[A-Za-z0-9._/-]+$ ]] \
      || fail "unsafe SHA256SUMS path: $line_count"
    case "/$path/" in
      *'/../'*|*'/./'*|*'//'*) fail "unsafe SHA256SUMS path: $line_count" ;;
    esac
    [[ -z "${manifest_paths[$path]+present}" ]] \
      || fail "duplicate SHA256SUMS path: $path"

    candidate="$DEPLOYMENT_ROOT/$path"
    [[ -f "$candidate" && ! -L "$candidate" ]] \
      || fail "missing or unsafe bundle payload: $path"
    resolved="$(realpath -e -- "$candidate")" \
      || fail "bundle payload does not resolve: $path"
    [[ "$resolved" == "$candidate" && "$resolved" == "$DEPLOYMENT_ROOT"/* ]] \
      || fail "bundle payload resolves outside its canonical path: $path"
    manifest_paths["$path"]="$hash"
  done < "$manifest"

  (( line_count > 0 )) || fail 'deployment bundle SHA256SUMS is empty'
  for required in "${REQUIRED_PAYLOADS[@]}"; do
    [[ -n "${manifest_paths[$required]+present}" ]] \
      || fail "required bundle payload is not listed: $required"
  done

  (
    cd "$DEPLOYMENT_ROOT"
    sha256sum --check --strict -- SHA256SUMS
  ) || fail 'deployment bundle checksum verification failed'
}

ensure_accounts() {
  getent group "$APP_GROUP" >/dev/null || groupadd --system "$APP_GROUP"
  if id -u "$APP_USER" >/dev/null 2>&1; then
    usermod --gid "$APP_GROUP" --home /nonexistent \
      --shell /usr/sbin/nologin "$APP_USER"
  else
    useradd --system --gid "$APP_GROUP" --home-dir /nonexistent \
      --shell /usr/sbin/nologin "$APP_USER"
  fi

  getent group "$DEPLOY_GROUP" >/dev/null || groupadd --system "$DEPLOY_GROUP"
  if id -u "$DEPLOY_USER" >/dev/null 2>&1; then
    usermod --gid "$DEPLOY_GROUP" --home /var/lib/wesite-deploy \
      --shell /bin/bash "$DEPLOY_USER"
  else
    useradd --system --gid "$DEPLOY_GROUP" \
      --home-dir /var/lib/wesite-deploy --shell /bin/bash "$DEPLOY_USER"
  fi

  getent group "$WEB_GROUP" >/dev/null \
    || fail "Web-server group does not exist: $WEB_GROUP"
  usermod -a -G "$WEB_GROUP" "$APP_USER"
}

validate_bundle

if [[ -z "$ROOT_PREFIX" && "$(id -u)" -ne 0 ]]; then
  fail 'Run this installer as root'
fi

ensure_accounts

install -d -m 0750 "$(destination /etc/wesite)"
install -d -m 0755 "$(destination /etc/systemd/system)"
install -d -m 0755 "$(destination /usr/local/sbin)"
install -d -m 0750 "$(destination /usr/java/apps)"
install -d -m 0750 "$(destination /usr/java/apps/web)"
install -d -m 0750 "$(destination /usr/java/apps/web/releases)"
install -d -m 0750 "$(destination /usr/java/apps/admin)"
install -d -m 0750 "$(destination /usr/java/apps/admin/releases)"
install -d -m 0750 "$(destination /usr/java/config/web)"
install -d -m 0750 "$(destination /usr/java/config/admin)"
install -d -m 0750 "$(destination /usr/java/logs)"
install -d -m 0750 "$(destination /usr/java/data)"
install -d -m 2775 "$(destination /var/www/sitemap)"
install -d -m 0750 "$(destination /var/lib/wesite-deploy)"
install -d -m 0750 "$(destination /var/lib/wesite-deploy/incoming)"

install -m 0644 "$DEPLOYMENT_ROOT/deploy/systemd/wesite-web.service" \
  "$(destination /etc/systemd/system/wesite-web.service)"
install -m 0644 "$DEPLOYMENT_ROOT/deploy/systemd/wesite-admin.service" \
  "$(destination /etc/systemd/system/wesite-admin.service)"
install -m 0644 "$DEPLOYMENT_ROOT/deploy/systemd/wesite-health-monitor.service" \
  "$(destination /etc/systemd/system/wesite-health-monitor.service)"
install -m 0644 "$DEPLOYMENT_ROOT/deploy/systemd/wesite-health-monitor.timer" \
  "$(destination /etc/systemd/system/wesite-health-monitor.timer)"

install -m 0755 "$DEPLOYMENT_ROOT/scripts/server/deploy-wesite-app.sh" \
  "$(destination /usr/local/sbin/deploy-wesite-app)"
install -m 0755 "$DEPLOYMENT_ROOT/scripts/server/rollback-wesite-app.sh" \
  "$(destination /usr/local/sbin/rollback-wesite-app)"
install -m 0755 "$DEPLOYMENT_ROOT/scripts/server/check-wesite-app.sh" \
  "$(destination /usr/local/sbin/check-wesite-app)"
install -m 0755 "$DEPLOYMENT_ROOT/scripts/server/check-wesite-services.sh" \
  "$(destination /usr/local/sbin/check-wesite-services)"
install -m 0755 "$DEPLOYMENT_ROOT/scripts/server/monitor-wesite-services.sh" \
  "$(destination /usr/local/sbin/monitor-wesite-services)"
install -m 0755 "$DEPLOYMENT_ROOT/scripts/server/deploy-wesite-release.sh" \
  "$(destination /usr/local/sbin/deploy-wesite-release)"
install -m 0644 "$DEPLOYMENT_ROOT/scripts/server/wesite-app-functions.sh" \
  "$(destination /usr/local/sbin/wesite-app-functions.sh)"
install -m 0644 "$DEPLOYMENT_ROOT/scripts/server/wesite-health-functions.sh" \
  "$(destination /usr/local/sbin/wesite-health-functions.sh)"
install -m 0755 "$DEPLOYMENT_ROOT/scripts/server/ensure-wesite-swap.sh" \
  "$(destination /usr/local/sbin/ensure-wesite-swap)"

install -m 0600 "$DEPLOYMENT_ROOT/deploy/systemd/wesite.env.example" \
  "$(destination /etc/wesite/wesite.env.example)"
install -m 0640 \
  "$DEPLOYMENT_ROOT/deploy/config/wesite-web.application-prod.properties.example" \
  "$(destination /usr/java/config/web/application-prod.properties.example)"
install -m 0640 \
  "$DEPLOYMENT_ROOT/deploy/config/wesite-admin.application-prod.properties.example" \
  "$(destination /usr/java/config/admin/application-prod.properties.example)"
install -m 0640 "$DEPLOYMENT_ROOT/deploy/sudoers/wesite-deploy" \
  "$(destination /etc/wesite/wesite-deploy.sudoers.example)"

copy_if_missing "$DEPLOYMENT_ROOT/deploy/systemd/wesite.env.example" \
  "$(destination /etc/wesite/wesite.env)" 0600
copy_if_missing \
  "$DEPLOYMENT_ROOT/deploy/config/wesite-web.application-prod.properties.example" \
  "$(destination /usr/java/config/web/application-prod.properties)" 0640
copy_if_missing \
  "$DEPLOYMENT_ROOT/deploy/config/wesite-admin.application-prod.properties.example" \
  "$(destination /usr/java/config/admin/application-prod.properties)" 0640

chmod 0600 "$(destination /etc/wesite/wesite.env)"
chmod 0640 "$(destination /usr/java/config/web/application-prod.properties)" \
  "$(destination /usr/java/config/admin/application-prod.properties)"
chmod 0750 "$(destination /usr/java/apps/web/releases)" \
  "$(destination /usr/java/apps/admin/releases)" \
  "$(destination /var/lib/wesite-deploy)" \
  "$(destination /var/lib/wesite-deploy/incoming)"

chown root:"$APP_GROUP" "$(destination /etc/wesite)"
chown root:"$APP_GROUP" "$(destination /etc/wesite/wesite.env)" \
  "$(destination /etc/wesite/wesite.env.example)"
chown root:root "$(destination /etc/wesite/wesite-deploy.sudoers.example)"
chown root:"$APP_GROUP" "$(destination /usr/java/config)" \
  "$(destination /usr/java/config/web)" \
  "$(destination /usr/java/config/admin)" \
  "$(destination /usr/java/data)"
chown root:"$APP_GROUP" \
  "$(destination /usr/java/config/web/application-prod.properties)" \
  "$(destination /usr/java/config/web/application-prod.properties.example)" \
  "$(destination /usr/java/config/admin/application-prod.properties)" \
  "$(destination /usr/java/config/admin/application-prod.properties.example)"
chown root:"$APP_GROUP" "$(destination /usr/java/apps)" \
  "$(destination /usr/java/apps/web)" "$(destination /usr/java/apps/admin)"
chown root:"$APP_GROUP" "$(destination /usr/java/apps/web/releases)"
chown root:"$APP_GROUP" "$(destination /usr/java/apps/admin/releases)"
chown "$APP_USER":"$APP_GROUP" "$(destination /usr/java/logs)"
chown "$APP_USER":"$WEB_GROUP" "$(destination /var/www/sitemap)"
chown "$DEPLOY_USER":"$DEPLOY_GROUP" "$(destination /var/lib/wesite-deploy)"
chown "$DEPLOY_USER":"$DEPLOY_GROUP" \
  "$(destination /var/lib/wesite-deploy/incoming)"
chmod 2775 "$(destination /var/www/sitemap)"

systemctl daemon-reload

printf '%s\n' \
  'Systemd files installed but not enabled. Configure the three live files; Jenkins job directories below incoming must use mode 0700.'
