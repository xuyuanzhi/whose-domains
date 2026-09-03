#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
STAGING_DIRECTORY=''
STAGING_IDENTITY=''

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  local current_identity

  [[ -n "$STAGING_DIRECTORY" && -n "$STAGING_IDENTITY" \
    && -d "$STAGING_DIRECTORY" && ! -L "$STAGING_DIRECTORY" ]] \
    || return 0
  current_identity="$(stat -c '%d:%i' -- "$STAGING_DIRECTORY" 2>/dev/null)" \
    || return 0
  [[ "$current_identity" == "$STAGING_IDENTITY" ]] || return 0
  rm -rf -- "$STAGING_DIRECTORY"
}
trap cleanup EXIT

[[ "$#" -eq 2 ]] \
  || fail 'Usage: build-wesite-deployment-bundle.sh COMMIT OUTPUT_DIRECTORY'

SOURCE_COMMIT="$1"
OUTPUT_DIRECTORY="$2"
[[ "$SOURCE_COMMIT" =~ ^[0-9a-f]{7,64}$ ]] \
  || fail 'COMMIT must contain 7 to 64 lowercase hexadecimal characters'
[[ -n "$OUTPUT_DIRECTORY" ]] || fail 'OUTPUT_DIRECTORY must not be empty'

READ_ONLY_PAYLOADS=(
  deploy/config/wesite-admin.application-prod.properties.example
  deploy/config/wesite-web.application-prod.properties.example
  deploy/sudoers/wesite-deploy
  deploy/systemd/wesite-admin.service
  deploy/systemd/wesite-health-monitor.service
  deploy/systemd/wesite-health-monitor.timer
  deploy/systemd/wesite-web.service
  deploy/systemd/wesite.env.example
  scripts/server/wesite-app-functions.sh
  scripts/server/wesite-health-functions.sh
)
EXECUTABLE_PAYLOADS=(
  scripts/server/check-wesite-app.sh
  scripts/server/check-wesite-services.sh
  scripts/server/deploy-wesite-app.sh
  scripts/server/deploy-wesite-release.sh
  scripts/server/ensure-wesite-swap.sh
  scripts/server/install-wesite-systemd.sh
  scripts/server/monitor-wesite-services.sh
  scripts/server/rollback-wesite-app.sh
)
BOOTSTRAP_SOURCE="$REPOSITORY_ROOT/scripts/server/install-wesite-deployment-bundle.sh"

for payload in "${READ_ONLY_PAYLOADS[@]}" "${EXECUTABLE_PAYLOADS[@]}"; do
  source_path="$REPOSITORY_ROOT/$payload"
  [[ -f "$source_path" && ! -L "$source_path" ]] \
    || fail "required production payload is missing or unsafe: $payload"
  [[ "$(realpath -e -- "$source_path")" == "$source_path" ]] \
    || fail "required production payload is not canonical: $payload"
done
[[ -f "$BOOTSTRAP_SOURCE" && ! -L "$BOOTSTRAP_SOURCE" ]] \
  || fail 'standalone bootstrap installer is missing or unsafe'
[[ "$(realpath -e -- "$BOOTSTRAP_SOURCE")" == "$BOOTSTRAP_SOURCE" ]] \
  || fail 'standalone bootstrap installer is not canonical'

