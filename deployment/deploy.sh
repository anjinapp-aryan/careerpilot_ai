#!/usr/bin/env bash
# deploy.sh — Deploy whatever code is currently checked out in this repository.
#
# Single Responsibility: this script deploys the working tree exactly as it
# stands. It never inspects, fetches, or changes Git state — version selection
# (which branch/tag/commit to run) is the operator's job, performed with plain
# Git commands *before* invoking this script. That separation is deliberate:
# it keeps this script identical whether a human runs it from a terminal or a
# CI/CD pipeline runs it after its own checkout step.
#
# Typical usage:
#
#   git fetch origin && git checkout main && git pull --ff-only
#   ./deployment/deploy.sh
#
#   git fetch --tags && git checkout v1.3.2
#   ./deployment/deploy.sh
#
#   git checkout 8fd92aa
#   ./deployment/deploy.sh
#
# What this script does, in order:
#   1. Validate prerequisites (Docker, Compose, compose file, env symlink,
#      careerpilot.env; warns — but does not act — if the working tree is dirty)
#   2. docker compose build
#   3. docker compose up -d --remove-orphans
#   4. Wait for containers to report healthy
#   5. Run deployment/verify.sh
#   6. Print a deployment summary
#
# Usage: ./deployment/deploy.sh
#   No parameters. The repository root is derived from this script's own
#   location, so it must be run from inside the checked-out repo (i.e. from
#   deployment/deploy.sh — not copied elsewhere).

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

trap 'log_error "deploy.sh failed at line ${LINENO} (exit code $?). Stack left as-is — inspect with: docker compose ps"' ERR

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly ENV_FILE="/etc/careerpilot/careerpilot.env"
readonly COMPOSE_FILE="${REPO_DIR}/docker-compose.yml"
readonly SYMLINK_PATH="${REPO_DIR}/.env"

DEPLOY_START_EPOCH=0

cd "${REPO_DIR}"

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------
check_docker_installed() {
  log_info "Checking Docker is installed..."
  if ! command -v docker >/dev/null 2>&1; then
    log_error "docker CLI not found. Run install-docker.sh first."
    exit 1
  fi
  log_ok "docker CLI found: $(docker --version)"
}

check_docker_running() {
  log_info "Checking Docker daemon..."
  if ! docker info >/dev/null 2>&1; then
    log_error "Docker daemon is not running or not reachable."
    exit 1
  fi
  log_ok "Docker daemon is running."
}

check_compose_available() {
  log_info "Checking Docker Compose plugin..."
  if ! docker compose version >/dev/null 2>&1; then
    log_error "docker compose plugin not found."
    exit 1
  fi
  log_ok "Docker Compose available: $(docker compose version --short 2>/dev/null || true)"
}

check_compose_file() {
  log_info "Checking for docker-compose.yml..."
  if [[ ! -f "${COMPOSE_FILE}" ]]; then
    log_error "${COMPOSE_FILE} not found."
    exit 1
  fi
  log_ok "${COMPOSE_FILE} present."
}

check_env_symlink() {
  log_info "Checking ${SYMLINK_PATH} symlink..."
  if [[ ! -L "${SYMLINK_PATH}" ]]; then
    log_error "${SYMLINK_PATH} is not a symlink. Run setup-env.sh first."
    exit 1
  fi
  log_ok "${SYMLINK_PATH} symlink present."
}

check_env_file() {
  log_info "Checking ${ENV_FILE}..."
  if [[ ! -f "${ENV_FILE}" ]]; then
    log_error "${ENV_FILE} not found. Run setup-env.sh first."
    exit 1
  fi
  log_ok "${ENV_FILE} present."
}

warn_if_working_tree_dirty() {
  # Informational only — deploy.sh never changes Git state, so a dirty tree
  # is not blocked, just surfaced. The operator decided what to check out;
  # this only helps them notice if that checkout has local modifications
  # they didn't intend to ship.
  log_info "Checking git working tree state (informational only)..."
  if ! git -C "${REPO_DIR}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    log_warn "${REPO_DIR} is not a git repository — skipping working tree check."
    return
  fi
  if [[ -n "$(git -C "${REPO_DIR}" status --porcelain)" ]]; then
    log_warn "Working tree has uncommitted changes. Deploying exactly what's checked out," \
              "including those changes — this is a warning only, not a block."
  else
    local ref
    ref="$(git -C "${REPO_DIR}" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
    log_ok "Working tree is clean. Deploying commit ${ref}."
  fi
}

# ---------------------------------------------------------------------------
# Deploy steps
# ---------------------------------------------------------------------------
build_images() {
  log_info "Building Docker images..."
  compose build
  log_ok "Images built."
}

start_stack() {
  log_info "Starting Docker Compose stack (--remove-orphans, volumes/data untouched)..."
  compose up -d --remove-orphans
  log_ok "Stack started."
}

wait_for_services() {
  log_info "Waiting for services to report healthy (up to 120s)..."
  local waited=0
  local interval=5
  local max_wait=120

  while (( waited < max_wait )); do
    local unhealthy
    unhealthy="$(compose ps --format '{{.Name}} {{.Health}}' 2>/dev/null \
      | awk '$2 != "" && $2 != "healthy" {print $1}' || true)"
    if [[ -z "${unhealthy}" ]]; then
      log_ok "All services with healthchecks report healthy."
      return
    fi
    sleep "${interval}"
    waited=$(( waited + interval ))
  done

  log_warn "Timed out after ${max_wait}s waiting for all services to become healthy."
  log_warn "Continuing to run verify.sh for a detailed breakdown."
}

run_verify() {
  log_info "Running deployment/verify.sh..."
  if [[ -x "${SCRIPT_DIR}/verify.sh" ]]; then
    if "${SCRIPT_DIR}/verify.sh"; then
      log_ok "verify.sh passed."
    else
      log_error "verify.sh reported failures. Deployment is up but not fully healthy."
      exit 1
    fi
  else
    log_warn "verify.sh not found or not executable — skipping automated verification."
  fi
}

print_summary() {
  local end_epoch duration
  end_epoch="$(date +%s)"
  duration=$(( end_epoch - DEPLOY_START_EPOCH ))

  echo ""
  log_ok "Deployment successful"
  echo ""
  log_info "Running containers:"
  compose ps
  echo ""
  echo "  Backend:    http://localhost:8080"
  echo "  Swagger UI: http://localhost:8080/swagger-ui.html"
  echo "  Actuator:   http://localhost:8080/actuator/health"
  echo ""
  log_ok "Deployment duration: ${duration}s"
}

main() {
  DEPLOY_START_EPOCH="$(date +%s)"

  check_docker_installed
  check_docker_running
  check_compose_available
  check_compose_file
  check_env_symlink
  check_env_file
  warn_if_working_tree_dirty

  build_images
  start_stack
  wait_for_services
  run_verify
  print_summary
}

main "$@"
