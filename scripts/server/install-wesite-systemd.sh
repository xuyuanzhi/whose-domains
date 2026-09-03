#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
ROOT_PREFIX="${WESITE_ROOT_PREFIX:-}"
APP_USER="wesite"
APP_GROUP="wesite"
WEB_GROUP="${WESITE_WEB_GROUP:-www-data}"

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

if [[ -z "$ROOT_PREFIX" && "$(id -u)" -ne 0 ]]; then
  fail 'Run this installer as root'
fi

if [[ "${WESITE_SKIP_SYSTEM_USER:-0}" != 1 ]]; then
  getent group "$APP_GROUP" >/dev/null || groupadd --system "$APP_GROUP"
  id -u "$APP_USER" >/dev/null 2>&1 || useradd --system --gid "$APP_GROUP" --home-dir /nonexistent --shell /usr/sbin/nologin "$APP_USER"
  getent group "$WEB_GROUP" >/dev/null || fail "Web-server group does not exist: $WEB_GROUP"
  usermod -a -G "$WEB_GROUP" "$APP_USER"
fi

install -d -m 0750 "$(destination /etc/wesite)"
install -d -m 0755 "$(destination /etc/systemd/system)"
install -d -m 0755 "$(destination /usr/local/sbin)"
install -d -m 0750 "$(destination /usr/java/releases)"
install -d -m 0750 "$(destination /usr/java/config/web)"
install -d -m 0750 "$(destination /usr/java/config/admin)"
install -d -m 0750 "$(destination /usr/java/logs)"
install -d -m 0750 "$(destination /usr/java/data)"
install -d -m 2775 "$(destination /var/www/sitemap)"

install -m 0644 "$REPOSITORY_ROOT/deploy/systemd/wesite-web.service" "$(destination /etc/systemd/system/wesite-web.service)"
install -m 0644 "$REPOSITORY_ROOT/deploy/systemd/wesite-admin.service" "$(destination /etc/systemd/system/wesite-admin.service)"
install -m 0644 "$REPOSITORY_ROOT/deploy/systemd/wesite-health-monitor.service" "$(destination /etc/systemd/system/wesite-health-monitor.service)"
install -m 0644 "$REPOSITORY_ROOT/deploy/systemd/wesite-health-monitor.timer" "$(destination /etc/systemd/system/wesite-health-monitor.timer)"
install -m 0755 "$REPOSITORY_ROOT/scripts/server/deploy-wesite-release.sh" "$(destination /usr/local/sbin/deploy-wesite-release)"
install -m 0755 "$REPOSITORY_ROOT/scripts/server/check-wesite-services.sh" "$(destination /usr/local/sbin/check-wesite-services)"
install -m 0755 "$REPOSITORY_ROOT/scripts/server/monitor-wesite-services.sh" "$(destination /usr/local/sbin/monitor-wesite-services)"
install -m 0644 "$REPOSITORY_ROOT/scripts/server/wesite-health-functions.sh" "$(destination /usr/local/sbin/wesite-health-functions.sh)"
install -m 0755 "$REPOSITORY_ROOT/scripts/server/ensure-wesite-swap.sh" "$(destination /usr/local/sbin/ensure-wesite-swap)"
install -m 0600 "$REPOSITORY_ROOT/deploy/systemd/wesite.env.example" "$(destination /etc/wesite/wesite.env.example)"
install -m 0640 "$REPOSITORY_ROOT/wesite-web/src/main/resources/application-prod.properties.example" "$(destination /usr/java/config/web/application-prod.properties.example)"
install -m 0640 "$REPOSITORY_ROOT/wesite-admin/src/main/resources/application-prod.properties.example" "$(destination /usr/java/config/admin/application-prod.properties.example)"

copy_if_missing "$REPOSITORY_ROOT/deploy/systemd/wesite.env.example" "$(destination /etc/wesite/wesite.env)" 0600
copy_if_missing "$REPOSITORY_ROOT/wesite-web/src/main/resources/application-prod.properties.example" "$(destination /usr/java/config/web/application-prod.properties)" 0640
copy_if_missing "$REPOSITORY_ROOT/wesite-admin/src/main/resources/application-prod.properties.example" "$(destination /usr/java/config/admin/application-prod.properties)" 0640

chmod 0600 "$(destination /etc/wesite/wesite.env)"
chmod 0640 "$(destination /usr/java/config/web/application-prod.properties)" \
  "$(destination /usr/java/config/admin/application-prod.properties)"

if [[ "${WESITE_SKIP_SYSTEM_USER:-0}" != 1 ]]; then
  chown -R root:"$APP_GROUP" "$(destination /etc/wesite)" "$(destination /usr/java/config)" "$(destination /usr/java/data)"
  chown -R root:"$APP_GROUP" "$(destination /usr/java/releases)"
  chown -R "$APP_USER":"$APP_GROUP" "$(destination /usr/java/logs)"
  chown "$APP_USER":"$WEB_GROUP" "$(destination /var/www/sitemap)"
  chmod 2775 "$(destination /var/www/sitemap)"
fi

systemctl daemon-reload

printf '%s\n' 'Systemd files installed but not enabled. Edit the three production files, deploy a release, then enable the services.'
