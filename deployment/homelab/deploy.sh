#!/usr/bin/env bash
#
# Day 30 — deploys the private-ai-chat Spring Boot JAR on the prepared host.
#
# Runs ON the server (as root). Idempotent. Never prints or commits secrets.
#
# Usage: sudo ./deploy.sh /path/to/private-ai-chat.jar
#
# Prepares:
#   /opt/private-ai/private-ai-chat.jar       executable Spring Boot jar
#   /etc/private-ai/application.yml           production config (non-secret)
#   /etc/private-ai/private-ai.env            generated CHAT_API_KEY (chmod 600)
#   /etc/systemd/system/private-ai.service    systemd unit
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_SOURCE="${1:-}"
APP_DIR=/opt/private-ai
ETC_DIR=/etc/private-ai
DATA_DIR=/var/lib/private-ai
LOG_DIR=/var/log/private-ai
SERVICE_USER=private-ai
MODEL="${CHAT_MODEL:-qwen3:1.7b}"

log() { printf '>>> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "must run as root"
[ -n "${JAR_SOURCE}" ] || die "usage: $0 /path/to/private-ai-chat.jar"
[ -f "${JAR_SOURCE}" ] || die "jar not found: ${JAR_SOURCE}"
[ -s "${JAR_SOURCE}" ] || die "jar is empty: ${JAR_SOURCE}"
command -v java >/dev/null 2>&1 || die "Java is not installed (run install.sh java)"

# --- layout ---------------------------------------------------------------
if ! id "${SERVICE_USER}" >/dev/null 2>&1; then
  log "Creating system user ${SERVICE_USER}"
  useradd --system --no-create-home --home-dir "${DATA_DIR}" --shell /usr/sbin/nologin "${SERVICE_USER}"
fi
mkdir -p "${APP_DIR}" "${ETC_DIR}" "${DATA_DIR}" "${LOG_DIR}"
chown -R "${SERVICE_USER}:${SERVICE_USER}" "${APP_DIR}" "${DATA_DIR}" "${LOG_DIR}"
# The app (user private-ai) must read application.yml, so the directory needs
# the execute bit; private-ai.env remains root-owned mode 600.
chmod 755 "${ETC_DIR}"

# --- jar ------------------------------------------------------------------
if [ -f "${APP_DIR}/private-ai-chat.jar" ]; then
  cp -f "${APP_DIR}/private-ai-chat.jar" "${APP_DIR}/private-ai-chat.jar.bak"
fi
install -m 0640 -o "${SERVICE_USER}" -g "${SERVICE_USER}" "${JAR_SOURCE}" "${APP_DIR}/private-ai-chat.jar"
log "Installed jar: $(du -h "${APP_DIR}/private-ai-chat.jar" | cut -f1)"

# --- production config (non-secret) --------------------------------------
cat > "${ETC_DIR}/application.yml" <<EOF
# Managed by deployment/homelab/deploy.sh — production configuration.
server:
  port: 8080
  address: 0.0.0.0
spring:
  thymeleaf:
    cache: true
app:
  ollama:
    base-url: http://127.0.0.1:11434
    model: ${MODEL}
    timeout: 2m
    think: false
  limits:
    rate-limit-per-minute: 5
    max-message-chars: 2000
    max-context-tokens: 4096
    max-output-tokens: 512
    max-history-messages: 8
    queue-capacity: 10
management:
  endpoints:
    web:
      exposure:
        include: health,info
EOF
chmod 644 "${ETC_DIR}/application.yml"

# --- secret env (generate once, never overwrite, never print) ------------
if [ ! -s "${ETC_DIR}/private-ai.env" ]; then
  log "Generating API key -> ${ETC_DIR}/private-ai.env"
  umask 077
  {
    echo "# Managed by deploy.sh. Do not commit. chmod 600."
    echo "CHAT_API_KEY=$(openssl rand -hex 24)"
    echo "# Override the packaged defaults if needed:"
    echo "# OLLAMA_BASE_URL=http://127.0.0.1:11434"
    echo "# CHAT_MODEL=${MODEL}"
  } > "${ETC_DIR}/private-ai.env"
  chmod 600 "${ETC_DIR}/private-ai.env"
else
  log "Keeping existing ${ETC_DIR}/private-ai.env"
fi

# --- systemd unit ---------------------------------------------------------
if [ -f "${SCRIPT_DIR}/private-ai.service.template" ]; then
  install -m 0644 "${SCRIPT_DIR}/private-ai.service.template" /etc/systemd/system/private-ai.service
else
  die "private-ai.service.template not found next to deploy.sh"
fi

systemctl daemon-reload
systemctl enable private-ai >/dev/null 2>&1 || true
systemctl restart private-ai

# --- wait for health ------------------------------------------------------
log "Waiting for service health"
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/api/health >/dev/null 2>&1; then
    log "Service is up"
    curl -fsS http://127.0.0.1:8080/api/health
    echo
    exit 0
  fi
  sleep 2
done
systemctl status private-ai --no-pager || true
journalctl -u private-ai -n 40 --no-pager || true
die "service did not become healthy in time"
