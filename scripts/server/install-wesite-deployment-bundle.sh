#!/usr/bin/env bash
set -euo pipefail

PRIVATE_DIRECTORY=''

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [[ -n "$PRIVATE_DIRECTORY" ]]; then
    rm -rf -- "$PRIVATE_DIRECTORY"
  fi
}
trap cleanup EXIT

[[ "$#" -eq 2 ]] \
  || fail 'Usage: install-wesite-deployment-bundle.sh ARCHIVE SIDECAR'

archive_input="$1"
sidecar_input="$2"
[[ -f "$archive_input" && ! -L "$archive_input" ]] \
  || fail 'archive is missing or unsafe'
[[ -f "$sidecar_input" && ! -L "$sidecar_input" ]] \
  || fail 'archive sidecar is missing or unsafe'

ARCHIVE="$(realpath -e -- "$archive_input")"
SIDECAR="$(realpath -e -- "$sidecar_input")"
[[ -f "$ARCHIVE" && ! -L "$ARCHIVE" ]] \
  || fail 'archive does not resolve to a regular file'
[[ -f "$SIDECAR" && ! -L "$SIDECAR" ]] \
  || fail 'archive sidecar does not resolve to a regular file'
archive_basename="$(basename "$ARCHIVE")"
[[ "$(basename "$SIDECAR")" == "$archive_basename.sha256" ]] \
  || fail 'sidecar name does not match the supplied archive'

mapfile -t sidecar_lines < "$SIDECAR"
[[ "${#sidecar_lines[@]}" -eq 1 ]] \
  || fail 'archive sidecar must contain exactly one line'
sidecar_line="${sidecar_lines[0]}"
[[ "${#sidecar_line}" -ge 67 ]] || fail 'archive sidecar line is malformed'
expected_hash="${sidecar_line:0:64}"
sidecar_separator="${sidecar_line:64:2}"
sidecar_archive="${sidecar_line:66}"
[[ "$expected_hash" =~ ^[0-9a-f]{64}$ ]] \
  || fail 'archive sidecar hash is malformed'
[[ "$sidecar_separator" == '  ' ]] \
  || fail 'archive sidecar separator is malformed'
[[ "$sidecar_archive" == "$archive_basename" ]] \
  || fail 'archive sidecar does not name only the supplied archive basename'
sidecar_size="$(wc -c < "$SIDECAR")"
[[ "$sidecar_size" -eq $((${#sidecar_line} + 1)) ]] \
  || fail 'archive sidecar must end with exactly one newline'
archive_hash_line="$(sha256sum -- "$ARCHIVE")"
[[ "${archive_hash_line:0:64}" == "$expected_hash" ]] \
  || fail 'archive checksum verification failed'

umask 077
private_candidate="$(mktemp -d)"
[[ "$private_candidate" == /* && "$private_candidate" != / \
  && -d "$private_candidate" && ! -L "$private_candidate" ]] \
  || fail 'could not create a safe private extraction directory'
PRIVATE_DIRECTORY="$private_candidate"
chmod 0700 "$private_candidate"
[[ "$(realpath -e -- "$private_candidate")" == "$PRIVATE_DIRECTORY" ]] \
  || fail 'private extraction directory is not canonical'

private_archive="$PRIVATE_DIRECTORY/$archive_basename"
cp -- "$ARCHIVE" "$private_archive"
private_hash_line="$(sha256sum -- "$private_archive")"
[[ "${private_hash_line:0:64}" == "$expected_hash" ]] \
  || fail 'archive changed after sidecar verification'

members_file="$PRIVATE_DIRECTORY/archive-members"
verbose_members_file="$PRIVATE_DIRECTORY/archive-members-verbose"
LC_ALL=C tar --list --gzip --absolute-names --quoting-style=escape \
  --file "$private_archive" > "$members_file" \
  || fail 'archive member listing failed'
LC_ALL=C tar --list --verbose --gzip --absolute-names --numeric-owner \
  --quoting-style=escape --file "$private_archive" > "$verbose_members_file" \
  || fail 'archive type listing failed'

member_count=0
root_seen=0
declare -A archive_members=()
while IFS= read -r member || [[ -n "$member" ]]; do
  member_count=$((member_count + 1))
  [[ -n "$member" && "$member" =~ ^[A-Za-z0-9._/-]+$ ]] \
    || fail "unsafe archive member: $member"
  [[ "$member" != /* ]] || fail "absolute archive member: $member"
  normalized_member="${member%/}"
  case "/$normalized_member/" in
    *'/../'*|*'/./'*|*'//'*) fail "unsafe archive member path: $member" ;;
  esac
  [[ "$member" == wesite-deployment/ \
    || "$member" == wesite-deployment/* ]] \
    || fail "archive member is outside wesite-deployment/: $member"
  if [[ "$member" == wesite-deployment/ ]]; then
    root_seen=$((root_seen + 1))
  fi
  [[ -z "${archive_members[$member]+present}" ]] \
    || fail "duplicate archive member: $member"
  archive_members["$member"]=1
done < "$members_file"
(( member_count > 0 && root_seen == 1 )) \
  || fail 'archive must contain exactly one wesite-deployment/ root entry'

verbose_count=0
while IFS= read -r verbose_member || [[ -n "$verbose_member" ]]; do
  verbose_count=$((verbose_count + 1))
  case "${verbose_member:0:1}" in
    -|d) ;;
    *) fail 'archive contains a link or special entry' ;;
  esac
done < "$verbose_members_file"
[[ "$verbose_count" -eq "$member_count" ]] \
  || fail 'archive member and type listings disagree'

EXTRACTION_ROOT="$PRIVATE_DIRECTORY/extracted"
mkdir -m 0700 "$EXTRACTION_ROOT"
tar --extract --gzip --file "$private_archive" --directory "$EXTRACTION_ROOT" \
  --no-same-owner --no-same-permissions \
  || fail 'archive extraction failed'

INSTALLER="$EXTRACTION_ROOT/wesite-deployment/scripts/server/install-wesite-systemd.sh"
[[ -f "$INSTALLER" && ! -L "$INSTALLER" ]] \
  || fail 'bundled systemd installer is missing or unsafe'
[[ "$(realpath -e -- "$INSTALLER")" == "$INSTALLER" ]] \
  || fail 'bundled systemd installer is outside the deployment root'

installer_environment=("PATH=$PATH")
for variable in WESITE_ROOT_PREFIX WESITE_WEB_GROUP \
  WESITE_TEST_ACCOUNT_STATE WESITE_TEST_CALL_LOG WESITE_TEST_QUERY_LOG; do
  if [[ -v "$variable" ]]; then
    installer_environment+=("$variable=${!variable}")
  fi
done
env -i "${installer_environment[@]}" bash "$INSTALLER"
