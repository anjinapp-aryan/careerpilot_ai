# Deploying to a single Oracle Cloud VM (Docker Compose)

This is the finalized production architecture for CareerPilot AI: **one Oracle Cloud VM
running Oracle Linux 9**, with every service — `backend`, `agent-service`, `redis`, `kafka`,
`zookeeper`, `minio` — in a single Docker Compose stack (`docker-compose.yml` at the repo
root). The frontend is deployed separately on Vercel; it is not part of this stack.

This doc covers VM-level setup only (installing Docker, running the stack under systemd).
For what each service does and how they're wired, see [CLAUDE.md](CLAUDE.md) and
`docker-compose.yml` itself — every non-obvious decision (why a service has no published
port, why a healthcheck uses a particular tool, why `SPRING_PROFILES_ACTIVE=prod` is set)
is documented inline there as a comment, not repeated here.

---

## 1. Install Docker CE + Docker Compose V2

Oracle Linux 9 is RHEL9-binary-compatible, so Docker's official **CentOS/RHEL** repository
is the correct, supported source — there is no Oracle-Linux-specific repo, and none is
needed.

```bash
# 0. Remove any conflicting Podman/Docker shims. Some Oracle Linux images ship
#    podman-docker, which aliases `docker` to Podman — must not coexist with real
#    Docker CE. Safe no-op if not installed.
sudo dnf remove -y podman podman-docker buildah runc

# 1. Docker CE — add prerequisite + official repo, then install
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo

sudo dnf install -y docker-ce docker-ce-cli containerd.io
# docker-ce        = the daemon (dockerd)
# docker-ce-cli    = the `docker` CLI binary
# containerd.io    = the container runtime docker-ce depends on
# (docker-buildx-plugin intentionally omitted — not needed for this repo's
#  single-arch `docker compose build`; skip unless you need multi-platform builds)

# 2. Docker Compose V2 — delivered as a CLI plugin (`docker compose`, no hyphen),
#    not the legacy standalone docker-compose v1 binary.
sudo dnf install -y docker-compose-plugin
```

## 2. Enable and start the Docker service

```bash
# 3. Enable — starts automatically on every future boot
sudo systemctl enable docker.service
sudo systemctl enable containerd.service

# 4. Start now (this boot)
sudo systemctl start docker.service
```

## 3. Add the `opc` user to the `docker` group

```bash
# 5. Lets opc run docker/docker compose without sudo. Takes effect on the next
#    login/new shell, not the current session.
sudo usermod -aG docker opc

# Apply immediately in the CURRENT shell without logging out (this session only —
# a fresh SSH login picks up the group membership automatically either way):
newgrp docker
```

## 4. Verify

```bash
# 6. Verify
docker --version
docker compose version
sudo systemctl is-active docker
sudo systemctl is-enabled docker
docker run --rm hello-world
```

Run `docker run hello-world` **as `opc`, without `sudo`** — that's the real proof that
group membership and daemon-socket permissions are correct. If it fails with a
permission-denied on `/var/run/docker.sock`, either `newgrp docker` didn't take in this
shell (fully log out and SSH back in instead) or step 5 ran after this shell was already
open.

Not covered here, since it wasn't required for this install: SELinux policy, firewalld
rules, and rootless Docker mode. Docker CE from this repo works under Oracle Linux 9's
default SELinux-enforcing + firewalld setup without extra steps for this stack's use case —
the only port ever exposed to the host is `8080` (backend), which is a firewalld/OCI VCN
Security List concern, not a Docker install concern. See section 6 below.

---

## 5. Running the stack — systemd unit

The stack is managed by a systemd unit (`careerpilot.service`) that wraps
`docker compose`, rather than running `docker compose up` by hand or relying on Docker's
own `restart:` policies alone. It:

