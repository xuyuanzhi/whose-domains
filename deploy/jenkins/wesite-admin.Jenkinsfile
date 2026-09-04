pipeline {
  agent any

  options {
    disableConcurrentBuilds()
  }

  stages {
    stage('Build Admin') {
      steps {
        sh 'mvn -B -pl wesite-admin -am clean verify'
      }
    }

    stage('Deploy Admin') {
      steps {
        withCredentials([
          sshUserPrivateKey(
            credentialsId: 'whose-domains-prod-ssh',
            keyFileVariable: 'SSH_KEY'
          ),
          file(
            credentialsId: 'whose-domains-prod-known-hosts',
            variable: 'KNOWN_HOSTS'
          )
        ]) {
          sh '''#!/usr/bin/env bash
set -euo pipefail

[[ "$GIT_COMMIT" =~ ^[0-9a-f]{7,64}$ ]] || {
  printf 'GIT_COMMIT must be 7 to 64 lowercase hexadecimal characters\n' >&2
  exit 64
}
[[ "$BUILD_NUMBER" =~ ^[0-9]+$ ]] || {
  printf 'BUILD_NUMBER must contain only decimal digits\n' >&2
  exit 64
}
[[ -f "$SSH_KEY" && -r "$SSH_KEY" ]] || {
  printf 'SSH private key credential is missing or unreadable\n' >&2
  exit 1
}
[[ -f "$KNOWN_HOSTS" && -s "$KNOWN_HOSTS" ]] || {
  printf 'Pinned known-hosts credential is missing or empty\n' >&2
  exit 1
}

version="${GIT_COMMIT}-${BUILD_NUMBER}"
artifact='wesite-admin/target/wesite-admin-1.0.0.jar'
remote_target='wesite-deploy@hk.tail5ed8be.ts.net'
remote_directory="/var/lib/wesite-deploy/incoming/admin-${version}"
remote_jar="${remote_directory}/wesite-admin-1.0.0.jar"
[[ -f "$artifact" && ! -L "$artifact" ]] || {
  printf 'Expected Admin artifact is missing or is a symbolic link\n' >&2
  exit 1
}

ssh_options=(
  -i "$SSH_KEY"
  -o BatchMode=yes
  -o IdentitiesOnly=yes
  -o StrictHostKeyChecking=yes
  -o "UserKnownHostsFile=$KNOWN_HOSTS"
  -o GlobalKnownHostsFile=/dev/null
)

ssh "${ssh_options[@]}" "$remote_target" \
  bash -s -- "$remote_directory" <<'REMOTE_PREPARE'
set -euo pipefail
umask 077
mkdir -m 0700 -- "$1"
[[ "$(stat -c '%a' -- "$1")" == 700 ]]
REMOTE_PREPARE

set +e
scp "${ssh_options[@]}" "$artifact" "${remote_target}:${remote_jar}"
upload_status=$?
set -e
if (( upload_status != 0 )); then
  set +e
  ssh "${ssh_options[@]}" "$remote_target" \
    bash -s -- "$remote_jar" "$remote_directory" <<'REMOTE_UPLOAD_CLEANUP'
set +e
rm -f -- "$1"
rmdir -- "$2"
REMOTE_UPLOAD_CLEANUP
  exit "$upload_status"
fi

ssh "${ssh_options[@]}" "$remote_target" \
  bash -s -- "$remote_jar" "$remote_directory" "$version" <<'REMOTE_DEPLOY'
set +e
sudo -n /usr/local/sbin/deploy-wesite-app admin "$3" "$1"
deploy_status=$?
check_status=0
if (( deploy_status == 0 )); then
  sudo -n /usr/local/sbin/check-wesite-app admin
  check_status=$?
fi
if (( deploy_status != 0 )); then
  release_status=$deploy_status
else
  release_status=$check_status
fi
rm -f -- "$1"
file_cleanup_status=$?
rmdir -- "$2"
directory_cleanup_status=$?
if (( file_cleanup_status != 0 || directory_cleanup_status != 0 )); then
  printf 'WARNING: exact incoming cleanup failed: %s\n' "$2" >&2
fi
exit "$release_status"
REMOTE_DEPLOY
'''
        }
      }
    }
  }
}
