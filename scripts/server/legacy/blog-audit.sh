#!/usr/bin/env bash
# Run interactively on the production server; do not source admin-start.sh.
set +x
set -euo pipefail
umask 077

JAVA="${BLOG_AUDIT_JAVA:-/usr/java/jdk-17.0.11/bin/java}"
JAR="${BLOG_AUDIT_JAR:-/usr/java/jar/wesite-admin-1.0.0.jar}"
CONFIG_DIR="${BLOG_AUDIT_CONFIG_DIR:-/usr/java/config/admin/}"
REPORT_ROOT="${BLOG_AUDIT_REPORT_ROOT:-/usr/java/logs/blog-audit}"
ENV_FILE="${ADMIN_ENV_FILE:-${CONFIG_DIR%/}/runtime.env}"

# Share only variable definitions with the server process, never its start command.
if [[ -e "$ENV_FILE" ]]; then
  [[ -f "$ENV_FILE" && -r "$ENV_FILE" ]] || { echo "Cannot read environment file: $ENV_FILE" >&2; exit 1; }
  set -a
  source "$ENV_FILE"
  set +a
fi

[[ -x "$JAVA" && -r "$JAR" && -d "$CONFIG_DIR" ]] || {
  echo 'Java, Admin JAR or external configuration directory is missing.' >&2
  exit 1
}
# A profile name is not a feature check: old binaries can ignore blog-audit and
# start their normal scheduled writers. Reject them before launching the app.
JAR_TOOL="${JAVA%/*}/jar"
[[ -x "$JAR_TOOL" ]] || { echo "JDK jar tool is missing: $JAR_TOOL" >&2; exit 1; }
if ! entries=$("$JAR_TOOL" tf "$JAR"); then
  echo "Cannot inspect Admin JAR: $JAR" >&2
  exit 1
fi
for required_class in \
    BOOT-INF/classes/info/wesite/admin/maintenance/BlogAuditRunner.class \
    BOOT-INF/classes/info/wesite/admin/config/AdminSchedulingConfiguration.class; do
  if ! grep -qxF "$required_class" <<< "$entries"; then
    echo "Refusing to start audit: JAR is missing $required_class. Deploy the complete blog audit release first." >&2
    exit 1
  fi
done
unset entries
if [[ -z "${JWT_SECRET:-}" ]]; then
  [[ -t 0 ]] || { echo 'Export the existing JWT_SECRET before a non-interactive audit.' >&2; exit 1; }
  read -r -s -p 'Enter the existing production JWT_SECRET (hidden): ' JWT_SECRET
  printf '\n'
fi
[[ -n "$JWT_SECRET" ]] || { echo 'JWT_SECRET must not be empty.' >&2; exit 1; }
export JWT_SECRET

mkdir -p "$REPORT_ROOT"
exec 9>"$REPORT_ROOT/.audit.lock"
flock -n 9 || { echo 'A blog audit is already running.' >&2; exit 1; }
REPORT_DIR=$(mktemp -d "$REPORT_ROOT/run-$(date +%Y%m%d-%H%M%S)-XXXXXX")
LOG="$REPORT_DIR/audit.log"
echo "Auditing blog content. Log: $LOG"

if "$JAVA" -Xms128m -Xmx384m -XX:+UseG1GC \
    -jar "$JAR" \
    --spring.profiles.active=prod,blog-audit \
    "--spring.config.additional-location=file:${CONFIG_DIR%/}/" \
    --spring.main.web-application-type=none \
    --wesite.blog.audit.enabled=true >"$LOG" 2>&1; then
  :
else
  result=$?
  echo "Audit failed (exit $result). Inspect $LOG locally; no completed report was produced." >&2
  exit "$result"
fi

marker='Blog content audit completed: '
count=$(grep -cF "$marker" "$LOG" || true)
[[ "$count" == 1 ]] || {
  echo "Expected one completion record, found $count. Inspect $LOG; do not treat this as a clean audit." >&2
  exit 1
}
sed -n 's/^.*Blog content audit completed: //p' "$LOG" > "$REPORT_DIR/report.json"
echo "Audit completed. Share this report for article triage: $REPORT_DIR/report.json"
echo 'The report contains article metadata and review findings, not article bodies. Keep audit.log private.'