- Waits for `docker.service` to be up before starting.
- Starts automatically on boot (`WantedBy=multi-user.target`).
- Restarts automatically if `docker compose up` itself fails (`Restart=on-failure`).
- Shuts down gracefully (`docker compose down --timeout 60`, long enough to clear the
  backend's own `spring.lifecycle.timeout-per-shutdown-phase` graceful-drain window).

Repository location: `/opt/careerpilot`. Environment file:
`/etc/careerpilot/careerpilot.env`.

**Env var architecture (resolved — this used to be a known gap, fixed by a migration)**:
`docker-compose.yml`'s `backend` and `agent-service` services load `careerpilot.env` in
full via `env_file:`, using canonical, app-facing variable names throughout (`MINIO_*`,
`SPRING_DATASOURCE_*`, `REDIS_URL`, `DATABASE_URL_PY`) — the same names
`application.yml`/`agent-service/app/config.py` actually read, no separate Compose-only
substitution layer to keep in sync. The one exception is the `minio` service itself,
which needs `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` (required by the upstream image) —
that's handled entirely inside `docker-compose.yml`, sourced from the same canonical
`MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` values.

`docker-compose.yml`'s `env_file: .env` is a path relative to `WorkingDirectory=/opt/careerpilot`
(set in `careerpilot.service`) — so `/opt/careerpilot/.env` must resolve to the real
`/etc/careerpilot/careerpilot.env` file. Rather than maintaining two separate files that
can drift, symlink them once during setup:

```bash
ln -s /etc/careerpilot/careerpilot.env /opt/careerpilot/.env
```

One real file, two paths pointing at it. `careerpilot.service`'s own `--env-file
/etc/careerpilot/careerpilot.env` flag (for Compose's YAML-level `${VAR}` substitution,
e.g. the `minio` rename above) keeps using the absolute path directly — both mechanisms
end up reading the identical content either way.

Install the unit:

```bash
sudo cp careerpilot.service /etc/systemd/system/careerpilot.service
sudo systemctl daemon-reload
sudo systemctl enable --now careerpilot
```

`docker` is assumed to resolve to `/usr/bin/docker` (the standard path for this RPM
install) — confirm with `which docker` if the unit fails to find it.

---

## 6. Network exposure

```
Internet
    │
 80 / 443
    │
  Nginx
    │
localhost:8080
```

The Spring Boot backend must never be reached directly from the internet. Only ports **80
(HTTP)** and **443 (HTTPS)** should be exposed publicly in the VM's OCI VCN Security List.
Nginx runs on the same VM, terminates the public-facing connection, and proxies to the
backend at `localhost:8080` — port 8080 itself should **not** remain open to `0.0.0.0/0`
once Nginx is in place.

During initial deployment, it's fine to open 8080 temporarily for direct testing (e.g.
confirming the backend responds before Nginx is configured) — but close that rule once
Nginx is up and proxying correctly.

Every other service (`redis`, `kafka`, `zookeeper`, `minio`, `agent-service`) has **no
published host port** by design — they're reachable only from other containers on the
Compose network, never from outside the VM. See the inline comments on each service in
`docker-compose.yml` for the specific reasoning per service.

`CORS_ALLOWED_ORIGINS` in `.env` must list the actual Vercel URL(s) the frontend is served
from, or the browser-hosted frontend will be rejected by the backend's CORS policy.

> **Not yet documented here**: the actual Nginx install/config (reverse-proxy block,
> TLS certificate setup) isn't written up in this doc yet — this section currently only
> states the target architecture and the resulting Security List rule. Say the word if you
> want that added as its own section.

---

## 7. Local-dev-only overlay — do not bring to the VM

`docker-compose.local.yml` re-opens host ports for `redis`/`kafka`/`minio`/`agent-service`
and unsets `SPRING_PROFILES_ACTIVE` for local development convenience (direct
`localhost:8088/docs`, `localhost:9001` MinIO console access, verbose logging, Swagger UI).
It is **not auto-merged** — Compose only auto-applies a file named exactly
`docker-compose.override.yml`, and this one was deliberately renamed to require an explicit
`-f docker-compose.local.yml` flag, precisely so it can never accidentally apply itself on
the VM. Do not copy this file to `/opt/careerpilot`, and never pass `-f
docker-compose.local.yml` in `careerpilot.service` or any production command.
