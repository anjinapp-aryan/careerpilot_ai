#!/usr/bin/env bash
# install-docker.sh — One-time Oracle Linux 9 VM setup: Docker CE + Compose V2.
#
# Idempotent: safe to rerun. Each step checks current state before acting so a
# second run is a fast no-op rather than an error.
#
# Usage: sudo ./install-docker.sh

set -Eeuo pipefail

# ---------------------------------------------------------------------------
# Logging helpers
# ---------------------------------------------------------------------------
readonly C_RESET='\033[0m'
readonly C_GREEN='\033[0;32m'
readonly C_YELLOW='\033[1;33m'
readonly C_RED='\033[0;31m'
readonly C_BLUE='\033[0;34m'

log_info()  { printf "${C_BLUE}[INFO]${C_RESET}  %s\n" "$*"; }
log_ok()    { printf "${C_GREEN}[OK]${C_RESET}    %s\n" "$*"; }
log_warn()  { printf "${C_YELLOW}[WARN]${C_RESET}  %s\n" "$*"; }
log_error() { printf "${C_RED}[ERROR]${C_RESET} %s\n" "$*" >&2; }

trap 'log_error "install-docker.sh failed at line ${LINENO} (exit code $?)"' ERR

# ---------------------------------------------------------------------------
# Prerequisite checks
# ---------------------------------------------------------------------------
require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    log_error "This script must be run as root (use sudo)."
    exit 1
  fi
}

require_dnf() {
  if ! command -v dnf >/dev/null 2>&1; then
    log_error "dnf not found — this script targets Oracle Linux 9 / RHEL9-compatible systems only."
    exit 1
  fi
}

# ---------------------------------------------------------------------------
# Steps
# ---------------------------------------------------------------------------
remove_podman() {
  log_info "Checking for conflicting Podman/Docker shims..."
  local pkgs=(podman podman-docker buildah runc)
  local installed=()
  for pkg in "${pkgs[@]}"; do
    if rpm -q "${pkg}" >/dev/null 2>&1; then
      installed+=("${pkg}")
    fi
  done
  if [[ ${#installed[@]} -eq 0 ]]; then
    log_ok "No conflicting Podman packages present."
    return
  fi
  log_warn "Removing conflicting packages: ${installed[*]}"
  dnf remove -y "${installed[@]}"
  log_ok "Podman/Docker shims removed."
}

install_docker_repo() {
  log_info "Ensuring dnf-plugins-core and Docker CE repo are present..."
  dnf install -y dnf-plugins-core
  if [[ -f /etc/yum.repos.d/docker-ce.repo ]]; then
    log_ok "Docker CE repo already configured."
  else
    dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
    log_ok "Docker CE repo added."
  fi
}

install_docker_packages() {
  log_info "Installing Docker CE, CLI, containerd, and Compose plugin..."
  # docker-buildx-plugin intentionally omitted — not needed for this repo's
  # single-arch `docker compose build`.
  dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
  log_ok "Docker packages installed."
}

enable_and_start_docker() {
  log_info "Enabling and starting docker.service + containerd.service..."
  systemctl enable docker.service
  systemctl enable containerd.service
  systemctl start docker.service
  log_ok "Docker daemon is running and enabled on boot."
}

add_user_to_docker_group() {
  local target_user="${SUDO_USER:-opc}"
  log_info "Adding user '${target_user}' to the docker group..."
  if ! id "${target_user}" >/dev/null 2>&1; then
    log_warn "User '${target_user}' does not exist on this system — skipping group add."
    return
  fi
  if id -nG "${target_user}" | grep -qw docker; then
    log_ok "User '${target_user}' is already in the docker group."
  else
    usermod -aG docker "${target_user}"
    log_ok "User '${target_user}' added to the docker group (takes effect on next login)."
  fi
}

verify_installation() {
  log_info "Verifying installation..."
  local failed=0

  if docker --version >/dev/null 2>&1; then
    log_ok "docker: $(docker --version)"
  else
    log_error "docker CLI not found or not working."
    failed=1
  fi

  if docker compose version >/dev/null 2>&1; then
    log_ok "docker compose: $(docker compose version --short 2>/dev/null || docker compose version)"
  else
    log_error "docker compose plugin not found or not working."
    failed=1
  fi

  if systemctl is-active --quiet docker; then
    log_ok "docker.service is active."
  else
    log_error "docker.service is not active."
    failed=1
  fi

  if systemctl is-enabled --quiet docker; then
    log_ok "docker.service is enabled on boot."
  else
    log_error "docker.service is not enabled on boot."
    failed=1
  fi

  if docker run --rm hello-world >/dev/null 2>&1; then
    log_ok "docker run hello-world succeeded (root context)."
  else
    log_warn "docker run hello-world failed as root — investigate before proceeding."
    failed=1
  fi

  if [[ "${failed}" -ne 0 ]]; then
    log_error "One or more verification checks failed. See above."
    exit 1
  fi

  log_ok "All verification checks passed."
  log_info "Note: run 'docker run --rm hello-world' as the non-root user (e.g. opc) after" \
           "logging out/in to confirm docker-group membership took effect."
}

main() {
  require_root
  require_dnf
  remove_podman
  install_docker_repo
  install_docker_packages
  enable_and_start_docker
  add_user_to_docker_group
  verify_installation
  log_ok "Docker installation complete."
}

main "$@"
