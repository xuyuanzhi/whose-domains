#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
OUTPUT_DIRECTORY=''
OUTPUT_IDENTITY=''
OUTPUT_FD=''
STAGING_DIRECTORY=''
STAGING_BASENAME=''
STAGING_IDENTITY=''
STAGING_FD=''

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  local original_status="$?"
  local cleanup_status=0
  local current_output_identity
  local current_identity
  local fd_root
  local staging_entry

  trap - EXIT
  if [[ -n "$STAGING_DIRECTORY" && -n "$STAGING_IDENTITY" ]]; then
    if [[ -z "$STAGING_FD" ]]; then
      printf 'WARNING: staging cleanup has no bound directory descriptor: %s\n' \
        "$STAGING_DIRECTORY" >&2
      cleanup_status=1
    else
      fd_root="/proc/$$/fd/$STAGING_FD/."
      if [[ ! -d "$fd_root" ]] \
          || ! find -P "$fd_root" -xdev -mindepth 1 -delete; then
        printf 'WARNING: staging cleanup through the bound descriptor failed: %s\n' \
          "$STAGING_DIRECTORY" >&2
        cleanup_status=1
      else
        current_identity="$(stat -Lc '%d:%i' -- "$fd_root" 2>/dev/null)" \
          || current_identity=''
        staging_entry="/proc/$$/fd/$OUTPUT_FD/$STAGING_BASENAME"
        if [[ "$current_identity" != "$STAGING_IDENTITY" ]] \
            || ! rmdir -- "$staging_entry"; then
          printf 'WARNING: staging cleanup root removal failed; preserving path: %s\n' \
            "$STAGING_DIRECTORY" >&2
          cleanup_status=1
        fi
      fi
    fi
  fi
  if [[ -n "$STAGING_FD" ]]; then
    exec {STAGING_FD}<&- || cleanup_status=1
  fi
  if [[ -n "$OUTPUT_FD" ]]; then
    current_output_identity="$(
      stat -Lc '%d:%i' -- "$OUTPUT_DIRECTORY" 2>/dev/null
    )" || current_output_identity=''
    if [[ "$current_output_identity" != "$OUTPUT_IDENTITY" ]]; then
      printf 'WARNING: output directory identity changed; preserving current path: %s\n' \
        "$OUTPUT_DIRECTORY" >&2
      cleanup_status=1
    fi
    exec {OUTPUT_FD}<&- || cleanup_status=1
  fi
  if (( original_status != 0 )); then
    exit "$original_status"
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT

[[ "$#" -eq 2 ]] \
  || fail 'Usage: build-wesite-deployment-bundle.sh COMMIT OUTPUT_DIRECTORY'

SOURCE_COMMIT="$1"
OUTPUT_DIRECTORY="$2"
[[ "$SOURCE_COMMIT" =~ ^[0-9a-f]{7,64}$ ]] \
  || fail 'COMMIT must contain 7 to 64 lowercase hexadecimal characters'
[[ -n "$OUTPUT_DIRECTORY" ]] || fail 'OUTPUT_DIRECTORY must not be empty'
[[ "$(uname -s)" == Linux && -d "/proc/$$/fd" ]] \
  || fail 'building a deployment bundle requires a Linux kernel with /proc directory descriptors; Git Bash/MSYS is not supported'
umask 077

