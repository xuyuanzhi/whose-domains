#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WEB_PIPELINE="$REPOSITORY_ROOT/deploy/jenkins/wesite-web.Jenkinsfile"
ADMIN_PIPELINE="$REPOSITORY_ROOT/deploy/jenkins/wesite-admin.Jenkinsfile"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

require_file() {
  local file="$1"
  [[ -f "$file" ]] || fail "missing Jenkins definition: ${file#"$REPOSITORY_ROOT/"}"
}

require_literal() {
  local file="$1"
  local literal="$2"
  local description="$3"
  grep -Fq -- "$literal" "$file" || fail "$description"
}

reject_literal() {
  local file="$1"
  local literal="$2"
  local description="$3"
  if grep -Fiq -- "$literal" "$file"; then
    fail "$description"
  fi
}

require_order() {
  local file="$1"
  local before="$2"
  local after="$3"
  local description="$4"
  local before_line
  local after_line
  before_line="$(grep -Fn -- "$before" "$file" | tail -n 1 | cut -d: -f1)"
  after_line="$(grep -Fn -- "$after" "$file" | tail -n 1 | cut -d: -f1)"
  [[ -n "$before_line" && -n "$after_line" && "$before_line" -lt "$after_line" ]] \
    || fail "$description"
}

assert_shared_contract() {
  local file="$1"
  local app="$2"

  require_literal "$file" 'disableConcurrentBuilds()' \
    "$app job does not disable concurrent builds"
  require_literal "$file" "credentialsId: 'whose-domains-prod-ssh'" \
    "$app job does not bind the production SSH credential"
  require_literal "$file" "credentialsId: 'whose-domains-prod-known-hosts'" \
    "$app job does not bind the pinned known-hosts credential"
  require_literal "$file" "remote_target='wesite-deploy@47.76.125.96'" \
    "$app job does not use the fixed non-root SSH target"
  require_literal "$file" 'StrictHostKeyChecking=yes' \
    "$app job does not require strict host-key verification"
  require_literal "$file" 'UserKnownHostsFile=$KNOWN_HOSTS' \
    "$app job does not use the Jenkins-pinned known-hosts file"
  require_literal "$file" 'GlobalKnownHostsFile=/dev/null' \
    "$app job can fall back to an unpinned global known-hosts file"

  require_literal "$file" '[[ "$GIT_COMMIT" =~ ^[0-9a-f]{7,64}$ ]]' \
    "$app job does not validate GIT_COMMIT as lowercase hexadecimal"
  require_literal "$file" '[[ "$BUILD_NUMBER" =~ ^[0-9]+$ ]]' \
    "$app job does not validate BUILD_NUMBER as numeric"
  require_literal "$file" 'version="${GIT_COMMIT}-${BUILD_NUMBER}"' \
    "$app job does not compose its version only from validated values"
  require_literal "$file" "remote_directory=\"/var/lib/wesite-deploy/incoming/${app}-\${version}\"" \
    "$app job does not use an app/commit/build-specific incoming directory"
  require_literal "$file" 'mkdir -m 0700 -- "$1"' \
    "$app job does not create a private remote staging directory"
  require_literal "$file" \
    'scp "${ssh_options[@]}" "$artifact" "${remote_target}:${remote_jar}"' \
    "$app job does not upload exactly its selected artifact"
  require_literal "$file" "sudo -n /usr/local/sbin/deploy-wesite-app $app" \
    "$app job does not invoke its non-interactive single-app deploy command"
  require_literal "$file" "sudo -n /usr/local/sbin/check-wesite-app $app" \
    "$app job does not invoke its non-interactive single-app check command"
  require_literal "$file" 'deploy_status=$?' \
    "$app job does not save deploy status"
  require_literal "$file" 'check_status=$?' \
    "$app job does not save check status"
  require_literal "$file" 'rm -f -- "$1"' \
    "$app job does not remove the exact uploaded JAR"
  require_literal "$file" 'rmdir -- "$2"' \
    "$app job does not remove only the empty job directory"
  require_literal "$file" 'exit "$release_status"' \
    "$app job does not return the saved deploy/check status"
  require_literal "$file" "<<'REMOTE_DEPLOY'" \
    "$app remote deployment body is not protected from local interpolation"

  require_order "$file" 'deploy_status=$?' 'rm -f -- "$1"' \
    "$app job cleans up before saving deploy status"
  require_order "$file" 'check_status=$?' 'rm -f -- "$1"' \
    "$app job cleans up before saving check status"
  require_order "$file" 'rm -f -- "$1"' 'rmdir -- "$2"' \
    "$app job does not remove the uploaded file before its exact directory"
  require_order "$file" 'rmdir -- "$2"' 'exit "$release_status"' \
    "$app job does not return the original status after cleanup"
  require_order "$file" 'mkdir -m 0700 -- "$1"' \
    'scp "${ssh_options[@]}" "$artifact" "${remote_target}:${remote_jar}"' \
    "$app job uploads before creating its private staging directory"

  [[ "$(grep -Ec '^[[:space:]]*scp ' "$file")" -eq 1 ]] \
    || fail "$app job must contain exactly one upload command"
  [[ "$(grep -Ec '^[[:space:]]*sudo ' "$file")" -eq 2 ]] \
    || fail "$app job must contain only its deploy and check sudo commands"

  reject_literal "$file" 'StrictHostKeyChecking=no' \
    "$app job disables SSH host-key verification"
  reject_literal "$file" 'root@' "$app job connects as root"
  reject_literal "$file" 'deploy-wesite-release' \
    "$app job invokes the retired paired deployment command"
  reject_literal "$file" 'systemctl' "$app job invokes systemctl directly"
  reject_literal "$file" 'nohup' "$app job starts a detached process"
  reject_literal "$file" 'pkill' "$app job kills processes directly"
  reject_literal "$file" 'killall' "$app job kills processes directly"
  reject_literal "$file" 'web-watchdog' "$app job invokes the retired watchdog"
  reject_literal "$file" 'BRANCH_NAME' \
    "$app job lets an untrusted branch name influence remote syntax"
  reject_literal "$file" 'GIT_BRANCH' \
    "$app job lets an untrusted branch name influence remote syntax"
  reject_literal "$file" 'sh """' \
    "$app job uses a Groovy-interpolated shell body"
}

