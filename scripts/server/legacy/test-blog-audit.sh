#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/config"
# Git Bash lacks Linux flock; these tests cover the audit result contract, not locking.
if ! command -v flock >/dev/null 2>&1; then
  mkdir -p "$fixture/bin"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$fixture/bin/flock"
  chmod +x "$fixture/bin/flock"
  export PATH="$fixture/bin:$PATH"
fi
touch "$fixture/admin.jar"
cat > "$fixture/java" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$MOCK_ARGS"
if [[ "${MOCK_EXPECT_ENV:-}" == 1 ]]; then
  [[ "${JWT_SECRET:-}" == shared-test-secret && "${DB_PASSWORD:-}" == 'test with $ literal' ]] || exit 9
fi
case "$MOCK_MODE" in
  success) echo 'INFO Blog content audit completed: {"scanned":0,"findings":[]}' ;;
  fail) echo 'startup failure'; exit 7 ;;
  missing) echo 'no completion record' ;;
esac
MOCK
chmod +x "$fixture/java"
cat > "$fixture/jar" <<'MOCK'
#!/usr/bin/env bash
[[ "$1" == tf && "$2" == "$BLOG_AUDIT_JAR" ]] || exit 2
[[ "${MOCK_JAR_MODE:-current}" != corrupt ]] || exit 1
echo 'BOOT-INF/classes/info/wesite/admin/config/AdminSchedulingConfiguration.class'
if [[ "${MOCK_JAR_MODE:-current}" != old ]]; then
  echo 'BOOT-INF/classes/info/wesite/admin/maintenance/BlogAuditRunner.class'
fi
MOCK
chmod +x "$fixture/jar"
export JWT_SECRET=test-only BLOG_AUDIT_JAVA="$fixture/java" BLOG_AUDIT_JAR="$fixture/admin.jar"
export BLOG_AUDIT_CONFIG_DIR="$fixture/config" MOCK_ARGS="$fixture/args"
export BLOG_AUDIT_REPORT_ROOT="$fixture/success" MOCK_MODE=success
bash "$SCRIPT_DIR/blog-audit.sh"
grep -qxF -- '--spring.profiles.active=prod,blog-audit' "$MOCK_ARGS"
grep -qxF -- '--spring.main.web-application-type=none' "$MOCK_ARGS"
grep -qxF -- '--wesite.blog.audit.enabled=true' "$MOCK_ARGS"
grep -qxF -- "--spring.config.additional-location=file:$fixture/config/" "$MOCK_ARGS"
grep -qxF '{"scanned":0,"findings":[]}' "$fixture"/success/run-*/report.json

export BLOG_AUDIT_REPORT_ROOT="$fixture/fail" MOCK_MODE=fail
result=0
bash "$SCRIPT_DIR/blog-audit.sh" || result=$?
[[ "$result" == 7 ]]
[[ -z "$(find "$fixture/fail" -name report.json -print)" ]]

export BLOG_AUDIT_REPORT_ROOT="$fixture/missing" MOCK_MODE=missing
if bash "$SCRIPT_DIR/blog-audit.sh"; then echo 'Missing completion must fail.' >&2; exit 1; fi
[[ -z "$(find "$fixture/missing" -name report.json -print)" ]]

unset JWT_SECRET
if bash "$SCRIPT_DIR/blog-audit.sh" </dev/null; then echo 'Missing secret must fail.' >&2; exit 1; fi
cat > "$fixture/config/runtime.env" <<'ENV'
JWT_SECRET='shared-test-secret'
DB_PASSWORD='test with $ literal'
ENV
export BLOG_AUDIT_REPORT_ROOT="$fixture/shared" MOCK_MODE=success MOCK_EXPECT_ENV=1
bash "$SCRIPT_DIR/blog-audit.sh" </dev/null > "$fixture/shared-output" 2>&1
grep -qxF '{"scanned":0,"findings":[]}' "$fixture"/shared/run-*/report.json
if grep -qF 'shared-test-secret' "$fixture/shared-output"; then echo 'Secret leaked.' >&2; exit 1; fi
export MOCK_ARGS="$fixture/should-not-launch"
for MOCK_JAR_MODE in old corrupt; do
  export MOCK_JAR_MODE
  if bash "$SCRIPT_DIR/blog-audit.sh" </dev/null; then echo 'Unsupported JAR must fail before launch.' >&2; exit 1; fi
  [[ ! -e "$MOCK_ARGS" ]]
done
echo 'PASS: audit results, shared environment, old and corrupt JARs rejected before Java launch.'