READ_ONLY_PAYLOADS=(
  deploy/config/wesite-admin.application-prod.properties.example
  deploy/config/wesite-web.application-prod.properties.example
  deploy/sudoers/wesite-deploy
  deploy/systemd/wesite-admin.service
  deploy/systemd/wesite-health-monitor.service
  deploy/systemd/wesite-health-monitor.timer
  deploy/systemd/wesite-web.service
  deploy/systemd/wesite.env.example
  deploy/tmpfiles.d/wesite.conf
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
else
  mkdir -- "$OUTPUT_DIRECTORY"
fi
OUTPUT_DIRECTORY="$(cd "$OUTPUT_DIRECTORY" && pwd -P)"
[[ -d "$OUTPUT_DIRECTORY" && ! -L "$OUTPUT_DIRECTORY" ]] \
  || fail 'OUTPUT_DIRECTORY is not a canonical directory'

output_identity_candidate="$(stat -Lc '%d:%i' -- "$OUTPUT_DIRECTORY")"
output_fd_candidate=''
exec {output_fd_candidate}<"$OUTPUT_DIRECTORY"
output_fd_candidate_path="/proc/$$/fd/$output_fd_candidate"
if [[ ! -d "$output_fd_candidate_path" ]] \
    || [[ "$(stat -Lc '%d:%i' -- "$output_fd_candidate_path")" \
      != "$output_identity_candidate" ]] \
    || [[ "$(realpath -e -- "$output_fd_candidate_path")" \
      != "$OUTPUT_DIRECTORY" ]] \
    || [[ "$(stat -Lc '%u' -- "$output_fd_candidate_path")" != "$EUID" ]]; then
  exec {output_fd_candidate}<&-
  fail 'OUTPUT_DIRECTORY could not be bound to a safe EUID-owned descriptor'
fi
shopt -s nullglob dotglob
output_entries=("$output_fd_candidate_path"/*)
shopt -u nullglob dotglob
if [[ "${#output_entries[@]}" -ne 0 ]]; then
  exec {output_fd_candidate}<&-
  fail 'OUTPUT_DIRECTORY must be empty'
fi
chmod 0700 "$output_fd_candidate_path"
if [[ "$(stat -Lc '%a' -- "$output_fd_candidate_path")" != 700 ]]; then
  exec {output_fd_candidate}<&-
  fail 'OUTPUT_DIRECTORY could not be made private'
fi
OUTPUT_IDENTITY="$output_identity_candidate"
OUTPUT_FD="$output_fd_candidate"
output_fd_candidate=''
output_fd_root="/proc/$$/fd/$OUTPUT_FD"

staging_candidate="$(
  mktemp -d "$output_fd_root/.wesite-deployment-build.XXXXXXXXXX"
)"
staging_basename_candidate="$(basename -- "$staging_candidate")"
staging_path_candidate="$output_fd_root/$staging_basename_candidate"
[[ "$staging_candidate" == "$staging_path_candidate" \
  && "$staging_basename_candidate" == .wesite-deployment-build.* \
  && -d "$staging_path_candidate" && ! -L "$staging_path_candidate" ]] \
  || fail 'could not create a safe private staging directory'
staging_identity_candidate="$(stat -Lc '%d:%i' -- "$staging_path_candidate")"
staging_canonical_candidate="$OUTPUT_DIRECTORY/$staging_basename_candidate"
[[ "$(realpath -e -- "$staging_path_candidate")" \
  == "$staging_canonical_candidate" ]] \
  || fail 'private staging directory is not canonical'
staging_fd_candidate=''
exec {staging_fd_candidate}<"$staging_path_candidate"
staging_fd_candidate_path="/proc/$$/fd/$staging_fd_candidate"
if [[ ! -d "$staging_fd_candidate_path" ]] \
    || [[ "$(stat -Lc '%a' -- "$staging_fd_candidate_path")" != 700 ]] \
    || [[ "$(stat -Lc '%d:%i' -- "$staging_fd_candidate_path")" \
      != "$staging_identity_candidate" ]] \
    || [[ "$(realpath -e -- "$staging_fd_candidate_path")" \
      != "$staging_canonical_candidate" ]]; then
  exec {staging_fd_candidate}<&-
  fail 'private staging descriptor does not match its directory'
fi
STAGING_DIRECTORY="$staging_canonical_candidate"
STAGING_BASENAME="$staging_basename_candidate"
STAGING_IDENTITY="$staging_identity_candidate"
STAGING_FD="$staging_fd_candidate"
staging_fd_candidate=''
staging_fd_root="/proc/$$/fd/$STAGING_FD"

BUNDLE_ROOT="$staging_fd_root/wesite-deployment"
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
ARCHIVE_PATH="$staging_fd_root/$ARCHIVE_NAME"
tar --sort=name --format=gnu --mtime='@0' --owner=0 --group=0 \
  --numeric-owner -cf - -C "$staging_fd_root" wesite-deployment \
  | gzip -n -9 > "$ARCHIVE_PATH"
(
  cd "$staging_fd_root"
  sha256sum -- "$ARCHIVE_NAME" > "$ARCHIVE_NAME.sha256"
)
install -m 0755 -- "$BOOTSTRAP_SOURCE" \
  "$staging_fd_root/install-wesite-deployment-bundle.sh"

OUTPUT_FILES=(
  "$ARCHIVE_NAME"
  "$ARCHIVE_NAME.sha256"
  install-wesite-deployment-bundle.sh
)
for output_name in "${OUTPUT_FILES[@]}"; do
  staged_output="$staging_fd_root/$output_name"
  final_output="$output_fd_root/$output_name"
  final_output_display="$OUTPUT_DIRECTORY/$output_name"
  [[ -f "$staged_output" && ! -L "$staged_output" ]] \
    || fail "staged output is missing or unsafe: $output_name"
  staged_identity="$(stat -c '%d:%i' -- "$staged_output")"
  ln --no-dereference --no-target-directory -- \
    "$staged_output" "$final_output" \
    || fail "refusing to overwrite publication target: $final_output_display"
  [[ -f "$final_output" && ! -L "$final_output" \
    && "$(stat -c '%d:%i' -- "$final_output")" == "$staged_identity" ]] \
    || fail "published output identity changed: $final_output_display"
done

printf 'Created %s, %s, and install-wesite-deployment-bundle.sh\n' \
  "$ARCHIVE_NAME" "$ARCHIVE_NAME.sha256"
