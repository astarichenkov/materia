#!/usr/bin/env bash
#
# Optional laptop-side helper that deploys private-ai-chat to the homelab LXC.
#
# Requires: PRIVATE_AI_LXC_PASS (never printed, never passed on the command
# line), ssh, scp, and a locally built JAR.
#
# Usage:
#   export PRIVATE_AI_LXC_PASS=...            # from a secure source
#   scripts/day30/deploy-private-ai.sh [host]
#
# host defaults to 203.0.113.10.
set -euo pipefail

HOST="${1:-203.0.113.10}"
USER_NAME="${PRIVATE_AI_LXC_USER:-root}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${REPO_ROOT}/private-ai-chat/target/private-ai-chat-1.0.0-SNAPSHOT.jar"
REMOTE_DIR=/tmp/day30-deploy

[ -n "${PRIVATE_AI_LXC_PASS:-}" ] || { echo "PRIVATE_AI_LXC_PASS is not set" >&2; exit 1; }
[ -s "${JAR}" ] || { echo "JAR not found: ${JAR} (run ./mvnw -pl private-ai-chat -am package -DskipTests)" >&2; exit 1; }

ASKPASS="$(mktemp)"
cleanup() { rm -f "${ASKPASS}"; }
trap cleanup EXIT
printf '#!/bin/sh\nprintf "%%s\\n" "$PRIVATE_AI_LXC_PASS"\n' > "${ASKPASS}"
chmod 700 "${ASKPASS}"

SSH=(ssh -o StrictHostKeyChecking=accept-new)
SCP=(scp -o StrictHostKeyChecking=accept-new)
if ! ssh -o BatchMode=yes -o ConnectTimeout=5 "${USER_NAME}@${HOST}" true 2>/dev/null; then
  echo "No key-based access; falling back to password via askpass (not logged)."
  export SSH_ASKPASS="${ASKPASS}" SSH_ASKPASS_REQUIRE=force DISPLAY=:0
  SSH=(ssh -o StrictHostKeyChecking=accept-new -o PreferredAuthentications=password -o PubkeyAuthentication=no)
  SCP=(scp -o StrictHostKeyChecking=accept-new -o PreferredAuthentications=password -o PubkeyAuthentication=no)
fi

echo ">>> Copying deployment assets to ${HOST}:${REMOTE_DIR}"
"${SSH[@]}" "${USER_NAME}@${HOST}" "rm -rf ${REMOTE_DIR} && mkdir -p ${REMOTE_DIR}"
"${SCP[@]}" "${REPO_ROOT}"/deployment/homelab/* "${USER_NAME}@${HOST}:${REMOTE_DIR}/"
"${SCP[@]}" "${JAR}" "${USER_NAME}@${HOST}:${REMOTE_DIR}/private-ai-chat.jar"

echo ">>> Preparing host"
"${SSH[@]}" "${USER_NAME}@${HOST}" "chmod +x ${REMOTE_DIR}/*.sh && ${REMOTE_DIR}/install.sh all"

echo ">>> Deploying service"
"${SSH[@]}" "${USER_NAME}@${HOST}" "${REMOTE_DIR}/deploy.sh ${REMOTE_DIR}/private-ai-chat.jar"

echo ">>> Running acceptance checks"
"${SSH[@]}" "${USER_NAME}@${HOST}" "${REMOTE_DIR}/verify.sh http://127.0.0.1:8080"
