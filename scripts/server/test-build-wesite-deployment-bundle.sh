#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
BUILDER="$REPOSITORY_ROOT/scripts/build-wesite-deployment-bundle.sh"
COMMIT=684d9b8
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
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
if [[ "${*: -1}" == "$WESITE_ROOT_PREFIX"/* ]]; then
  printf 'chmod %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
fi
/usr/bin/chmod "$@"
EOF
  cat > "$fake_bin/systemctl" <<'EOF'
#!/usr/bin/env bash
printf 'systemctl %s\n' "$*" >> "$WESITE_TEST_CALL_LOG"
EOF
  chmod +x "$fake_bin"/*
}

run_bootstrap() {
  local scenario="$1"
  local archive="$2"
  local sidecar="$3"

  env \
    PATH="$scenario/fake-bin:$PATH" \
    WESITE_ROOT_PREFIX="$scenario/root" \
    WESITE_TEST_ACCOUNT_STATE="$scenario/account-state" \
    WESITE_TEST_CALL_LOG="$scenario/calls.log" \
    WESITE_TEST_QUERY_LOG="$scenario/queries.log" \
    bash "$BOOTSTRAP" "$archive" "$sidecar"
}

write_sidecar() {
  local archive="$1"
  local archive_dir
  local archive_name

  archive_dir="$(dirname "$archive")"
  archive_name="$(basename "$archive")"
  (
    cd "$archive_dir"
    sha256sum -- "$archive_name" > "$archive_name.sha256"
  )
}

copy_clean_bundle() {
  local scenario="$1"

  mkdir -p "$scenario/tree"
  cp -a -- "$EXTRACTED/wesite-deployment" "$scenario/tree/"
}

pack_clean_tree() {
  local scenario="$1"
  local archive="$scenario/wesite-deployment-malicious.tar.gz"

  tar -czf "$archive" -C "$scenario/tree" wesite-deployment
  write_sidecar "$archive"
}

expect_bootstrap_failure() {
  local name="$1"
  local archive="$2"
  local sidecar="$3"
  local scenario="$4"
  local status

  make_fake_commands "$scenario"
  mkdir -p "$scenario/root"
  set +e
  run_bootstrap "$scenario" "$archive" "$sidecar" \
    > "$scenario/bootstrap.out" 2>&1
  status=$?
  set -e
  (( status != 0 )) || fail "$name input was accepted"
  [[ ! -s "$scenario/calls.log" ]] \
    || fail "$name input mutated the fake host: $(head -n 1 "$scenario/calls.log")"
}

wait_for_builder_pause() {
  local pause_file="$1"
  local builder_pid="$2"
  local attempt

  for ((attempt = 0; attempt < 500; attempt++)); do
    [[ -e "$pause_file" ]] && return 0
    if ! kill -0 "$builder_pid" 2>/dev/null; then
      wait "$builder_pid" || true
      fail 'builder exited before the publication-race pause'
    fi
    sleep 0.01
  done
  kill "$builder_pid" 2>/dev/null || true
  wait "$builder_pid" 2>/dev/null || true
  fail 'timed out waiting for the publication-race pause'
}

test_publish_race() {
  local kind="$1"
  local scenario="$TEST_ROOT/publish-race-$kind"
  local output="$scenario/output"
  local fake_bin="$scenario/fake-bin"
  local pause_file="$scenario/paused"
  local resume_fifo="$scenario/resume"
  local archive="$output/wesite-deployment-$COMMIT.tar.gz"
  local sidecar="$archive.sha256"
  local bootstrap="$output/install-wesite-deployment-bundle.sh"
  local protected_content="$scenario/protected-content"
  local target
  local sentinel=''
  local builder_pid
  local status
  local failed=0

  mkdir -p "$output" "$fake_bin"
  mkfifo "$resume_fifo"
  printf 'concurrent-owner-%s\n' "$kind" > "$protected_content"
  cat > "$fake_bin/mktemp" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
candidate="$(/usr/bin/mktemp "$@")"
/usr/bin/touch "$WESITE_TEST_BUILD_PAUSED"
IFS= read -r _ < "$WESITE_TEST_BUILD_RESUME"
printf '%s\n' "$candidate"
EOF
  chmod +x "$fake_bin/mktemp"

  env \
    PATH="$fake_bin:$PATH" \
    WESITE_TEST_BUILD_PAUSED="$pause_file" \
    WESITE_TEST_BUILD_RESUME="$resume_fifo" \
    bash "$BUILDER" "$COMMIT" "$output" \
    > "$scenario/builder.out" 2>&1 &
  builder_pid=$!
  wait_for_builder_pause "$pause_file" "$builder_pid"

  case "$kind" in
    archive-file)
      target="$archive"
      cp -- "$protected_content" "$target"
      ;;
    archive-symlink)
      target="$archive"
      sentinel="$scenario/sentinel"
      cp -- "$protected_content" "$sentinel"
      ln -s -- "$sentinel" "$target"
      ;;
    sidecar)
      target="$sidecar"
      cp -- "$protected_content" "$target"
      ;;
    bootstrap)
      target="$bootstrap"
      cp -- "$protected_content" "$target"
      ;;
    *)
      fail "unknown publication-race fixture: $kind"
      ;;
  esac

  printf 'resume\n' > "$resume_fifo"
  set +e
  wait "$builder_pid"
  status=$?
  set -e

  if (( status == 0 )); then
    printf 'FAIL: builder accepted concurrent %s publication target\n' \
      "$kind" >&2
    failed=1
  fi
  if [[ "$kind" == archive-symlink ]]; then
    if [[ ! -L "$target" || "$(readlink -- "$target")" != "$sentinel" ]]; then
      printf 'FAIL: builder replaced the concurrent archive symlink\n' >&2
      failed=1
    fi
    if ! cmp -s "$protected_content" "$sentinel"; then
      printf 'FAIL: builder followed the concurrent archive symlink\n' >&2
      failed=1
    fi
  elif [[ ! -f "$target" || -L "$target" ]] \
      || ! cmp -s "$protected_content" "$target"; then
    printf 'FAIL: builder overwrote the concurrent %s target\n' "$kind" >&2
    failed=1
  fi

  (( failed == 0 ))
}

EXPECTED_PAYLOADS=(
  SOURCE_COMMIT
  SHA256SUMS
  deploy/config/wesite-admin.application-prod.properties.example
  deploy/config/wesite-web.application-prod.properties.example
  deploy/sudoers/wesite-deploy
  deploy/systemd/wesite-admin.service
  deploy/systemd/wesite-health-monitor.service
  deploy/systemd/wesite-health-monitor.timer
  deploy/systemd/wesite-web.service
  deploy/systemd/wesite.env.example
  scripts/server/check-wesite-app.sh
  scripts/server/check-wesite-services.sh
  scripts/server/deploy-wesite-app.sh
  scripts/server/deploy-wesite-release.sh
  scripts/server/ensure-wesite-swap.sh
  scripts/server/install-wesite-systemd.sh
  scripts/server/monitor-wesite-services.sh
  scripts/server/rollback-wesite-app.sh
  scripts/server/wesite-app-functions.sh
  scripts/server/wesite-health-functions.sh
)

OUTPUT_ONE="$TEST_ROOT/output-one"
OUTPUT_TWO="$TEST_ROOT/output-two"
bash "$BUILDER" "$COMMIT" "$OUTPUT_ONE"

ARCHIVE="$OUTPUT_ONE/wesite-deployment-$COMMIT.tar.gz"
SIDECAR="$ARCHIVE.sha256"
BOOTSTRAP="$OUTPUT_ONE/install-wesite-deployment-bundle.sh"
[[ -f "$ARCHIVE" ]] || fail 'builder did not create the deployment archive'
[[ -f "$SIDECAR" ]] || fail 'builder did not create the archive sidecar'
[[ -x "$BOOTSTRAP" ]] || fail 'builder did not create an executable bootstrap installer'

mapfile -t output_files < <(
  find "$OUTPUT_ONE" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' \
    | LC_ALL=C sort
)
expected_output_files=(
  install-wesite-deployment-bundle.sh
  "wesite-deployment-$COMMIT.tar.gz"
  "wesite-deployment-$COMMIT.tar.gz.sha256"
)
[[ "${output_files[*]}" == "${expected_output_files[*]}" ]] \
  || fail "builder produced unexpected output files: ${output_files[*]}"

(
  cd "$OUTPUT_ONE"
  sha256sum -c "$(basename "$SIDECAR")"
)

bash "$BUILDER" "$COMMIT" "$OUTPUT_TWO"
cmp -s "$ARCHIVE" "$OUTPUT_TWO/$(basename "$ARCHIVE")" \
  || fail 'two builds from identical input did not produce identical archive bytes'
cmp -s "$SIDECAR" "$OUTPUT_TWO/$(basename "$SIDECAR")" \
  || fail 'two identical builds did not produce identical sidecars'

EMPTY_OUTPUT="$TEST_ROOT/existing-empty-output"
mkdir "$EMPTY_OUTPUT"
bash "$BUILDER" "$COMMIT" "$EMPTY_OUTPUT"

NONEMPTY_OUTPUT="$TEST_ROOT/nonempty-output"
mkdir "$NONEMPTY_OUTPUT"
printf 'keep\n' > "$NONEMPTY_OUTPUT/sentinel"
set +e
bash "$BUILDER" "$COMMIT" "$NONEMPTY_OUTPUT" \
  > "$TEST_ROOT/nonempty.out" 2>&1
nonempty_status=$?
bash "$BUILDER" not-a-commit "$TEST_ROOT/invalid-commit-output" \
  > "$TEST_ROOT/invalid-commit.out" 2>&1
invalid_commit_status=$?
bash "$BUILDER" "$COMMIT" "$TEST_ROOT/extra-argument-output" unexpected \
  > "$TEST_ROOT/extra-argument.out" 2>&1
extra_argument_status=$?
set -e
(( nonempty_status != 0 )) || fail 'builder accepted a nonempty output directory'
(( invalid_commit_status != 0 )) || fail 'builder accepted a non-hexadecimal commit'
(( extra_argument_status != 0 )) || fail 'builder accepted an extra argument'
[[ "$(cat "$NONEMPTY_OUTPUT/sentinel")" == keep ]] \
  || fail 'builder changed an existing nonempty output directory'

publication_race_failures=0
for race_kind in archive-file archive-symlink sidecar bootstrap; do
  if ! test_publish_race "$race_kind"; then
    publication_race_failures=$((publication_race_failures + 1))
  fi
done
(( publication_race_failures == 0 )) \
  || fail "$publication_race_failures publication-race scenarios failed"

MEMBERS="$TEST_ROOT/archive-members"
tar -tzf "$ARCHIVE" > "$MEMBERS"
grep -Fxq 'wesite-deployment/' "$MEMBERS" \
  || fail 'archive does not contain the single deployment root'
for path in "${EXPECTED_PAYLOADS[@]}"; do
  grep -Fxq "wesite-deployment/$path" "$MEMBERS" \
    || fail "archive is missing required payload: $path"
done
while IFS= read -r member || [[ -n "$member" ]]; do
  [[ "$member" == wesite-deployment/ \
    || "$member" == wesite-deployment/* ]] \
    || fail "archive member is outside the single root: $member"
done < "$MEMBERS"
if grep -E '(^|/)(\.git(/|$)|src(/|$)|target(/|$)|test-|pom\.xml$|README\.md$|[^/]*\.java$|[^/]*\.jar$)' \
  "$MEMBERS"; then
  fail 'archive contains source, test, Maven, Git, documentation, or JAR content'
fi

EXTRACTED="$TEST_ROOT/extracted"
mkdir "$EXTRACTED"
tar -xzf "$ARCHIVE" -C "$EXTRACTED"
[[ "$(cat "$EXTRACTED/wesite-deployment/SOURCE_COMMIT")" == "$COMMIT" ]] \
  || fail 'SOURCE_COMMIT does not contain the exact build commit'
(
  cd "$EXTRACTED/wesite-deployment"
  sha256sum -c SHA256SUMS
)
mapfile -t extracted_files < <(
  cd "$EXTRACTED/wesite-deployment"
  find . -type f -printf '%P\n' | LC_ALL=C sort
)
mapfile -t expected_payloads < <(printf '%s\n' "${EXPECTED_PAYLOADS[@]}" | LC_ALL=C sort)
[[ "${extracted_files[*]}" == "${expected_payloads[*]}" ]] \
  || fail "archive file whitelist differs: ${extracted_files[*]}"

POSITIVE="$TEST_ROOT/positive"
make_fake_commands "$POSITIVE"
mkdir -p "$POSITIVE/root"
if ! run_bootstrap "$POSITIVE" "$ARCHIVE" "$SIDECAR" \
  > "$POSITIVE/bootstrap.out" 2>&1; then
  cat "$POSITIVE/bootstrap.out" >&2
  fail 'bootstrap rejected the builder output'
fi
[[ -f "$POSITIVE/root/etc/systemd/system/wesite-web.service" ]] \
  || fail 'bootstrap did not invoke the bundled installer'
grep -Fxq 'systemctl daemon-reload' "$POSITIVE/calls.log" \
  || fail 'bundled installer did not complete through daemon-reload'

# Archive modes and ownership must not be restored into the private extraction.
PRIVATE="$TEST_ROOT/private-extraction"
copy_clean_bundle "$PRIVATE"
cat > "$PRIVATE/tree/wesite-deployment/scripts/server/install-wesite-systemd.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
deployment_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
[[ "$(stat -c '%a' "$deployment_root")" == 700 ]]
[[ "$(stat -c '%u' "$deployment_root")" == "$(/usr/bin/id -u)" ]]
[[ "$(stat -c '%a' "$deployment_root/scripts/server/install-wesite-systemd.sh")" == 700 ]]
printf 'PRIVATE_EXTRACTION_OK\n'
EOF
(
  cd "$PRIVATE/tree/wesite-deployment"
  sha256sum scripts/server/install-wesite-systemd.sh \
    > "$PRIVATE/installer.sum"
)
awk 'FNR == NR { replacement = $0; next } \
  $2 == "scripts/server/install-wesite-systemd.sh" { print replacement; next } \
  { print }' "$PRIVATE/installer.sum" \
  "$PRIVATE/tree/wesite-deployment/SHA256SUMS" > "$PRIVATE/manifest.next"
mv -- "$PRIVATE/manifest.next" "$PRIVATE/tree/wesite-deployment/SHA256SUMS"
chmod -R 0777 "$PRIVATE/tree/wesite-deployment"
PRIVATE_ARCHIVE="$PRIVATE/wesite-deployment-private.tar.gz"
tar --owner=12345 --group=12345 -czf "$PRIVATE_ARCHIVE" \
  -C "$PRIVATE/tree" wesite-deployment
write_sidecar "$PRIVATE_ARCHIVE"
make_fake_commands "$PRIVATE"
mkdir -p "$PRIVATE/root"
if ! run_bootstrap "$PRIVATE" "$PRIVATE_ARCHIVE" "$PRIVATE_ARCHIVE.sha256" \
  > "$PRIVATE/bootstrap.out" 2>&1; then
  cat "$PRIVATE/bootstrap.out" >&2
  fail 'bootstrap rejected the private-extraction fixture'
fi
grep -Fxq PRIVATE_EXTRACTION_OK "$PRIVATE/bootstrap.out" \
  || fail 'bootstrap restored archive owner or permissions during extraction'

CHECKSUM_MISMATCH="$TEST_ROOT/checksum-mismatch"
copy_clean_bundle "$CHECKSUM_MISMATCH"
pack_clean_tree "$CHECKSUM_MISMATCH"
CHECKSUM_ARCHIVE="$CHECKSUM_MISMATCH/wesite-deployment-malicious.tar.gz"
printf 'corrupt\n' >> "$CHECKSUM_ARCHIVE"
expect_bootstrap_failure checksum-mismatch "$CHECKSUM_ARCHIVE" \
  "$CHECKSUM_ARCHIVE.sha256" "$CHECKSUM_MISMATCH"

WRONG_SIDECAR="$TEST_ROOT/wrong-sidecar-basename"
copy_clean_bundle "$WRONG_SIDECAR"
pack_clean_tree "$WRONG_SIDECAR"
WRONG_ARCHIVE="$WRONG_SIDECAR/wesite-deployment-malicious.tar.gz"
WRONG_HASH="$(sha256sum "$WRONG_ARCHIVE" | awk '{print $1}')"
printf '%s  %s\n' "$WRONG_HASH" other-archive.tar.gz \
  > "$WRONG_ARCHIVE.sha256"
expect_bootstrap_failure wrong-sidecar-basename "$WRONG_ARCHIVE" \
  "$WRONG_ARCHIVE.sha256" "$WRONG_SIDECAR"

ABSOLUTE_MEMBER="$TEST_ROOT/absolute-archive-path"
copy_clean_bundle "$ABSOLUTE_MEMBER"
ABSOLUTE_ARCHIVE="$ABSOLUTE_MEMBER/wesite-deployment-malicious.tar.gz"
tar -cPzf "$ABSOLUTE_ARCHIVE" -C "$ABSOLUTE_MEMBER/tree" \
  --transform='s|^wesite-deployment|/wesite-deployment|' wesite-deployment
write_sidecar "$ABSOLUTE_ARCHIVE"
expect_bootstrap_failure absolute-archive-path "$ABSOLUTE_ARCHIVE" \
  "$ABSOLUTE_ARCHIVE.sha256" "$ABSOLUTE_MEMBER"

PARENT_MEMBER="$TEST_ROOT/parent-archive-path"
copy_clean_bundle "$PARENT_MEMBER"
PARENT_ARCHIVE="$PARENT_MEMBER/wesite-deployment-malicious.tar.gz"
tar -czf "$PARENT_ARCHIVE" -C "$PARENT_MEMBER/tree" \
  --transform='s|^wesite-deployment|wesite-deployment/../outside|' \
  wesite-deployment
write_sidecar "$PARENT_ARCHIVE"
expect_bootstrap_failure parent-archive-path "$PARENT_ARCHIVE" \
  "$PARENT_ARCHIVE.sha256" "$PARENT_MEMBER"

OUTSIDE_ROOT="$TEST_ROOT/outside-single-root"
copy_clean_bundle "$OUTSIDE_ROOT"
OUTSIDE_ARCHIVE="$OUTSIDE_ROOT/wesite-deployment-malicious.tar.gz"
tar -czf "$OUTSIDE_ARCHIVE" -C "$OUTSIDE_ROOT/tree" \
  --transform='s|^wesite-deployment|other-root|' wesite-deployment
write_sidecar "$OUTSIDE_ARCHIVE"
expect_bootstrap_failure outside-single-root "$OUTSIDE_ARCHIVE" \
  "$OUTSIDE_ARCHIVE.sha256" "$OUTSIDE_ROOT"

ABSOLUTE_MANIFEST="$TEST_ROOT/absolute-checksum-path"
copy_clean_bundle "$ABSOLUTE_MANIFEST"
printf '%064d  /etc/passwd\n' 0 \
  >> "$ABSOLUTE_MANIFEST/tree/wesite-deployment/SHA256SUMS"
pack_clean_tree "$ABSOLUTE_MANIFEST"
ABSOLUTE_MANIFEST_ARCHIVE="$ABSOLUTE_MANIFEST/wesite-deployment-malicious.tar.gz"
expect_bootstrap_failure absolute-checksum-path "$ABSOLUTE_MANIFEST_ARCHIVE" \
  "$ABSOLUTE_MANIFEST_ARCHIVE.sha256" "$ABSOLUTE_MANIFEST"

PARENT_MANIFEST="$TEST_ROOT/parent-checksum-path"
copy_clean_bundle "$PARENT_MANIFEST"
printf 'outside\n' > "$PARENT_MANIFEST/tree/outside"
(
  cd "$PARENT_MANIFEST/tree/wesite-deployment"
  sha256sum ../outside >> SHA256SUMS
)
pack_clean_tree "$PARENT_MANIFEST"
PARENT_MANIFEST_ARCHIVE="$PARENT_MANIFEST/wesite-deployment-malicious.tar.gz"
expect_bootstrap_failure parent-checksum-path "$PARENT_MANIFEST_ARCHIVE" \
  "$PARENT_MANIFEST_ARCHIVE.sha256" "$PARENT_MANIFEST"

RESOLVED_OUTSIDE="$TEST_ROOT/resolved-outside-checksum-path"
copy_clean_bundle "$RESOLVED_OUTSIDE"
mkdir "$RESOLVED_OUTSIDE/outside"
printf 'outside\n' > "$RESOLVED_OUTSIDE/outside/payload"
ln -s "$RESOLVED_OUTSIDE/outside" \
  "$RESOLVED_OUTSIDE/tree/wesite-deployment/escape"
(
  cd "$RESOLVED_OUTSIDE/tree/wesite-deployment"
  sha256sum escape/payload >> SHA256SUMS
)
pack_clean_tree "$RESOLVED_OUTSIDE"
RESOLVED_OUTSIDE_ARCHIVE="$RESOLVED_OUTSIDE/wesite-deployment-malicious.tar.gz"
expect_bootstrap_failure resolved-outside-checksum-path \
  "$RESOLVED_OUTSIDE_ARCHIVE" "$RESOLVED_OUTSIDE_ARCHIVE.sha256" \
  "$RESOLVED_OUTSIDE"

DUPLICATE_MANIFEST="$TEST_ROOT/duplicate-checksum-entry"
copy_clean_bundle "$DUPLICATE_MANIFEST"
head -n 1 "$DUPLICATE_MANIFEST/tree/wesite-deployment/SHA256SUMS" \
  >> "$DUPLICATE_MANIFEST/tree/wesite-deployment/SHA256SUMS"
pack_clean_tree "$DUPLICATE_MANIFEST"
DUPLICATE_ARCHIVE="$DUPLICATE_MANIFEST/wesite-deployment-malicious.tar.gz"
expect_bootstrap_failure duplicate-checksum-entry "$DUPLICATE_ARCHIVE" \
  "$DUPLICATE_ARCHIVE.sha256" "$DUPLICATE_MANIFEST"

SPECIAL_ENTRY="$TEST_ROOT/special-archive-entry"
copy_clean_bundle "$SPECIAL_ENTRY"
mkfifo "$SPECIAL_ENTRY/tree/wesite-deployment/special-fifo"
pack_clean_tree "$SPECIAL_ENTRY"
SPECIAL_ARCHIVE="$SPECIAL_ENTRY/wesite-deployment-malicious.tar.gz"
expect_bootstrap_failure special-archive-entry "$SPECIAL_ARCHIVE" \
  "$SPECIAL_ARCHIVE.sha256" "$SPECIAL_ENTRY"

printf '%s\n' \
  'Deployment bundle tests passed: reproducible whitelist, 10 rejection scenarios, and 4 publication races.'
