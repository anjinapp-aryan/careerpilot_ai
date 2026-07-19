#!/usr/bin/env bash
# backup.sh — Back up CareerPilot AI deployment configuration (not data).
#
# Backs up: careerpilot.env, careerpilot.service, docker-compose.yml.
# Does NOT back up: Docker volumes, the database, MinIO data, images, or logs
# — those are out of scope for a config backup and have their own backup story
# (Neon point-in-time recovery, MinIO bucket replication, etc.).
#
# Keeps the latest 10 timestamped backups, deleting older ones automatically.
#
# Usage: sudo ./backup.sh [REPO_DIR]
#   REPO_DIR defaults to /opt/careerpilot

set -Eeuo pipefail

readonly C_RESET='\033[0m'
readonly C_GREEN='\033[0;32m'
readonly C_YELLOW='\033[1;33m'
readonly C_RED='\033[0;31m'
readonly C_BLUE='\033[0;34m'

log_info()  { printf "${C_BLUE}[INFO]${C_RESET}  %s\n" "$*"; }
log_ok()    { printf "${C_GREEN}[OK]${C_RESET}    %s\n" "$*"; }
log_warn()  { printf "${C_YELLOW}[WARN]${C_RESET}  %s\n" "$*"; }
log_error() { printf "${C_RED}[ERROR]${C_RESET} %s\n" "$*" >&2; }

trap 'log_error "backup.sh failed at line ${LINENO} (exit code $?)"' ERR

readonly REPO_DIR="${1:-/opt/careerpilot}"
readonly ENV_FILE="/etc/careerpilot/careerpilot.env"
readonly SERVICE_FILE="/etc/systemd/system/careerpilot.service"
readonly COMPOSE_FILE="${REPO_DIR}/docker-compose.yml"
readonly BACKUP_ROOT="/var/backups/careerpilot"
readonly KEEP_COUNT=10

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    log_error "This script must be run as root (use sudo) — it reads careerpilot.env (0600)."
    exit 1
  fi
}

ensure_backup_dir() {
  if [[ ! -d "${BACKUP_ROOT}" ]]; then
    log_info "Creating ${BACKUP_ROOT}..."
    mkdir -p "${BACKUP_ROOT}"
    chmod 700 "${BACKUP_ROOT}"
  fi
}

run_backup() {
  local timestamp
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  local dest="${BACKUP_ROOT}/${timestamp}"

  log_info "Creating backup at ${dest}..."
  mkdir -p "${dest}"

  local copied=0

  if [[ -f "${ENV_FILE}" ]]; then
    cp -p "${ENV_FILE}" "${dest}/careerpilot.env"
    copied=$(( copied + 1 ))
  else
    log_warn "${ENV_FILE} not found — skipping."
  fi

  if [[ -f "${SERVICE_FILE}" ]]; then
    cp -p "${SERVICE_FILE}" "${dest}/careerpilot.service"
    copied=$(( copied + 1 ))
  else
    log_warn "${SERVICE_FILE} not found — skipping."
  fi

  if [[ -f "${COMPOSE_FILE}" ]]; then
    cp -p "${COMPOSE_FILE}" "${dest}/docker-compose.yml"
    copied=$(( copied + 1 ))
  else
    log_warn "${COMPOSE_FILE} not found — skipping."
  fi

  if [[ "${copied}" -eq 0 ]]; then
    log_error "No files were backed up — nothing to copy. Removing empty backup directory."
    rmdir "${dest}"
    exit 1
  fi

  chmod 700 "${dest}"
  chmod 600 "${dest}"/* 2>/dev/null || true

  log_ok "Backed up ${copied} file(s) to ${dest}."
}

prune_old_backups() {
  log_info "Pruning backups beyond the latest ${KEEP_COUNT}..."
  local backups
  mapfile -t backups < <(find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -r)

  local total="${#backups[@]}"
  if (( total <= KEEP_COUNT )); then
    log_ok "${total} backup(s) present, nothing to prune."
    return
  fi

  local to_delete=("${backups[@]:KEEP_COUNT}")
  for old in "${to_delete[@]}"; do
    log_info "Deleting old backup: ${old}"
    rm -rf "${BACKUP_ROOT:?}/${old}"
  done
  log_ok "Pruned $(( total - KEEP_COUNT )) old backup(s), ${KEEP_COUNT} retained."
}

main() {
  require_root
  ensure_backup_dir
  run_backup
  prune_old_backups
  log_ok "Backup complete."
}

main "$@"
