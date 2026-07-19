#!/usr/bin/env bash
# verify.sh — Complete production verification of the CareerPilot AI stack.
#
# Checks Docker, Compose, the Compose network, every container's health
# status, and the two HTTP health endpoints (backend actuator, agent-service).
# Exits non-zero if any critical service is unhealthy or unreachable.
#
# Usage: ./verify.sh [REPO_DIR]
#   REPO_DIR defaults to the repository root this script lives in (so
#   deploy.sh can call it with no arguments and get consistent results).

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

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEFAULT_REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly REPO_DIR="${1:-${DEFAULT_REPO_DIR}}"
readonly ENV_FILE="/etc/careerpilot/careerpilot.env"
readonly COMPOSE_FILE="${REPO_DIR}/docker-compose.yml"

# `set -e` stays active, but every check below guards its risky command inside
# an if/test (or `|| true`) so a failing check records a failure and continues
# instead of aborting the whole script on the first problem found.
FAILURES=0
record_failure() { FAILURES=$(( FAILURES + 1 )); }

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

check_docker_daemon() {
  log_info "Checking Docker daemon..."
  if docker info >/dev/null 2>&1; then
    log_ok "Docker daemon is running."
  else
    log_error "Docker daemon is not running."
    record_failure
  fi
}

check_compose_plugin() {
  log_info "Checking Docker Compose plugin..."
  if docker compose version >/dev/null 2>&1; then
    log_ok "Docker Compose plugin available."
  else
    log_error "Docker Compose plugin not found."
    record_failure
  fi
}

check_container_health() {
  local service="$1"
  log_info "Checking container health: ${service}..."
  local cid
  cid="$(compose ps -q "${service}" 2>/dev/null || true)"
  if [[ -z "${cid}" ]]; then
    log_error "${service}: container not found (not running?)."
    record_failure
    return
  fi

  local status
  status="$(docker inspect --format '{{.State.Status}}' "${cid}" 2>/dev/null || echo "unknown")"
  if [[ "${status}" != "running" ]]; then
    log_error "${service}: container status is '${status}', expected 'running'."
    record_failure
    return
  fi

  local health
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${cid}" 2>/dev/null || echo "unknown")"
  case "${health}" in
    healthy)
      log_ok "${service}: running, health=healthy."
      ;;
    none)
      log_ok "${service}: running (no healthcheck defined)."
      ;;
    *)
      log_error "${service}: running but health=${health}."
      record_failure
      ;;
  esac
}

check_network() {
  log_info "Checking Docker Compose network..."
  local net
  net="$(compose ps --format '{{.Networks}}' 2>/dev/null | head -n1 || true)"
  if [[ -z "${net}" ]]; then
    log_error "Could not determine Compose network — no running containers?"
    record_failure
    return
  fi
  if docker network inspect "${net}" >/dev/null 2>&1; then
    log_ok "Compose network '${net}' exists."
  else
    log_error "Compose network '${net}' not found."
    record_failure
  fi
}

check_backend_http() {
  log_info "Checking backend HTTP health endpoint..."
  local backend_cid
  backend_cid="$(compose ps -q backend 2>/dev/null || true)"
  if [[ -z "${backend_cid}" ]]; then
    log_error "backend: container not found — skipping HTTP check."
    record_failure
    return
  fi
  if docker exec "${backend_cid}" wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health 2>/dev/null; then
    log_ok "backend: /actuator/health responded OK."
  else
    log_error "backend: /actuator/health did not respond OK."
    record_failure
  fi
}

check_agent_http() {
  log_info "Checking agent-service HTTP health endpoint..."
  local agent_cid
  agent_cid="$(compose ps -q agent-service 2>/dev/null || true)"
  if [[ -z "${agent_cid}" ]]; then
    log_error "agent-service: container not found — skipping HTTP check."
    record_failure
    return
  fi
  if docker exec "${agent_cid}" python3 -c "import urllib.request; urllib.request.urlopen('http://localhost:8088/health', timeout=3)" 2>/dev/null; then
    log_ok "agent-service: /health responded OK."
  else
    log_error "agent-service: /health did not respond OK."
    record_failure
  fi
}

print_summary() {
  echo ""
  echo "================= Deployment Verification Summary ================="
  compose ps
  echo "======================================================================"
  if [[ "${FAILURES}" -eq 0 ]]; then
    log_ok "All checks passed."
  else
    log_error "${FAILURES} check(s) failed. See above for details."
  fi
}

main() {
  check_docker_daemon
  check_compose_plugin
  check_network

  for svc in redis zookeeper kafka minio agent-service backend; do
    check_container_health "${svc}"
  done

  check_backend_http
  check_agent_http

  print_summary

  if [[ "${FAILURES}" -gt 0 ]]; then
    exit 1
  fi
  exit 0
}

main "$@"
