#!/usr/bin/env bash
set +x
set -euo pipefail
umask 027

JAVA=/usr/java/jdk-17.0.11/bin/java
JAR=/usr/java/jar/wesite-admin-1.0.0.jar
CONFIG_DIR=/usr/java/config/admin/
LOG=/usr/java/logs/admin.log
DUMP_DIR=/usr/java/dump
ENV_FILE="${ADMIN_ENV_FILE:-${CONFIG_DIR%/}/runtime.env}"

if [[ -e "$ENV_FILE" ]]; then
  [[ -f "$ENV_FILE" && -r "$ENV_FILE" ]] || { echo "Cannot read environment file: $ENV_FILE" >&2; exit 1; }
  set -a
  source "$ENV_FILE"
  set +a
fi

# Both scripts share this lock, including when Jenkins invokes them.
exec 9>/usr/java/bin/.admin-control.lock
flock -x -w 120 9 || { echo 'Another Admin operation is in progress.' >&2; exit 1; }

matches_admin() {
  local executable argument previous='' profiles jar_found=0
  executable=$(readlink "/proc/$1/exe" 2>/dev/null) || return 1
  [[ "$executable" == */java || "$executable" == */'java (deleted)' ]] || return 1
  [[ -r "/proc/$1/cmdline" ]] || return 1
  while IFS= read -r -d '' argument; do
    # Examine all arguments: maintenance options occur after the shared JAR.
    if [[ "$previous" == -jar && "$argument" == "$JAR" ]]; then jar_found=1; fi
    case "$argument" in
      --spring.main.web-application-type=none|-Dspring.main.web-application-type=none) return 1 ;;
    esac
    if [[ "$previous" == --spring.main.web-application-type && "$argument" == none ]]; then return 1; fi
    profiles=''
    case "$argument" in
      --spring.profiles.active=*|-Dspring.profiles.active=*) profiles=${argument#*=} ;;
    esac
    if [[ "$previous" == --spring.profiles.active ]]; then profiles=$argument; fi
    case ",${profiles//[[:space:]]/}," in
      *,blog-audit,*|*,blog-sanitize,*) return 1 ;;
    esac
    previous=$argument
  done < "/proc/$1/cmdline"
  [[ "$jar_found" == 1 ]]
}

for process in /proc/[0-9]*; do
  pid=${process##*/}
  if matches_admin "$pid" 2>/dev/null; then
    echo "Admin is already running: PID=$pid"
    exit 0
  fi
done

[[ -x "$JAVA" && -r "$JAR" ]] || { echo 'Java executable or Admin JAR is missing.' >&2; exit 1; }
[[ -d "$CONFIG_DIR" && -r "$CONFIG_DIR" && -x "$CONFIG_DIR" ]] || {
  echo "Admin configuration directory is missing or inaccessible: $CONFIG_DIR" >&2
  exit 1
}
mkdir -p /usr/java/logs "$DUMP_DIR"
cd /usr/java/bin

# Preserve existing output and ensure the child does not inherit the control lock.
printf '\n--- Admin start: %s ---\n' "$(date -Is)" >> "$LOG"
JENKINS_NODE_COOKIE=dontKillMe BUILD_ID=dontKillMe nohup "$JAVA" \
  -Xms256m -Xmx512m -Xss512k \
  -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=128m \
  -XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError "-XX:HeapDumpPath=$DUMP_DIR" \
  '-Xlog:gc*:file=/usr/java/logs/gc_admin.log:time,uptime,level,tags:filecount=5,filesize=10M' \
  -jar "$JAR" --spring.profiles.active=prod \
  "--spring.config.additional-location=file:$CONFIG_DIR" \
  </dev/null >> "$LOG" 2>&1 9>&- &
pid=$!

# This checks process survival, not HTTP readiness.
for ((attempt = 0; attempt < 10; attempt++)); do
  sleep 1
  if ! matches_admin "$pid" 2>/dev/null; then
    echo "Admin exited during startup. Inspect $LOG" >&2
    exit 1
  fi
done
echo "Admin process is running: PID=$pid. Verify application readiness in $LOG."