if [[ -e "$OUTPUT_DIRECTORY" || -L "$OUTPUT_DIRECTORY" ]]; then
  [[ -d "$OUTPUT_DIRECTORY" && ! -L "$OUTPUT_DIRECTORY" ]] \
    || fail 'OUTPUT_DIRECTORY exists and is not a regular directory'
  shopt -s nullglob dotglob
  output_entries=("$OUTPUT_DIRECTORY"/*)
  shopt -u nullglob dotglob
  [[ "${#output_entries[@]}" -eq 0 ]] \
    || fail 'OUTPUT_DIRECTORY must be empty'
else
  mkdir -- "$OUTPUT_DIRECTORY"
fi
OUTPUT_DIRECTORY="$(cd "$OUTPUT_DIRECTORY" && pwd -P)"
[[ -d "$OUTPUT_DIRECTORY" && ! -L "$OUTPUT_DIRECTORY" ]] \
  || fail 'OUTPUT_DIRECTORY is not a canonical directory'

umask 077
staging_candidate="$(
  mktemp -d "$OUTPUT_DIRECTORY/.wesite-deployment-build.XXXXXXXXXX"
)"
[[ "$staging_candidate" == /* && "$staging_candidate" != / \
  && -d "$staging_candidate" && ! -L "$staging_candidate" ]] \
  || fail 'could not create a safe private staging directory'
STAGING_DIRECTORY="$staging_candidate"
STAGING_IDENTITY="$(stat -c '%d:%i' -- "$STAGING_DIRECTORY")"
chmod 0700 "$staging_candidate"
[[ "$(realpath -e -- "$staging_candidate")" == "$STAGING_DIRECTORY" ]] \
  || fail 'private staging directory is not canonical'

BUNDLE_ROOT="$STAGING_DIRECTORY/wesite-deployment"
mkdir "$BUNDLE_ROOT"
for payload in "${READ_ONLY_PAYLOADS[@]}"; do
  destination="$BUNDLE_ROOT/$payload"
  mkdir -p -- "$(dirname "$destination")"
  install -m 0644 -- "$REPOSITORY_ROOT/$payload" "$destination"
done
for payload in "${EXECUTABLE_PAYLOADS[@]}"; do
  destination="$BUNDLE_ROOT/$payload"
  mkdir -p -- "$(dirname "$destination")"
  install -m 0755 -- "$REPOSITORY_ROOT/$payload" "$destination"
done
find "$BUNDLE_ROOT" -type d -exec chmod 0755 -- {} +
printf '%s\n' "$SOURCE_COMMIT" > "$BUNDLE_ROOT/SOURCE_COMMIT"
chmod 0644 "$BUNDLE_ROOT/SOURCE_COMMIT"

MANIFEST_PAYLOADS=(
  "${READ_ONLY_PAYLOADS[@]}"
  "${EXECUTABLE_PAYLOADS[@]}"
  SOURCE_COMMIT
)
(
  cd "$BUNDLE_ROOT"
  printf '%s\0' "${MANIFEST_PAYLOADS[@]}" \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum > SHA256SUMS
  chmod 0644 SHA256SUMS
)

ARCHIVE_NAME="wesite-deployment-$SOURCE_COMMIT.tar.gz"
ARCHIVE_PATH="$STAGING_DIRECTORY/$ARCHIVE_NAME"
tar --sort=name --format=gnu --mtime='@0' --owner=0 --group=0 \
  --numeric-owner -cf - -C "$STAGING_DIRECTORY" wesite-deployment \
  | gzip -n -9 > "$ARCHIVE_PATH"
(
  cd "$STAGING_DIRECTORY"
  sha256sum -- "$ARCHIVE_NAME" > "$ARCHIVE_NAME.sha256"
)
install -m 0755 -- "$BOOTSTRAP_SOURCE" \
  "$STAGING_DIRECTORY/install-wesite-deployment-bundle.sh"

OUTPUT_FILES=(
  "$ARCHIVE_NAME"
  "$ARCHIVE_NAME.sha256"
  install-wesite-deployment-bundle.sh
)
for output_name in "${OUTPUT_FILES[@]}"; do
  staged_output="$STAGING_DIRECTORY/$output_name"
  final_output="$OUTPUT_DIRECTORY/$output_name"
  [[ -f "$staged_output" && ! -L "$staged_output" ]] \
    || fail "staged output is missing or unsafe: $output_name"
  staged_identity="$(stat -c '%d:%i' -- "$staged_output")"
  ln --no-dereference --no-target-directory -- \
    "$staged_output" "$final_output" \
    || fail "refusing to overwrite publication target: $final_output"
  [[ -f "$final_output" && ! -L "$final_output" \
    && "$(stat -c '%d:%i' -- "$final_output")" == "$staged_identity" ]] \
    || fail "published output identity changed: $final_output"
done

printf 'Created %s, %s, and install-wesite-deployment-bundle.sh\n' \
  "$ARCHIVE_NAME" "$ARCHIVE_NAME.sha256"