require_file "$WEB_PIPELINE"
require_file "$ADMIN_PIPELINE"

assert_shared_contract "$WEB_PIPELINE" web
require_literal "$WEB_PIPELINE" 'mvn -B -pl wesite-web -am clean verify' \
  'Web job does not build only the Web reactor target'
require_literal "$WEB_PIPELINE" \
  "artifact='wesite-web/target/wesite-web-1.0.0.jar'" \
  'Web job does not select the Web artifact'
reject_literal "$WEB_PIPELINE" 'wesite-admin/target/' \
  'Web job references the Admin artifact'
reject_literal "$WEB_PIPELINE" 'deploy-wesite-app admin' \
  'Web job can deploy Admin'
reject_literal "$WEB_PIPELINE" 'check-wesite-app admin' \
  'Web job can make Admin health gate the Web release'

assert_shared_contract "$ADMIN_PIPELINE" admin
require_literal "$ADMIN_PIPELINE" 'mvn -B -pl wesite-admin -am clean verify' \
  'Admin job does not build only the Admin reactor target'
require_literal "$ADMIN_PIPELINE" \
  "artifact='wesite-admin/target/wesite-admin-1.0.0.jar'" \
  'Admin job does not select the Admin artifact'
reject_literal "$ADMIN_PIPELINE" 'wesite-web/target/' \
  'Admin job references the Web artifact'
reject_literal "$ADMIN_PIPELINE" 'deploy-wesite-app web' \
  'Admin job can deploy Web'
reject_literal "$ADMIN_PIPELINE" 'check-wesite-app web' \
  'Admin job can make Web health gate the Admin release'

printf 'Jenkins deployment contract tests passed: Web and Admin jobs are independent.\n'
