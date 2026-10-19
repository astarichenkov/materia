#!/usr/bin/env bash
#
# Day 30 — installer for the private AI host (Debian 12 LXC).
#
# Idempotent: safe to re-run. Installs Java 25 (Temurin JRE), Ollama, applies
# the loopback-only Ollama systemd drop-in, pulls qwen3:1.7b and creates the
# service user / directories. Never contains or logs secrets.
#
# Usage: sudo ./install.sh {detect|java|ollama|model|dirs|all}
#        sudo ./install.sh all            # full host preparation
#
set -euo pipefail

MODEL="${CHAT_MODEL:-qwen3:1.7b}"
JAVA_DIR=/opt/java
APP_DIR=/opt/private-ai
ETC_DIR=/etc/private-ai
DATA_DIR=/var/lib/private-ai
LOG_DIR=/var/log/private-ai
SERVICE_USER=private-ai

log() { printf '>>> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require_root() { [ "$(id -u)" -eq 0 ] || die "must run as root"; }

detect() {
  log "OS"; grep PRETTY_NAME /etc/os-release || true
  log "Kernel"; uname -a
  log "CPU"; nproc
  log "Memory"; free -h
  log "Disk"; df -h /
  log "Ports"; (ss -ltn 2>/dev/null || true) | grep -E ':(22|8080|11434)\b' || echo "  none of 22/8080/11434 listening"
  log "Java"; java -version 2>&1 | head -1 || echo "  not installed"
  log "Ollama"; ollama --version 2>/dev/null || echo "  not installed"
  log "Model"; ollama list 2>/dev/null || true
}

install_java() {
  if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '"25'; then
    log "Java 25 already installed: $(java -version 2>&1 | head -1)"
    return
  fi
  log "Installing Temurin 25 JRE into ${JAVA_DIR}"
  mkdir -p "${JAVA_DIR}"
  local tmp; tmp="$(mktemp -d)"
  curl -fsSL -o "${tmp}/jre.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jre/hotspot/normal/eclipse"
  tar -xzf "${tmp}/jre.tar.gz" -C "${JAVA_DIR}"
  rm -rf "${tmp}"
  local found; found="$(find "${JAVA_DIR}" -maxdepth 1 -type d -name 'jdk-25*' | head -1)"
  [ -n "${found}" ] || die "Temurin 25 extraction failed"
  ln -sfn "${found}" "${JAVA_DIR}/current"
  ln -sfn "${JAVA_DIR}/current/bin/java" /usr/local/bin/java
  java -version
}

install_ollama() {
  if ! command -v ollama >/dev/null 2>&1; then
    log "Installing Ollama (official script)"
    curl -fsSL https://ollama.com/install.sh | sh
  else
    log "Ollama already installed: $(ollama --version 2>&1)"
  fi
  log "Applying loopback-only systemd drop-in"
  mkdir -p /etc/systemd/system/ollama.service.d
  cat > /etc/systemd/system/ollama.service.d/override.conf <<'EOF'
[Service]
Environment="OLLAMA_HOST=127.0.0.1:11434"
Environment="OLLAMA_NUM_PARALLEL=1"
Environment="OLLAMA_MAX_LOADED_MODELS=1"
Environment="OLLAMA_MAX_QUEUE=5"
Environment="OLLAMA_CONTEXT_LENGTH=4096"
Environment="OLLAMA_KEEP_ALIVE=10m"
Environment="OLLAMA_NO_CLOUD=1"
EOF
  systemctl daemon-reload
  systemctl enable ollama >/dev/null 2>&1 || true
  systemctl restart ollama
  sleep 3
  systemctl is-active --quiet ollama || die "ollama.service failed to start"
  curl -fsS http://127.0.0.1:11434/api/tags >/dev/null || die "Ollama API not reachable on loopback"
  log "Ollama listening on 127.0.0.1:11434"
}

pull_model() {
  log "Pulling model ${MODEL} (this can take a few minutes)"
  ollama pull "${MODEL}" >/dev/null
  ollama list
}

create_layout() {
  if ! id "${SERVICE_USER}" >/dev/null 2>&1; then
    log "Creating system user ${SERVICE_USER}"
    useradd --system --no-create-home --home-dir "${DATA_DIR}" --shell /usr/sbin/nologin "${SERVICE_USER}"
  fi
  mkdir -p "${APP_DIR}" "${ETC_DIR}" "${DATA_DIR}" "${LOG_DIR}"
  chown -R "${SERVICE_USER}:${SERVICE_USER}" "${APP_DIR}" "${DATA_DIR}" "${LOG_DIR}"
  # The service user must be able to traverse /etc/private-ai to read
  # application.yml; private-ai.env stays root-only (systemd reads it).
  chmod 755 "${ETC_DIR}"
  log "Layout ready: ${APP_DIR} ${ETC_DIR} ${DATA_DIR} ${LOG_DIR}"
}

case "${1:-help}" in
  detect) detect ;;
  java) require_root; install_java ;;
  ollama) require_root; install_ollama ;;
  model) require_root; pull_model ;;
  dirs) require_root; create_layout ;;
  all) require_root; detect; install_java; install_ollama; pull_model; create_layout; detect ;;
  *) echo "Usage: $0 {detect|java|ollama|model|dirs|all}"; exit 1 ;;
esac
