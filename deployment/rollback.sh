#!/usr/bin/env bash
# rollback.sh — Roll back the CareerPilot AI application to the previous git commit.
#
# Rolls back APPLICATION CODE ONLY: stops the stack, checks out the previous
# commit, rebuilds, restarts, and verifies health. Never touches the database,
# Docker volumes, MinIO data, or careerpilot.env — those are out of scope for
# an application rollback and have their own recovery paths.
#
# Aborts if the git working tree is dirty (uncommitted changes would be lost
# by the checkout, and could mask what's actually running).
#
# Usage: ./rollback.sh [REPO_DIR] [TARGET_REF]
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

trap 'log_error "rollback.sh failed at line ${LINENO} (exit code $?). Stack may be stopped — inspect with: docker compose ps"' ERR

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEFAULT_REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly REPO_DIR="${1:-${DEFAULT_REPO_DIR}}"
readonly ENV_FILE="/etc/careerpilot/careerpilot.env"
readonly COMPOSE_FILE="${REPO_DIR}/docker-compose.yml"

cd "${REPO_DIR}"

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

check_git_repo() {
  log_info "Checking git repository validity..."
  if ! git -C "${REPO_DIR}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    log_error "${REPO_DIR} is not a valid git repository."
    exit 1
  fi
  log_ok "Valid git repository."
}

check_clean_working_tree() {
  log_info "Checking git working tree is clean..."
  if [[ -n "$(git -C "${REPO_DIR}" status --porcelain)" ]]; then
    log_error "Working tree is dirty. Aborting rollback — commit, stash, or discard local" \
               "changes before rolling back, so the git history accurately reflects what runs."
    exit 1
  fi
  log_ok "Working tree is clean."
}

resolve_target_commit() {
  local target="${1:-HEAD~1}"
  if ! git -C "${REPO_DIR}" rev-parse --verify "${target}" >/dev/null 2>&1; then
    log_error "Target commit '${target}' does not resolve to a valid commit."
    exit 1
  fi
  echo "$(git -C "${REPO_DIR}" rev-parse --short "${target}")"
}

stop_stack() {
  log_info "Stopping Docker Compose stack (containers only, volumes preserved)..."
  compose stop
  log_ok "Stack stopped."
}

checkout_previous_commit() {
  local target_ref="$1"
  local current
  current="$(git -C "${REPO_DIR}" rev-parse --short HEAD)"
  log_info "Checking out ${target_ref} (currently at ${current})..."
  git -C "${REPO_DIR}" checkout "${target_ref}"
  log_ok "Checked out $(git -C "${REPO_DIR}" rev-parse --short HEAD)."
}

rebuild_and_restart() {
  log_info "Rebuilding images for rolled-back code..."
  compose build
  log_info "Restarting stack..."
  compose up -d --remove-orphans
  log_ok "Stack restarted on rolled-back code."
}

verify_health() {
  log_info "Verifying health after rollback..."
  if [[ -x "${SCRIPT_DIR}/verify.sh" ]]; then
    "${SCRIPT_DIR}/verify.sh" "${REPO_DIR}" || {
      log_error "Post-rollback verification failed. The stack is running rolled-back code" \
                 "but is not fully healthy — investigate before considering rollback complete."
      exit 1
    }
  else
    log_warn "verify.sh not found or not executable — skipping automated health verification."
  fi
  log_ok "Post-rollback verification passed."
}

main() {
  local target_ref="${2:-HEAD~1}"

  check_git_repo
  check_clean_working_tree

  local resolved
  resolved="$(resolve_target_commit "${target_ref}")"
  log_info "Rolling back to commit ${resolved}."

  stop_stack
  checkout_previous_commit "${target_ref}"
  rebuild_and_restart
  verify_health

  log_ok "Rollback to ${resolved} complete."
}

main "$@"
