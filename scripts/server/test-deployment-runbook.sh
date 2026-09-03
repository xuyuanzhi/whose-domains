#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
RUNBOOK="$REPOSITORY_ROOT/deploy/README.md"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

[[ "$(grep -Fc 'sudo -i env -u SUDO_USER -u SUDO_UID -u SUDO_GID' "$RUNBOOK")" -eq 2 ]] \
  || fail 'legacy baselines must use the documented direct-root invocation'
if grep -Fq 'sudo env WESITE_HEALTH_RESPONSE_MODE=legacy-http-200' "$RUNBOOK"; then
  fail 'runbook still documents a legacy override rejected by the deployer'
fi
grep -Fq "sudo stat -c '%U:%G %a %n' /run/lock/wesite" "$RUNBOOK" \
  || fail 'runbook does not verify the root-owned deployment lock directory'

printf 'Deployment runbook tests passed: 1.\n'
