#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
INSTALL_SCRIPT="$REPOSITORY_ROOT/scripts/server/install-wesite-systemd.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

mkdir -p "$TEST_ROOT/root/etc/wesite" \
  "$TEST_ROOT/root/usr/java/config/web" \
  "$TEST_ROOT/root/usr/java/config/admin" \
  "$TEST_ROOT/fake-bin"
printf 'KEEP_THIS_SECRET=yes\n' > "$TEST_ROOT/root/etc/wesite/wesite.env"
printf 'keep-web=true\n' > "$TEST_ROOT/root/usr/java/config/web/application-prod.properties"
printf 'keep-admin=true\n' > "$TEST_ROOT/root/usr/java/config/admin/application-prod.properties"
chmod 0644 "$TEST_ROOT/root/etc/wesite/wesite.env" \
  "$TEST_ROOT/root/usr/java/config/web/application-prod.properties" \
  "$TEST_ROOT/root/usr/java/config/admin/application-prod.properties"

cat > "$TEST_ROOT/fake-bin/systemctl" <<'EOF'
#!/bin/sh
printf 'systemctl %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
exit 0
EOF
chmod +x "$TEST_ROOT/fake-bin/systemctl"

env \
  PATH="$TEST_ROOT/fake-bin:$PATH" \
  WESITE_ROOT_PREFIX="$TEST_ROOT/root" \
  WESITE_SKIP_SYSTEM_USER=1 \
  WESITE_TEST_CALL_LOG="$TEST_ROOT/calls.log" \
  bash "$INSTALL_SCRIPT"

[[ -f "$TEST_ROOT/root/etc/systemd/system/wesite-web.service" ]] || fail 'web service was not installed'
[[ -f "$TEST_ROOT/root/etc/systemd/system/wesite-admin.service" ]] || fail 'admin service was not installed'
[[ -f "$TEST_ROOT/root/etc/systemd/system/wesite-health-monitor.service" ]] || fail 'health monitor service was not installed'
[[ -f "$TEST_ROOT/root/etc/systemd/system/wesite-health-monitor.timer" ]] || fail 'health monitor timer was not installed'
[[ -f "$TEST_ROOT/root/etc/wesite/wesite.env.example" ]] || fail 'environment example was not installed'
[[ -x "$TEST_ROOT/root/usr/local/sbin/deploy-wesite-release" ]] || fail 'deployment command was not installed'
[[ -x "$TEST_ROOT/root/usr/local/sbin/check-wesite-services" ]] || fail 'health-check command was not installed'
[[ -x "$TEST_ROOT/root/usr/local/sbin/monitor-wesite-services" ]] || fail 'health monitor command was not installed'
[[ -r "$TEST_ROOT/root/usr/local/sbin/wesite-health-functions.sh" ]] || fail 'strict readiness library was not installed'
[[ -x "$TEST_ROOT/root/usr/local/sbin/ensure-wesite-swap" ]] || fail 'swap command was not installed'
[[ -f "$TEST_ROOT/root/usr/java/config/web/application-prod.properties.example" ]] || fail 'web config example was not installed'
[[ -f "$TEST_ROOT/root/usr/java/config/admin/application-prod.properties.example" ]] || fail 'admin config example was not installed'
[[ -d "$TEST_ROOT/root/usr/java/releases" ]] || fail 'release directory was not created'
[[ -d "$TEST_ROOT/root/usr/java/logs" ]] || fail 'log directory was not created'

[[ "$(cat "$TEST_ROOT/root/etc/wesite/wesite.env")" == 'KEEP_THIS_SECRET=yes' ]] || fail 'existing environment file was overwritten'
[[ "$(cat "$TEST_ROOT/root/usr/java/config/web/application-prod.properties")" == 'keep-web=true' ]] || fail 'existing web config was overwritten'
[[ "$(cat "$TEST_ROOT/root/usr/java/config/admin/application-prod.properties")" == 'keep-admin=true' ]] || fail 'existing admin config was overwritten'
[[ "$(stat -c '%a' "$TEST_ROOT/root/etc/wesite/wesite.env")" == 600 ]] || fail 'existing environment permissions were not tightened'
[[ "$(stat -c '%a' "$TEST_ROOT/root/usr/java/config/web/application-prod.properties")" == 640 ]] || fail 'existing web config permissions were not tightened'
[[ "$(stat -c '%a' "$TEST_ROOT/root/usr/java/config/admin/application-prod.properties")" == 640 ]] || fail 'existing admin config permissions were not tightened'
grep -Fq -- '--server.address=127.0.0.1 --server.port=8080' \
  "$TEST_ROOT/root/etc/systemd/system/wesite-web.service" \
  || fail 'web unit does not force its loopback bind and port'
grep -Fq -- '--server.address=127.0.0.1 --server.port=8082' \
  "$TEST_ROOT/root/etc/systemd/system/wesite-admin.service" \
  || fail 'admin unit does not force its loopback bind and port'

cat > "$TEST_ROOT/expected-calls.log" <<'EOF'
systemctl daemon-reload
EOF
cmp -s "$TEST_ROOT/expected-calls.log" "$TEST_ROOT/calls.log" || fail 'installer enabled or started services during the migration window'

printf 'Systemd installer tests passed: 1.\n'
