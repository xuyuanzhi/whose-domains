#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
RUNBOOK="$REPOSITORY_ROOT/deploy/README.md"
WEB_PIPELINE="$REPOSITORY_ROOT/deploy/jenkins/wesite-web.Jenkinsfile"
ADMIN_PIPELINE="$REPOSITORY_ROOT/deploy/jenkins/wesite-admin.Jenkinsfile"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

require_literal() {
  local file="$1"
  local literal="$2"
  local message="$3"

  grep -Fq -- "$literal" "$file" || fail "$message"
}

require_section_marker() {
  local heading="$1"
  local marker="$2"

  awk -v heading="$heading" -v marker="$marker" '
    $0 == heading { in_section = 1; next }
    in_section && /^#{1,3} / { exit 1 }
    in_section && index($0, marker) { found = 1; exit 0 }
    END { if (!found) exit 1 }
  ' "$RUNBOOK" || fail "$heading does not identify its execution environment"
}

[[ "$(grep -Fc 'sudo -i env -u SUDO_USER -u SUDO_UID -u SUDO_GID' "$RUNBOOK")" -eq 2 ]] \
  || fail 'legacy baselines must use the documented direct-root invocation'
if grep -Fq 'sudo env WESITE_HEALTH_RESPONSE_MODE=legacy-http-200' "$RUNBOOK"; then
  fail 'runbook still documents a legacy override rejected by the deployer'
fi
grep -Fq "sudo stat -c '%U:%G %a %n' /run/lock/wesite" "$RUNBOOK" \
  || fail 'runbook does not verify the root-owned deployment lock directory'

if grep -Eq '47[.]76[.]125[.]96' "$RUNBOOK" "$WEB_PIPELINE" "$ADMIN_PIPELINE"; then
  fail 'deployment documentation still contains the retired public IP'
fi
require_literal "$WEB_PIPELINE" \
  "remote_target='wesite-deploy@hk.tail5ed8be.ts.net'" \
  'Web Pipeline does not use the fixed Tailnet target'
require_literal "$ADMIN_PIPELINE" \
  "remote_target='wesite-deploy@hk.tail5ed8be.ts.net'" \
  'Admin Pipeline does not use the fixed Tailnet target'
require_literal "$RUNBOOK" 'git status --porcelain' \
  'runbook does not require a clean source worktree'
require_literal "$RUNBOOK" 'git worktree add --detach' \
  'runbook does not build infrastructure from a detached clean worktree'
require_literal "$RUNBOOK" \
  '[[ "$(git rev-parse --verify '\''HEAD^{commit}'\'')" == "$INFRA_COMMIT" ]]' \
  'runbook does not require HEAD to equal the selected origin/main commit'
require_literal "$RUNBOOK" \
  '"$INFRA_SOURCE/scripts/build-wesite-deployment-bundle.sh" \' \
  'runbook does not execute the builder from the detached source worktree'
require_literal "$RUNBOOK" '-o BatchMode=yes' \
  'runbook does not test non-interactive Jenkins SSH authentication'
require_literal "$RUNBOOK" '-o StrictHostKeyChecking=yes' \
  'runbook does not test Jenkins SSH with strict host-key verification'
require_literal "$RUNBOOK" 'test -s "$KNOWN_HOSTS"' \
  'runbook does not reject an empty pinned known-hosts credential'
require_literal "$RUNBOOK" '-o GlobalKnownHostsFile=/dev/null' \
  'runbook lets global SSH host keys bypass the pinned credential'
require_literal "$RUNBOOK" 'test "$(id -un)" = wesite-deploy' \
  'runbook does not verify the remote Jenkins deployment identity'
require_literal "$RUNBOOK" \
  '$KeyDirectory = Join-Path $env:USERPROFILE ".ssh\whose-domains-jenkins"' \
  'runbook does not place the private key in a profile directory outside the repository'
require_literal "$RUNBOOK" 'if (Test-Path $KeyDirectory) {' \
  'runbook does not reject reuse of the private-key directory'
require_literal "$RUNBOOK" 'ssh-keygen -t ed25519 -N ""' \
  'runbook does not define the PowerShell 7.3+ empty-passphrase contract'
require_literal "$RUNBOOK" "ssh-keygen -t ed25519 -N '\"\"'" \
  'runbook does not define the Windows PowerShell 5.1 empty-passphrase contract'
require_literal "$RUNBOOK" \
  'if ($PSVersionTable.PSVersion -lt [version]"7.3") {' \
  'runbook does not select native argument handling by PowerShell version'
require_literal "$RUNBOOK" '$PSNativeCommandArgumentPassing = "Standard"' \
  'runbook does not enforce standard native argument handling on PowerShell 7.3+'
require_literal "$RUNBOOK" 'Assert-NativeSuccess "ssh-keygen"' \
  'runbook does not stop when native key generation fails'
require_literal "$RUNBOOK" 'Assert-NativeSuccess "scp"' \
  'runbook does not stop when public-key upload fails'

require_section_marker '### 7.4 执行首次新版本发布' \
  '**执行位置：本地电脑的 Jenkins UI 发起；实际步骤在 Jenkins 容器执行**'
require_section_marker '### 9.3 维护期间暂停自动恢复' \
  '**执行位置：生产服务器**'
require_section_marker '### 10.1 手动回滚 Web' \
  '**执行位置：生产服务器**'
require_section_marker '### 10.2 手动回滚 Admin' \
  '**执行位置：生产服务器**'
require_section_marker '### 10.3 查看日志' \
  '**执行位置：生产服务器**'
require_section_marker '## 11. 清理旧版本' \
  '**执行位置：生产服务器**'
require_section_marker '### 12.3 执行内容净化' \
  '**执行位置：生产服务器**'

printf 'Deployment runbook tests passed.\n'
