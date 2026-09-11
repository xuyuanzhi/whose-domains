#!/usr/bin/env bash
set -euo pipefail

JAR=/usr/java/jar/wesite-admin-1.0.0.jar
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

pids=()
for process in /proc/[0-9]*; do
  pid=${process##*/}
  if matches_admin "$pid" 2>/dev/null; then pids+=("$pid"); fi
done

if ((${#pids[@]} == 0)); then
  echo 'Admin is not running.'
  exit 0
fi

for pid in "${pids[@]}"; do
  if matches_admin "$pid" 2>/dev/null; then
    echo "Stopping Admin: PID=$pid"
    if ! kill -TERM "$pid" 2>/dev/null && matches_admin "$pid" 2>/dev/null; then
      echo "Unable to signal Admin: PID=$pid" >&2
      exit 1
    fi
  fi
done

for ((attempt = 0; attempt < 60; attempt++)); do
  running=0
  # Scan again so a replacement process started by a monitor also blocks deployment.
  for process in /proc/[0-9]*; do
    if matches_admin "${process##*/}" 2>/dev/null; then running=1; break; fi
  done
  if ((running == 0)); then echo 'Admin stopped.'; exit 0; fi
  sleep 1
done

echo 'Admin is still running after 60 seconds. Deployment must stop; inspect the process and any restart monitor.' >&2
exit 1
