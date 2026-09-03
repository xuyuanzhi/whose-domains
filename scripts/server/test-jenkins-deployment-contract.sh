#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WEB_PIPELINE="$REPOSITORY_ROOT/deploy/jenkins/wesite-web.Jenkinsfile"
ADMIN_PIPELINE="$REPOSITORY_ROOT/deploy/jenkins/wesite-admin.Jenkinsfile"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

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

trim_shell_line() {
  local value="$1"
  value="${value%$'\r'}"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

sanitize_shell_body() {
  local body="$1"
  local output="$2"

  awk '
    {
      input = $0
      sub(/\r$/, "", input)
      output = ""
      state = "plain"
      previous = ""
      for (position = 1; position <= length(input); position++) {
        character = substr(input, position, 1)
        if (state == "single") {
          output = output " "
          if (character == sprintf("%c", 39)) state = "plain"
        } else if (state == "double") {
          output = output " "
          if (character == "\\") {
            position++
            if (position <= length(input)) output = output " "
          } else if (character == "\"") {
            state = "plain"
          }
        } else if (character == sprintf("%c", 39)) {
          output = output " "
          state = "single"
        } else if (character == "\"") {
          output = output " "
          state = "double"
        } else if (character == "#" \
          && (previous == "" || previous ~ /[[:space:];&|(){}]/)) {
          break
        } else if (character == "\\") {
          output = output " "
          position++
          if (position <= length(input)) output = output " "
        } else {
          output = output character
        }
        previous = character
      }
      print output
    }
  ' "$body" > "$output"
}

extract_embedded_bash() {
  local file="$1"
  local output="$2"
  local line
  local inside=0
  local blocks=0

  : > "$output"
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if (( inside == 0 )) && [[ "$line" == *"sh '''#!/usr/bin/env bash" ]]; then
      inside=1
      blocks=$((blocks + 1))
      printf '%s\n' '#!/usr/bin/env bash' >> "$output"
    elif (( inside == 1 )) && [[ "$(trim_shell_line "$line")" == "'''" ]]; then
      inside=0
    elif (( inside == 1 )); then
      printf '%s\n' "$line" >> "$output"
    fi
  done < "$file"

  [[ "$blocks" -eq 1 && "$inside" -eq 0 ]] \
    || fail "pipeline must contain exactly one closed literal Bash body"
}

command_invocation_lines() {
  local command_name="$1"
  local body="$2"
  local code_body="$3"
  local boundary='(^|[;&|(){}]|[[:space:]](if|then|elif|while|until|do|!|time)[[:space:]])'
  local wrappers='((command|builtin|env)[[:space:]]+)*'
  local path_prefix='([^[:space:];&|(){}]*/)?'
  local pattern="${boundary}[[:space:]]*${wrappers}${path_prefix}${command_name}([[:space:]]|$)"

  awk -v pattern="$pattern" '
    NR == FNR { raw[NR] = $0; next }
    $0 ~ pattern {
      line = raw[FNR]
      sub(/\r$/, "", line)
      sub(/^[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      print line
    }
  ' "$body" "$code_body"
}

assert_single_exact_assignment() {
  local body="$1"
  local code_body="$2"
  local variable="$3"
  local expected="$4"
  local description="$5"
  local line
  local code
  local trimmed
  local count=0

  exec 3< "$code_body"
  while IFS= read -r line || [[ -n "$line" ]]; do
    IFS= read -r code <&3 || code=''
    if [[ "$code" =~ ^[[:space:]]*${variable}[[:space:]]*(\+)?= ]]; then
      count=$((count + 1))
      trimmed="$(trim_shell_line "$line")"
      [[ "$trimmed" == "$expected" ]] || fail "$description"
    fi
  done < "$body"
  exec 3<&-
  [[ "$count" -eq 1 ]] || fail "$description"
}

assert_pinned_ssh_options() {
  local body="$1"
  local code_body="$2"
  local app="$3"
  local line
  local code
  local trimmed
  local collecting=0
  local count=0
  local option_index=0
  local -a expected_options=(
    'ssh_options=('
    '-i "$SSH_KEY"'
    '-o BatchMode=yes'
    '-o IdentitiesOnly=yes'
    '-o StrictHostKeyChecking=yes'
    '-o "UserKnownHostsFile=$KNOWN_HOSTS"'
    '-o GlobalKnownHostsFile=/dev/null'
    ')'
  )

  exec 3< "$code_body"
  while IFS= read -r line || [[ -n "$line" ]]; do
    IFS= read -r code <&3 || code=''
    trimmed="$(trim_shell_line "$line")"
    if (( collecting == 0 )) && [[ "$code" =~ ^[[:space:]]*ssh_options[[:space:]]*(\+)?= ]]; then
      count=$((count + 1))
      collecting=1
      option_index=0
    fi
    if (( collecting == 1 )); then
      (( option_index < ${#expected_options[@]} )) \
        || fail "$app job has a non-canonical SSH options block"
      [[ "$trimmed" == "${expected_options[option_index]}" ]] \
        || fail "$app job has a non-canonical SSH options block"
      option_index=$((option_index + 1))
      if (( option_index == ${#expected_options[@]} )); then
        collecting=0
      fi
    elif [[ "$code" == *ssh_options* ]]; then
      fail "$app job mutates its pinned SSH options"
    fi
  done < "$body"
  exec 3<&-

  [[ "$count" -eq 1 && "$collecting" -eq 0 ]] \
    || fail "$app job must define exactly one pinned SSH options block"
}

assert_transport_commands() {
  local body="$1"
  local code_body="$2"
  local app="$3"
  local expected_scp='scp "${ssh_options[@]}" "$artifact" "${remote_target}:${remote_jar}"'
  local expected_ssh='ssh "${ssh_options[@]}" "$remote_target" \'
  local -a scp_lines=()
  local -a ssh_lines=()

  mapfile -t scp_lines < <(command_invocation_lines scp "$body" "$code_body")
  [[ "${#scp_lines[@]}" -eq 1 && "${scp_lines[0]}" == "$expected_scp" ]] \
    || fail "$app job must contain exactly one canonical upload command"

  mapfile -t ssh_lines < <(command_invocation_lines ssh "$body" "$code_body")
  [[ "${#ssh_lines[@]}" -eq 3 ]] \
    || fail "$app job must contain exactly three canonical SSH commands"
  local ssh_line
  for ssh_line in "${ssh_lines[@]}"; do
    [[ "$ssh_line" == "$expected_ssh" ]] \
      || fail "$app job has an SSH invocation without the pinned options"
  done
}

assert_sudo_commands() {
  local body="$1"
  local code_body="$2"
  local app="$3"
  local expected_deploy="sudo -n /usr/local/sbin/deploy-wesite-app $app \"\$3\" \"\$1\""
  local expected_check="sudo -n /usr/local/sbin/check-wesite-app $app"
  local -a sudo_lines=()

  mapfile -t sudo_lines < <(command_invocation_lines sudo "$body" "$code_body")
  [[ "${#sudo_lines[@]}" -eq 2 \
    && "${sudo_lines[0]}" == "$expected_deploy" \
    && "${sudo_lines[1]}" == "$expected_check" ]] \
    || fail "$app job has unexpected sudo command structure"
}

assert_no_banned_commands() {
  local body="$1"
  local code_body="$2"
  local app="$3"
  local boundary='(^|[;&|(){}]|[[:space:]](if|then|elif|while|until|do|!|time)[[:space:]])'
  local wrappers='((command|builtin|env)[[:space:]]+)*'
  local path_prefix='([^[:space:];&|(){}]*/)?'
  local banned='(rollback-wesite-app|deploy-wesite-release|install-wesite-systemd|install-wesite-deployment-bundle|bootstrap|systemctl|web-watchdog|nohup|pkill|killall|java|jar|rsync|sftp|git|curl|wget|tar|unzip|cp|mv|install|tee|truncate|chmod|chown|ln|sudoedit|vi|vim|nano|ed|ex|sed|perl|eval)'

  # Keep this as one scan. Spawning one awk/grep process per forbidden command
  # made this contract test needlessly slow on Jenkins agents hosted on Windows.
  if grep -Eq "${boundary}[[:space:]]*${wrappers}${path_prefix}${banned}([[:space:]]|$)" \
    "$code_body"; then
    fail "$app job invokes a forbidden deployment or mutation command"
  fi
}

assert_no_dynamic_command_dispatch() {
  local body="$1"
  local app="$2"
  local boundary='(^|[;&|]|[(){}][[:space:]]|[[:space:]](if|then|elif|while|until|do|!|time)[[:space:]])'
  local assignments='([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]+[[:space:]]+)*'
  local wrappers='((command|builtin|env)[[:space:]]+)*'
  local unsafe_marker='([$]|[\\]|["]|'"'"'|[*]|[?]|[~])'
  local obfuscated_word="[^[:space:];&|(){}=]*${unsafe_marker}"
  local bracket_glob_word='[^][[:space:];&|(){}=]+[[]'

  if grep -Eq "${boundary}[[:space:]]*${assignments}${wrappers}(${obfuscated_word}|${bracket_glob_word})" \
    "$body"; then
    fail "$app job contains dynamic command dispatch"
  fi
}

assert_no_disallowed_redirections() {
  local body="$1"
  local code_body="$2"
  local app="$3"
  local line
  local code
  local trimmed

  exec 3< "$code_body"
  while IFS= read -r line || [[ -n "$line" ]]; do
    IFS= read -r code <&3 || code=''
    [[ "$code" == *'<'* || "$code" == *'>'* ]] || continue
    trimmed="$(trim_shell_line "$line")"
    case "$trimmed" in
      "bash -s -- \"\$remote_directory\" <<'REMOTE_PREPARE'"|\
      "bash -s -- \"\$remote_jar\" \"\$remote_directory\" <<'REMOTE_UPLOAD_CLEANUP'"|\
      "bash -s -- \"\$remote_jar\" \"\$remote_directory\" \"\$version\" <<'REMOTE_DEPLOY'")
        ;;
      *)
        if [[ "$code" =~ ^[[:space:]]*printf[[:space:]]+\ *\>\&2[[:space:]]*$ ]]; then
          continue
        fi
        fail "$app job contains a non-canonical shell redirection"
        ;;
    esac
  done < "$body"
  exec 3<&-
}

assert_command_substitution_contract() {
  local body="$1"
  local app="$2"
  local substitution_count

  substitution_count="$(grep -Fo '$(' "$body" | wc -l | tr -d '[:space:]')"
  [[ "$substitution_count" -eq 1 ]] \
    || fail "$app job contains unexpected command substitution"
  [[ "$(grep -Fxc '[[ "$(stat -c '\''%a'\'' -- "$1")" == 700 ]]' "$body")" -eq 1 ]] \
    || fail "$app job does not limit command substitution to the permission check"
  if grep -Fq '`' "$body"; then
    fail "$app job contains forbidden backtick command substitution"
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
  local body="$3"
  local code_body="$4"

  require_literal "$file" 'disableConcurrentBuilds()' \
    "$app job does not disable concurrent builds"
  require_literal "$file" "credentialsId: 'whose-domains-prod-ssh'" \
    "$app job does not bind the production SSH credential"
  require_literal "$file" "credentialsId: 'whose-domains-prod-known-hosts'" \
    "$app job does not bind the pinned known-hosts credential"
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

  assert_single_exact_assignment "$body" "$code_body" version \
    'version="${GIT_COMMIT}-${BUILD_NUMBER}"' \
    "$app job has a non-canonical version assignment"
  assert_single_exact_assignment "$body" "$code_body" remote_target \
    "remote_target='wesite-deploy@47.76.125.96'" \
    "$app job does not use exactly one fixed non-root SSH target"
  assert_single_exact_assignment "$body" "$code_body" remote_directory \
    "remote_directory=\"/var/lib/wesite-deploy/incoming/${app}-\${version}\"" \
    "$app job has a non-canonical remote staging directory"
  assert_single_exact_assignment "$body" "$code_body" remote_jar \
    "remote_jar=\"\${remote_directory}/wesite-${app}-1.0.0.jar\"" \
    "$app job has a non-canonical remote JAR path"
  assert_pinned_ssh_options "$body" "$code_body" "$app"
  assert_transport_commands "$body" "$code_body" "$app"
  assert_sudo_commands "$body" "$code_body" "$app"
  assert_no_banned_commands "$body" "$code_body" "$app"
  assert_no_dynamic_command_dispatch "$body" "$app"
  assert_no_disallowed_redirections "$body" "$code_body" "$app"
  assert_command_substitution_contract "$body" "$app"

  local line
  local code
  while IFS= read -r code || [[ -n "$code" ]]; do
    [[ "$code" != *BRANCH_NAME* ]] \
      || fail "$app job lets an untrusted branch name influence remote syntax"
    [[ "$code" != *GIT_BRANCH* ]] \
      || fail "$app job lets an untrusted branch name influence remote syntax"
    [[ ! "$code" =~ (^|[[:space:]])(bash|sh)[[:space:]]+-c([[:space:]]|$) ]] \
      || fail "$app job invokes an uninspectable nested shell command"
  done < "$code_body"

  reject_literal "$file" 'sh """' \
    "$app job uses a Groovy-interpolated shell body"
}

assert_pipeline_contract() {
  local file="$1"
  local app="$2"
  local label="$3"
  local module="$4"
  local artifact="$5"
  local other_module="$6"
  local other_app="$7"
  local body
  local code_body

  require_file "$file"
  body="$(mktemp "$TEST_ROOT/${app}.body.XXXXXX")"
  extract_embedded_bash "$file" "$body"
  code_body="${body}.code"
  sanitize_shell_body "$body" "$code_body"
  assert_shared_contract "$file" "$app" "$body" "$code_body"
  require_literal "$file" "mvn -B -pl $module -am clean verify" \
    "$label job does not build only its reactor target"
  assert_single_exact_assignment "$body" "$code_body" artifact "artifact='$artifact'" \
    "$label job does not select exactly one canonical artifact"

  local line
  local code
  while IFS= read -r code || [[ -n "$code" ]]; do
    [[ "$code" != *"$other_module/target/"* ]] \
      || fail "$label job references the other application artifact"
    [[ "$code" != *"deploy-wesite-app $other_app"* ]] \
      || fail "$label job can deploy the other application"
    [[ "$code" != *"check-wesite-app $other_app"* ]] \
      || fail "$label job can make the other application gate its release"
  done < "$code_body"
}

replace_exact_line() {
  local source="$1"
  local destination="$2"
  local before="$3"
  local after="$4"
  local replacements=0
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == "$before" ]]; then
      printf '%s\n' "$after"
      replacements=$((replacements + 1))
    else
      printf '%s\n' "$line"
    fi
  done < "$source" > "$destination"
  [[ "$replacements" -eq 1 ]] || fail "fixture line replacement count was $replacements"
}

add_documentation_fixture() {
  local source="$1"
  local destination="$2"
  local inserted=0
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line"
    if [[ "$inserted" -eq 0 && "$line" == 'set -euo pipefail' ]]; then
      printf '%s\n' \
        '# Documentation only: sudo rollback-wesite-app; scp pom.xml; systemctl.' \
        "documentation='StrictHostKeyChecking=no java -jar deploy/ README.md bootstrap'" \
        "printf '%s\\n' 'Documentation only: sudo rollback-wesite-app; scp pom.xml'"
      inserted=1
    fi
  done < "$source" > "$destination"
  [[ "$inserted" -eq 1 ]] || fail 'documentation fixture insertion point was missing'
}

add_command_substitution_fixture() {
  local source="$1"
  local destination="$2"
  local inserted=0
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line"
    if [[ "$inserted" -eq 0 && "$line" == 'set -euo pipefail' ]]; then
      printf '%s\n' 'attack="$(sudo -n /usr/local/sbin/rollback-wesite-app web)"'
      inserted=1
    fi
  done < "$source" > "$destination"
  [[ "$inserted" -eq 1 ]] || fail 'command-substitution fixture insertion point was missing'
}

add_dynamic_command_fixture() {
  local source="$1"
  local destination="$2"
  local inserted=0
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line"
    if [[ "$inserted" -eq 0 && "$line" == 'set -euo pipefail' ]]; then
      printf '%s\n' 'command_name=sudo' \
        '"$command_name" -n /usr/local/sbin/rollback-wesite-app web'
      inserted=1
    fi
  done < "$source" > "$destination"
  [[ "$inserted" -eq 1 ]] || fail 'dynamic-command fixture insertion point was missing'
}

add_positional_command_fixture() {
  local source="$1"
  local destination="$2"
  local inserted=0
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line"
    if [[ "$inserted" -eq 0 && "$line" == 'set -euo pipefail' ]]; then
      printf '%s\n' 'set -- sudo' \
        '"$1" -n /usr/local/sbin/rollback-wesite-app web'
      inserted=1
    fi
  done < "$source" > "$destination"
  [[ "$inserted" -eq 1 ]] || fail 'positional-command fixture insertion point was missing'
}

add_obfuscated_command_fixture() {
  local source="$1"
  local destination="$2"
  local command_line="$3"
  local inserted=0
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line"
    if [[ "$inserted" -eq 0 && "$line" == 'set -euo pipefail' ]]; then
      printf '%s\n' "$command_line"
      inserted=1
    fi
  done < "$source" > "$destination"
  [[ "$inserted" -eq 1 ]] || fail 'obfuscated-command fixture insertion point was missing'
}

CONTRACT_FAILURES=0

expect_contract_rejection() {
  local name="$1"
  local expected="$2"
  shift 2
  local output="$TEST_ROOT/$name.output"

  if ("$@") > "$output" 2>&1; then
    printf 'MUTATION ACCEPTED: %s\n' "$name" >&2
    CONTRACT_FAILURES=$((CONTRACT_FAILURES + 1))
  elif ! grep -Fq -- "$expected" "$output"; then
    printf 'MUTATION FAILED FOR WRONG REASON: %s\n' "$name" >&2
    cat "$output" >&2
    CONTRACT_FAILURES=$((CONTRACT_FAILURES + 1))
  fi
}

expect_contract_acceptance() {
  local name="$1"
  shift
  local output="$TEST_ROOT/$name.output"

  if ! ("$@") > "$output" 2>&1; then
    printf 'SAFE FIXTURE REJECTED: %s\n' "$name" >&2
    cat "$output" >&2
    CONTRACT_FAILURES=$((CONTRACT_FAILURES + 1))
  fi
}

assert_pipeline_contract "$WEB_PIPELINE" web Web wesite-web \
  wesite-web/target/wesite-web-1.0.0.jar wesite-admin admin
assert_pipeline_contract "$ADMIN_PIPELINE" admin Admin wesite-admin \
  wesite-admin/target/wesite-admin-1.0.0.jar wesite-web web

EXTRA_UPLOAD="$TEST_ROOT/web-extra-upload.Jenkinsfile"
replace_exact_line "$WEB_PIPELINE" "$EXTRA_UPLOAD" \
  'scp "${ssh_options[@]}" "$artifact" "${remote_target}:${remote_jar}"' \
  'scp "${ssh_options[@]}" "$artifact" "${remote_target}:${remote_jar}"; scp "${ssh_options[@]}" pom.xml "${remote_target}:${remote_directory}/pom.xml"'
EXTRA_UPLOAD_BODY="$TEST_ROOT/web-extra-upload.body"
EXTRA_UPLOAD_CODE="$TEST_ROOT/web-extra-upload.code"
extract_embedded_bash "$EXTRA_UPLOAD" "$EXTRA_UPLOAD_BODY"
sanitize_shell_body "$EXTRA_UPLOAD_BODY" "$EXTRA_UPLOAD_CODE"
expect_contract_rejection extra-upload 'web job must contain exactly one canonical upload command' \
  assert_transport_commands "$EXTRA_UPLOAD_BODY" "$EXTRA_UPLOAD_CODE" web

CHAINED_ROLLBACK="$TEST_ROOT/web-chained-rollback.Jenkinsfile"
replace_exact_line "$WEB_PIPELINE" "$CHAINED_ROLLBACK" \
  'sudo -n /usr/local/sbin/deploy-wesite-app web "$3" "$1"' \
  'sudo -n /usr/local/sbin/deploy-wesite-app web "$3" "$1"; sudo -n /usr/local/sbin/rollback-wesite-app web'
CHAINED_ROLLBACK_BODY="$TEST_ROOT/web-chained-rollback.body"
CHAINED_ROLLBACK_CODE="$TEST_ROOT/web-chained-rollback.code"
extract_embedded_bash "$CHAINED_ROLLBACK" "$CHAINED_ROLLBACK_BODY"
sanitize_shell_body "$CHAINED_ROLLBACK_BODY" "$CHAINED_ROLLBACK_CODE"
expect_contract_rejection chained-rollback 'web job has unexpected sudo command structure' \
  assert_sudo_commands "$CHAINED_ROLLBACK_BODY" "$CHAINED_ROLLBACK_CODE" web

COMMAND_SUBSTITUTION="$TEST_ROOT/web-command-substitution.Jenkinsfile"
add_command_substitution_fixture "$WEB_PIPELINE" "$COMMAND_SUBSTITUTION"
COMMAND_SUBSTITUTION_BODY="$TEST_ROOT/web-command-substitution.body"
extract_embedded_bash "$COMMAND_SUBSTITUTION" "$COMMAND_SUBSTITUTION_BODY"
expect_contract_rejection command-substitution \
  'web job contains unexpected command substitution' \
  assert_command_substitution_contract "$COMMAND_SUBSTITUTION_BODY" web

DYNAMIC_COMMAND="$TEST_ROOT/web-dynamic-command.Jenkinsfile"
add_dynamic_command_fixture "$WEB_PIPELINE" "$DYNAMIC_COMMAND"
DYNAMIC_COMMAND_BODY="$TEST_ROOT/web-dynamic-command.body"
extract_embedded_bash "$DYNAMIC_COMMAND" "$DYNAMIC_COMMAND_BODY"
expect_contract_rejection dynamic-command \
  'web job contains dynamic command dispatch' \
  assert_no_dynamic_command_dispatch "$DYNAMIC_COMMAND_BODY" web

POSITIONAL_COMMAND="$TEST_ROOT/web-positional-command.Jenkinsfile"
add_positional_command_fixture "$WEB_PIPELINE" "$POSITIONAL_COMMAND"
POSITIONAL_COMMAND_BODY="$TEST_ROOT/web-positional-command.body"
extract_embedded_bash "$POSITIONAL_COMMAND" "$POSITIONAL_COMMAND_BODY"
expect_contract_rejection positional-command \
  'web job contains dynamic command dispatch' \
  assert_no_dynamic_command_dispatch "$POSITIONAL_COMMAND_BODY" web

ESCAPED_COMMAND="$TEST_ROOT/web-escaped-command.Jenkinsfile"
add_obfuscated_command_fixture "$WEB_PIPELINE" "$ESCAPED_COMMAND" \
  's\udo -n /usr/local/sbin/rollback-wesite-app web'
ESCAPED_COMMAND_BODY="$TEST_ROOT/web-escaped-command.body"
extract_embedded_bash "$ESCAPED_COMMAND" "$ESCAPED_COMMAND_BODY"
expect_contract_rejection escaped-command \
  'web job contains dynamic command dispatch' \
  assert_no_dynamic_command_dispatch "$ESCAPED_COMMAND_BODY" web

EMPTY_QUOTE_COMMAND="$TEST_ROOT/web-empty-quote-command.Jenkinsfile"
add_obfuscated_command_fixture "$WEB_PIPELINE" "$EMPTY_QUOTE_COMMAND" \
  "s''udo -n /usr/local/sbin/rollback-wesite-app web"
EMPTY_QUOTE_COMMAND_BODY="$TEST_ROOT/web-empty-quote-command.body"
extract_embedded_bash "$EMPTY_QUOTE_COMMAND" "$EMPTY_QUOTE_COMMAND_BODY"
expect_contract_rejection empty-quote-command \
  'web job contains dynamic command dispatch' \
  assert_no_dynamic_command_dispatch "$EMPTY_QUOTE_COMMAND_BODY" web

QUOTED_COMMAND="$TEST_ROOT/web-quoted-command.Jenkinsfile"
add_obfuscated_command_fixture "$WEB_PIPELINE" "$QUOTED_COMMAND" \
  "'sudo' -n /usr/local/sbin/rollback-wesite-app web"
QUOTED_COMMAND_BODY="$TEST_ROOT/web-quoted-command.body"
extract_embedded_bash "$QUOTED_COMMAND" "$QUOTED_COMMAND_BODY"
expect_contract_rejection quoted-command \
  'web job contains dynamic command dispatch' \
  assert_no_dynamic_command_dispatch "$QUOTED_COMMAND_BODY" web

ANSI_COMMAND="$TEST_ROOT/web-ansi-command.Jenkinsfile"
add_obfuscated_command_fixture "$WEB_PIPELINE" "$ANSI_COMMAND" \
  "\$'sudo' -n /usr/local/sbin/rollback-wesite-app web"
ANSI_COMMAND_BODY="$TEST_ROOT/web-ansi-command.body"
extract_embedded_bash "$ANSI_COMMAND" "$ANSI_COMMAND_BODY"
expect_contract_rejection ansi-command \
  'web job contains dynamic command dispatch' \
  assert_no_dynamic_command_dispatch "$ANSI_COMMAND_BODY" web

GLOB_COMMAND="$TEST_ROOT/web-glob-command.Jenkinsfile"
add_obfuscated_command_fixture "$WEB_PIPELINE" "$GLOB_COMMAND" \
  '/usr/bin/su[d]o -n /usr/local/sbin/rollback-wesite-app web'
GLOB_COMMAND_BODY="$TEST_ROOT/web-glob-command.body"
extract_embedded_bash "$GLOB_COMMAND" "$GLOB_COMMAND_BODY"
expect_contract_rejection glob-command \
  'web job contains dynamic command dispatch' \
  assert_no_dynamic_command_dispatch "$GLOB_COMMAND_BODY" web

DOCUMENTATION="$TEST_ROOT/web-documentation.Jenkinsfile"
add_documentation_fixture "$WEB_PIPELINE" "$DOCUMENTATION"
DOCUMENTATION_BODY="$TEST_ROOT/web-documentation.body"
DOCUMENTATION_CODE="$TEST_ROOT/web-documentation.code"
extract_embedded_bash "$DOCUMENTATION" "$DOCUMENTATION_BODY"
sanitize_shell_body "$DOCUMENTATION_BODY" "$DOCUMENTATION_CODE"
expect_contract_acceptance documentation-text \
  assert_transport_commands "$DOCUMENTATION_BODY" "$DOCUMENTATION_CODE" web
expect_contract_acceptance documentation-commands \
  assert_sudo_commands "$DOCUMENTATION_BODY" "$DOCUMENTATION_CODE" web
expect_contract_acceptance documentation-banned-words \
  assert_no_banned_commands "$DOCUMENTATION_BODY" "$DOCUMENTATION_CODE" web

(( CONTRACT_FAILURES == 0 )) \
  || fail "$CONTRACT_FAILURES Jenkins contract fixture checks failed"

printf 'Jenkins deployment contract tests passed: Web and Admin jobs are independent.\n'
