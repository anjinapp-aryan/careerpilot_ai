#!/usr/bin/env bash
# setup-env.sh — Prepare production configuration for CareerPilot AI.
#
# Ensures /etc/careerpilot/careerpilot.env exists with correct permissions,
# and that <repo>/.env is a symlink pointing at it (the mechanism
# docker-compose.yml's `env_file: - .env` relies on in production).
#
# Idempotent: safe to rerun.
#
# Usage: sudo ./setup-env.sh [REPO_DIR]
#   REPO_DIR defaults to the repository root this script lives in (i.e. the
#   parent of the deployment/ directory) — works no matter where the repo was
#   cloned (/opt/careerpilot, /opt/careerpilot/careerpilot_ai, /home/opc/..., etc).

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

trap 'log_error "setup-env.sh failed at line ${LINENO} (exit code $?)"' ERR

readonly ENV_DIR="/etc/careerpilot"
readonly ENV_FILE="${ENV_DIR}/careerpilot.env"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEFAULT_REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly REPO_DIR="${1:-${DEFAULT_REPO_DIR}}"
readonly SYMLINK_PATH="${REPO_DIR}/.env"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    log_error "This script must be run as root (use sudo)."
    exit 1
  fi
}

ensure_env_dir() {
  if [[ -d "${ENV_DIR}" ]]; then
    log_ok "${ENV_DIR} already exists."
  else
    log_info "Creating ${ENV_DIR}..."
    mkdir -p "${ENV_DIR}"
    log_ok "${ENV_DIR} created."
  fi
}

verify_env_file_exists() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    log_error "${ENV_FILE} does not exist."
    log_error "This script does not create it for you — copy your production values there first" \
               "(it contains secrets and must never be generated or overwritten automatically)."
    exit 1
  fi
  log_ok "${ENV_FILE} exists."
}

secure_env_file() {
  log_info "Setting ownership and permissions on ${ENV_FILE}..."
  chown root:root "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
  log_ok "Ownership set to root:root, permissions set to 600."
}

verify_env_dir_permissions() {
  local perms
  perms="$(stat -c '%a' "${ENV_DIR}")"
  if [[ "${perms}" != "700" && "${perms}" != "750" ]]; then
    log_info "Tightening ${ENV_DIR} permissions to 700..."
    chmod 700 "${ENV_DIR}"
  fi
  log_ok "${ENV_DIR} permissions: $(stat -c '%a' "${ENV_DIR}")"
}

ensure_repo_dir_exists() {
  if [[ ! -d "${REPO_DIR}" ]]; then
    log_error "${REPO_DIR} does not exist. Clone the repository there before running this script."
    exit 1
  fi
  log_ok "${REPO_DIR} exists."
}

create_symlink() {
  if [[ -L "${SYMLINK_PATH}" ]]; then
    local current_target
    current_target="$(readlink -f "${SYMLINK_PATH}")"
    if [[ "${current_target}" == "$(readlink -f "${ENV_FILE}")" ]]; then
      log_ok "${SYMLINK_PATH} already symlinks correctly to ${ENV_FILE}."
      return
    else
      log_warn "${SYMLINK_PATH} exists but points at ${current_target} — repointing."
    fi
  elif [[ -e "${SYMLINK_PATH}" ]]; then
    log_error "${SYMLINK_PATH} exists and is a regular file, not a symlink."
    log_error "Refusing to overwrite a real file — inspect it manually, back it up if needed," \
               "then remove it and rerun this script."
    exit 1
  fi

  log_info "Creating symlink ${SYMLINK_PATH} -> ${ENV_FILE}..."
  ln -sfn "${ENV_FILE}" "${SYMLINK_PATH}"
  log_ok "Symlink created."
}

verify_symlink() {
  if [[ ! -L "${SYMLINK_PATH}" ]]; then
    log_error "${SYMLINK_PATH} is not a symlink after setup — something went wrong."
    exit 1
  fi
  local resolved
  resolved="$(readlink -f "${SYMLINK_PATH}")"
  if [[ "${resolved}" != "$(readlink -f "${ENV_FILE}")" ]]; then
    log_error "${SYMLINK_PATH} resolves to ${resolved}, expected ${ENV_FILE}."
    exit 1
  fi
  if [[ ! -r "${SYMLINK_PATH}" ]]; then
    log_error "${SYMLINK_PATH} is not readable through the symlink."
    exit 1
  fi
  log_ok "Symlink verified: ${SYMLINK_PATH} -> ${ENV_FILE} (readable)."
}

main() {
  require_root
  ensure_env_dir
  verify_env_file_exists
  secure_env_file
  verify_env_dir_permissions
  ensure_repo_dir_exists
  create_symlink
  verify_symlink
  log_ok "Environment setup complete. docker-compose.yml's env_file: - .env will now resolve to ${ENV_FILE}."
}

main "$@"
