#!/usr/bin/env bash

wesite_readiness_check() {
  local url="$1"
  local response
  local status
  local body
  local response_mode="${2:-${WESITE_HEALTH_RESPONSE_MODE:-readiness}}"
  local up_pattern='^[[:space:]]*\{[[:space:]]*"status"[[:space:]]*:[[:space:]]*"UP"[[:space:]]*\}[[:space:]]*$'

  if ! response="$(curl --silent --show-error --max-time 5 \
      --write-out $'\n%{http_code}' "$url")"; then
    return 1
  fi
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"

  [[ "$status" == 200 ]] || return 1
  case "$response_mode" in
    readiness)
      [[ "$body" =~ $up_pattern ]]
      ;;
    legacy-http-200)
      [[ "$body" =~ [^[:space:]] ]]
      ;;
    *)
      return 1
      ;;
  esac
}
