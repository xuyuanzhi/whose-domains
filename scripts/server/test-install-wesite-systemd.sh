#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
TEST_ROOT="$(mktemp -d)"
EXPECTED_SUDOERS='wesite-deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-wesite-app web *, /usr/local/sbin/deploy-wesite-app admin *, /usr/local/sbin/check-wesite-app web, /usr/local/sbin/check-wesite-app admin'

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_file_mode() {
  local path="$1"
  local expected="$2"
  local actual
  actual="$(stat -c '%a' -- "$path")"
  [[ "$actual" == "$expected" ]] \
    || fail "$path mode was $actual, expected $expected"
}

make_bundle() {
  local bundle="$1"
  local path
  local -a payloads=(
    deploy/systemd/wesite-web.service
    deploy/systemd/wesite-admin.service
    deploy/systemd/wesite-health-monitor.service
    deploy/systemd/wesite-health-monitor.timer
    deploy/systemd/wesite.env.example
    deploy/tmpfiles.d/wesite.conf
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

  mkdir -p "$bundle/deploy/config" "$bundle/deploy/sudoers" \
    "$bundle/deploy/systemd" "$bundle/deploy/tmpfiles.d" \
    "$bundle/scripts/server"
  for path in "${payloads[@]}"; do
    cp -- "$REPOSITORY_ROOT/$path" "$bundle/$path"
  done
  cp -- "$REPOSITORY_ROOT/deploy/config/wesite-web.application-prod.properties.example" \
    "$bundle/deploy/config/wesite-web.application-prod.properties.example"
  cp -- "$REPOSITORY_ROOT/deploy/config/wesite-admin.application-prod.properties.example" \
    "$bundle/deploy/config/wesite-admin.application-prod.properties.example"
  cp -- "$REPOSITORY_ROOT/deploy/sudoers/wesite-deploy" \
    "$bundle/deploy/sudoers/wesite-deploy"
  printf '%s\n' 0123456789abcdef0123456789abcdef01234567 > "$bundle/SOURCE_COMMIT"
  (
    cd "$bundle"
    find . -type f ! -name SHA256SUMS -printf '%P\0' \
      | LC_ALL=C sort -z \
      | xargs -0 sha256sum > SHA256SUMS
  )
}

make_fake_commands() {
  local scenario="$1"
  local fake_bin="$scenario/fake-bin"

  mkdir -p "$fake_bin" "$scenario/account-state"
  : > "$scenario/calls.log"
  : > "$scenario/queries.log"

  cat > "$fake_bin/getent" <<'EOF'
#!/usr/bin/env bash
printf 'getent %s\n' "$*" >> "$WESITE_TEST_QUERY_LOG"
[[ "$1" == group ]] || exit 2
if [[ "$2" == www-data || -e "$WESITE_TEST_ACCOUNT_STATE/group-$2" ]]; then
  exit 0
fi
exit 2
EOF
  cat > "$fake_bin/groupadd" <<'EOF'
#!/usr/bin/env bash
printf 'groupadd %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
touch "$WESITE_TEST_ACCOUNT_STATE/group-${*: -1}"
EOF
  cat > "$fake_bin/id" <<'EOF'
#!/usr/bin/env bash
printf 'id %s\n' "$*" >> "$WESITE_TEST_QUERY_LOG"
if [[ "$1" == -u && -n "${2:-}" && -e "$WESITE_TEST_ACCOUNT_STATE/user-$2" ]]; then
  printf '1234\n'
  exit 0
fi
exit 1
EOF
  cat > "$fake_bin/useradd" <<'EOF'
#!/usr/bin/env bash
printf 'useradd %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
touch "$WESITE_TEST_ACCOUNT_STATE/user-${*: -1}"
EOF
  cat > "$fake_bin/usermod" <<'EOF'
#!/usr/bin/env bash
printf 'usermod %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
EOF
  cat > "$fake_bin/chown" <<'EOF'
#!/usr/bin/env bash
printf 'chown %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
EOF
  cat > "$fake_bin/install" <<'EOF'
#!/usr/bin/env bash
printf 'install %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
/usr/bin/install "$@"
EOF
  cat > "$fake_bin/chmod" <<'EOF'
#!/usr/bin/env bash
printf 'chmod %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
/usr/bin/chmod "$@"
EOF
  cat > "$fake_bin/systemctl" <<'EOF'
#!/usr/bin/env bash
printf 'systemctl %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
EOF
  cat > "$fake_bin/systemd-tmpfiles" <<'EOF'
#!/usr/bin/env bash
printf 'systemd-tmpfiles %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
mkdir -p "$WESITE_ROOT_PREFIX/run/lock/wesite"
chmod 0755 "$WESITE_ROOT_PREFIX/run/lock/wesite"
chown 0:0 "$WESITE_ROOT_PREFIX/run/lock/wesite"
EOF
  cat > "$fake_bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
printf 'sha256sum %s\n' "$*" >> "$WESITE_TEST_QUERY_LOG"
exec /usr/bin/sha256sum "$@"
EOF
  chmod +x "$fake_bin"/*
}

run_installer() {
  local scenario="$1"
  local bundle="$2"

  env \
    PATH="$scenario/fake-bin:$PATH" \
    WESITE_ROOT_PREFIX="$scenario/root" \
    WESITE_TEST_ACCOUNT_STATE="$scenario/account-state" \
    WESITE_TEST_CALL_LOG="$scenario/calls.log" \
    WESITE_TEST_QUERY_LOG="$scenario/queries.log" \
    bash "$bundle/scripts/server/install-wesite-systemd.sh"
}

expect_invalid_bundle() {
  local name="$1"
  local mutator="$2"
  local expect_checksum_check="${3:-0}"
  local scenario="$TEST_ROOT/negative-$name"
  local bundle="$scenario/bundle"
  local status

  mkdir -p "$scenario/root"
  make_bundle "$bundle"
  make_fake_commands "$scenario"
  "$mutator" "$scenario" "$bundle"
  set +e
  run_installer "$scenario" "$bundle" > "$scenario/output.log" 2>&1
  status=$?
  set -e
  (( status != 0 )) || fail "$name bundle was accepted"
  [[ ! -s "$scenario/calls.log" ]] \
    || fail "$name bundle caused host mutation before rejection: $(head -n 1 "$scenario/calls.log")"
  if [[ "$expect_checksum_check" == 1 ]]; then
    grep -q '^sha256sum --check ' "$scenario/queries.log" \
      || fail "$name bundle did not reach checksum verification"
  elif grep -q '^sha256sum --check ' "$scenario/queries.log"; then
    fail "$name bundle reached checksum verification before all paths were validated"
  fi
}

remove_manifest() {
  rm -- "$2/SHA256SUMS"
}

remove_source_commit() {
  rm -- "$2/SOURCE_COMMIT"
}

malform_source_commit() {
  printf '%s\n' 'not-a-git-commit' > "$2/SOURCE_COMMIT"
  (
    cd "$2"
    sha256sum SOURCE_COMMIT > "$1/source-commit.sum"
  )
  awk 'FNR == NR { replacement = $0; next } $2 == "SOURCE_COMMIT" { print replacement; next } { print }' \
    "$1/source-commit.sum" "$2/SHA256SUMS" > "$1/manifest.next"
  mv -- "$1/manifest.next" "$2/SHA256SUMS"
}

corrupt_payload() {
  printf '%s\n' '# tampered' >> "$2/scripts/server/check-wesite-app.sh"
}

duplicate_manifest_entry() {
  head -n 1 "$2/SHA256SUMS" >> "$2/SHA256SUMS"
}

add_absolute_manifest_entry() {
  printf '%064d  /etc/passwd\n' 0 >> "$2/SHA256SUMS"
}

add_parent_manifest_entry() {
  printf 'outside\n' > "$1/outside"
  (
    cd "$2"
    sha256sum ../outside >> SHA256SUMS
  )
}

add_malformed_manifest_entry() {
  printf '%s\n' 'this is not a checksum line' >> "$2/SHA256SUMS"
}

omit_required_manifest_entry() {
  grep -v '  scripts/server/rollback-wesite-app.sh$' "$2/SHA256SUMS" \
    > "$1/manifest.next"
  mv -- "$1/manifest.next" "$2/SHA256SUMS"
}

remove_required_payload() {
  rm -- "$2/scripts/server/rollback-wesite-app.sh"
}

add_resolved_outside_entry() {
  local scenario="$1"
  local bundle="$2"
  mkdir "$scenario/outside-dir"
  printf 'outside\n' > "$scenario/outside-dir/payload"
  ln -s "$scenario/outside-dir" "$bundle/escape"
  (
    cd "$bundle"
    sha256sum escape/payload >> SHA256SUMS
  )
}

replace_required_with_internal_symlink() {
  local scenario="$1"
  local bundle="$2"
  rm -- "$bundle/scripts/server/rollback-wesite-app.sh"
  ln -s deploy-wesite-app.sh "$bundle/scripts/server/rollback-wesite-app.sh"
  grep -v '  scripts/server/rollback-wesite-app.sh$' "$bundle/SHA256SUMS" \
    > "$scenario/manifest.next"
  (
    cd "$bundle"
    sha256sum scripts/server/rollback-wesite-app.sh >> "$scenario/manifest.next"
  )
  mv -- "$scenario/manifest.next" "$bundle/SHA256SUMS"
}

# Every malformed or incomplete bundle must be rejected before an account,
# filesystem, permission, ownership, or systemd mutation command is invoked.
expect_invalid_bundle missing-manifest remove_manifest
expect_invalid_bundle missing-source-commit remove_source_commit
expect_invalid_bundle malformed-source-commit malform_source_commit
expect_invalid_bundle checksum-mismatch corrupt_payload 1
expect_invalid_bundle duplicate-entry duplicate_manifest_entry
expect_invalid_bundle absolute-path add_absolute_manifest_entry
expect_invalid_bundle parent-path add_parent_manifest_entry
expect_invalid_bundle malformed-line add_malformed_manifest_entry
expect_invalid_bundle unlisted-required-input omit_required_manifest_entry
expect_invalid_bundle missing-required-input remove_required_payload
expect_invalid_bundle resolved-outside add_resolved_outside_entry
expect_invalid_bundle symlinked-input replace_required_with_internal_symlink

SCENARIO="$TEST_ROOT/positive"
BUNDLE="$SCENARIO/bundle"
ROOT="$SCENARIO/root"
mkdir -p "$ROOT/etc/wesite" "$ROOT/usr/java/config/web" \
  "$ROOT/usr/java/config/admin"
make_bundle "$BUNDLE"
make_fake_commands "$SCENARIO"
printf 'KEEP_THIS_SECRET=yes\n' > "$ROOT/etc/wesite/wesite.env"
printf 'keep-web=true\n' > "$ROOT/usr/java/config/web/application-prod.properties"
printf 'keep-admin=true\n' > "$ROOT/usr/java/config/admin/application-prod.properties"
/usr/bin/chmod 0644 "$ROOT/etc/wesite/wesite.env" \
  "$ROOT/usr/java/config/web/application-prod.properties" \
  "$ROOT/usr/java/config/admin/application-prod.properties"

run_installer "$SCENARIO" "$BUNDLE"

for command in deploy-wesite-app rollback-wesite-app check-wesite-app \
  check-wesite-services monitor-wesite-services; do
  [[ -x "$ROOT/usr/local/sbin/$command" ]] \
    || fail "$command was not installed as an executable"
done
[[ -x "$ROOT/usr/local/sbin/deploy-wesite-release" ]] \
  || fail 'fail-closed legacy deployment stub was not installed'
[[ -r "$ROOT/usr/local/sbin/wesite-app-functions.sh" ]] \
  || fail 'application contract library was not installed'
[[ -r "$ROOT/usr/local/sbin/wesite-health-functions.sh" ]] \
  || fail 'readiness library was not installed'

for unit in wesite-web.service wesite-admin.service \
  wesite-health-monitor.service wesite-health-monitor.timer; do
  [[ -f "$ROOT/etc/systemd/system/$unit" ]] || fail "$unit was not installed"
done
grep -Fq '/usr/java/apps/web/current/wesite-web.jar' \
  "$ROOT/etc/systemd/system/wesite-web.service" \
  || fail 'web unit does not use the independent Web release path'
grep -Fq '/usr/java/apps/admin/current/wesite-admin.jar' \
  "$ROOT/etc/systemd/system/wesite-admin.service" \
  || fail 'admin unit does not use the independent Admin release path'

[[ -d "$ROOT/usr/java/apps/web/releases" ]] \
  || fail 'Web release root was not created'
[[ -d "$ROOT/usr/java/apps/admin/releases" ]] \
  || fail 'Admin release root was not created'
[[ -d "$ROOT/var/lib/wesite-deploy/incoming" ]] \
  || fail 'deployment incoming directory was not created'
[[ -d "$ROOT/run/lock/wesite" ]] \
  || fail 'root-owned deployment lock directory was not created'
assert_file_mode "$ROOT/usr/java/apps/web/releases" 750
assert_file_mode "$ROOT/usr/java/apps/admin/releases" 750
assert_file_mode "$ROOT/var/lib/wesite-deploy" 750
assert_file_mode "$ROOT/var/lib/wesite-deploy/incoming" 750
assert_file_mode "$ROOT/run/lock/wesite" 755

[[ "$(cat "$ROOT/etc/wesite/wesite.env")" == 'KEEP_THIS_SECRET=yes' ]] \
  || fail 'existing environment file was overwritten'
[[ "$(cat "$ROOT/usr/java/config/web/application-prod.properties")" == 'keep-web=true' ]] \
  || fail 'existing Web config was overwritten'
[[ "$(cat "$ROOT/usr/java/config/admin/application-prod.properties")" == 'keep-admin=true' ]] \
  || fail 'existing Admin config was overwritten'
assert_file_mode "$ROOT/etc/wesite/wesite.env" 600
assert_file_mode "$ROOT/usr/java/config/web/application-prod.properties" 640
assert_file_mode "$ROOT/usr/java/config/admin/application-prod.properties" 640
assert_file_mode "$ROOT/etc/wesite/wesite.env.example" 600
assert_file_mode "$ROOT/usr/java/config/web/application-prod.properties.example" 640
assert_file_mode "$ROOT/usr/java/config/admin/application-prod.properties.example" 640
assert_file_mode "$ROOT/etc/wesite/wesite-deploy.sudoers.example" 640
cmp -s "$BUNDLE/deploy/config/wesite-web.application-prod.properties.example" \
  "$ROOT/usr/java/config/web/application-prod.properties.example" \
  || fail 'installed Web config example does not match the bundle'
cmp -s "$BUNDLE/deploy/config/wesite-admin.application-prod.properties.example" \
  "$ROOT/usr/java/config/admin/application-prod.properties.example" \
  || fail 'installed Admin config example does not match the bundle'
printf '%s\n' "$EXPECTED_SUDOERS" > "$SCENARIO/expected-sudoers"
cmp -s "$SCENARIO/expected-sudoers" \
  "$ROOT/etc/wesite/wesite-deploy.sudoers.example" \
  || fail 'installed sudoers example grants unexpected commands'
[[ ! -e "$ROOT/etc/sudoers.d/wesite-deploy" ]] \
  || fail 'installer activated the sudoers example'

grep -Fxq 'groupadd --system wesite' "$SCENARIO/calls.log" \
  || fail 'runtime private group was not created exactly'
grep -Fxq 'useradd --system --gid wesite --home-dir /nonexistent --shell /usr/sbin/nologin wesite' \
  "$SCENARIO/calls.log" || fail 'runtime user contract was not created exactly'
grep -Fxq 'groupadd --system wesite-deploy' "$SCENARIO/calls.log" \
  || fail 'deployment private group was not created exactly'
grep -Fxq 'useradd --system --gid wesite-deploy --home-dir /var/lib/wesite-deploy --shell /bin/bash wesite-deploy' \
  "$SCENARIO/calls.log" || fail 'deployment SSH user contract was not created exactly'
grep -Fxq 'usermod -a -G www-data wesite' "$SCENARIO/calls.log" \
  || fail 'runtime user was not added to the Web group'
grep -Fq "chown root:wesite $ROOT/usr/java/apps/web/releases" \
  "$SCENARIO/calls.log" || fail 'Web releases ownership was not enforced'
grep -Fq "chown root:wesite $ROOT/usr/java/apps/admin/releases" \
  "$SCENARIO/calls.log" || fail 'Admin releases ownership was not enforced'
grep -Fq "chown wesite-deploy:wesite-deploy $ROOT/var/lib/wesite-deploy" \
  "$SCENARIO/calls.log" || fail 'deployment home ownership was not enforced'
grep -Fq "chown wesite-deploy:wesite-deploy $ROOT/var/lib/wesite-deploy/incoming" \
  "$SCENARIO/calls.log" || fail 'incoming ownership was not enforced'
grep -Fxq "systemd-tmpfiles --root=$ROOT --create wesite.conf" \
  "$SCENARIO/calls.log" || fail 'deployment lock tmpfiles policy was not applied'

mapfile -t systemctl_calls < <(grep '^systemctl ' "$SCENARIO/calls.log")
[[ "${#systemctl_calls[@]}" -eq 1 \
  && "${systemctl_calls[0]}" == 'systemctl daemon-reload' ]] \
  || fail 'installer enabled or started units'

set +e
"$ROOT/usr/local/sbin/deploy-wesite-release" > "$SCENARIO/legacy.out" 2>&1
legacy_status=$?
set -e
[[ "$legacy_status" -eq 64 ]] || fail 'legacy paired command did not fail closed with exit 64'
grep -Fq 'paired deployment is retired' "$SCENARIO/legacy.out" \
  || fail 'legacy paired command did not explain its retirement'

# A second installation must preserve live config and must not recreate users
# or groups once the fake account database reports them as present.
run_installer "$SCENARIO" "$BUNDLE"
[[ "$(grep -c '^groupadd ' "$SCENARIO/calls.log")" -eq 2 ]] \
  || fail 'idempotent reinstall recreated a group'
[[ "$(grep -c '^useradd ' "$SCENARIO/calls.log")" -eq 2 ]] \
  || fail 'idempotent reinstall recreated a user'
grep -Fxq 'usermod --gid wesite --home /nonexistent --shell /usr/sbin/nologin wesite' \
  "$SCENARIO/calls.log" || fail 'existing runtime user contract was not enforced'
grep -Fxq 'usermod --gid wesite-deploy --home /var/lib/wesite-deploy --shell /bin/bash wesite-deploy' \
  "$SCENARIO/calls.log" || fail 'existing deployment user contract was not enforced'
[[ "$(cat "$ROOT/etc/wesite/wesite.env")" == 'KEEP_THIS_SECRET=yes' ]] \
  || fail 'idempotent reinstall overwrote the environment file'
[[ "$(grep -c '^systemctl daemon-reload$' "$SCENARIO/calls.log")" -eq 2 ]] \
  || fail 'each successful installation must reload systemd exactly once'

printf 'Systemd installer tests passed: 13 bundle scenarios.\n'
