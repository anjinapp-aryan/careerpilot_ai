# Deploying to a single Oracle Cloud VM (Docker Compose)

This is the finalized production architecture for CareerPilot AI: **one Oracle Cloud VM
running Oracle Linux 9**, with every service — `backend`, `agent-service`, `redis`, `kafka`,
`zookeeper`, `minio` — in a single Docker Compose stack (`docker-compose.yml` at the repo
root). The frontend is deployed separately on Vercel; it is not part of this stack.

**This doc explains the *why* of the VM-level architecture** (repo choice, systemd design,
network topology, env var wiring) — the non-obvious decisions behind each piece. For the
**day-to-day *how*** (exact commands for first-time setup, regular deploys, rollback,
backup, health checks, troubleshooting), see the operational runbook:
**[deployment/README.md](deployment/README.md)**. The two documents are complementary —
this one tells you why the pieces are shaped this way, that one tells you what to type.

For what each service does and how they're wired at the application level, see
[CLAUDE.md](CLAUDE.md) and `docker-compose.yml` itself — every non-obvious per-service
decision (why a service has no published port, why a healthcheck uses a particular tool,
why `SPRING_PROFILES_ACTIVE=prod` is set) is documented inline there as a comment, not
repeated here.

All VM automation lives under [deployment/](deployment/):

```
deployment/
├── README.md              operational runbook — start here for commands
├── install-docker.sh      one-time: install Docker CE + Compose plugin
├── setup-env.sh            one-time: prepare /etc/careerpilot + the .env symlink
├── deploy.sh                every deploy: build, start, verify (no Git logic — see §5)
├── verify.sh                health check: run anytime, also called by deploy.sh
├── rollback.sh              roll back application code to a previous commit
├── backup.sh                back up config (not data)
└── careerpilot.service      systemd unit for the stack
```

---

## 1. Install Docker CE + Docker Compose V2

Oracle Linux 9 is RHEL9-binary-compatible, so Docker's official **CentOS/RHEL** repository
is the correct, supported source — there is no Oracle-Linux-specific repo, and none is
needed.

Run the install script from the repo checkout (idempotent — safe to rerun):

```bash
sudo ./deployment/install-docker.sh
```

It performs, in order: remove conflicting Podman/Docker shims (some Oracle Linux images
ship `podman-docker`, which aliases `docker` to Podman — must not coexist with real Docker
CE) → add the Docker CE CentOS/RHEL repo → install `docker-ce`, `docker-ce-cli`,
`containerd.io`, `docker-compose-plugin` (Compose V2 ships as the `docker compose` CLI
plugin, not the legacy standalone `docker-compose` v1 binary) → enable + start
`docker.service`/`containerd.service` → add the invoking sudo user (or `opc` if run via
plain `sudo`) to the `docker` group → verify with `docker run hello-world`.

`docker-buildx-plugin` is intentionally not installed — not needed for this repo's
single-arch `docker compose build`; skip it unless you need multi-platform builds.

Run `docker run hello-world` **as the non-root user, without `sudo`**, after logging back
in — that's the real proof group membership and daemon-socket permissions are correct. If
it fails with a permission-denied on `/var/run/docker.sock`, the group membership hasn't
taken effect in this shell yet — fully log out and SSH back in.

Not covered by the script, since it wasn't required for this install: SELinux policy,
firewalld rules, and rootless Docker mode. Docker CE from this repo works under Oracle
Linux 9's default SELinux-enforcing + firewalld setup without extra steps for this stack's
use case — the only port ever exposed to the host is `8080` (backend), which is a
firewalld/OCI VCN Security List concern, not a Docker install concern. See §6 below.

---

## 2. Prepare production configuration

The stack's entire runtime configuration lives in one file, `careerpilot.env`, which
`docker-compose.yml`'s `backend` and `agent-service` services load in full via `env_file:`
(see §4 for the architecture behind that). This file contains real secrets (JWT signing
key, database password, AI provider API keys) and **must never be committed to git** — it's
listed in `.gitignore` for exactly that reason.

**Start from the checked-in template**, which documents every variable with placeholders
instead of real values:

```bash
sudo mkdir -p /etc/careerpilot
sudo cp careerpilot.env.example /etc/careerpilot/careerpilot.env
sudo nano /etc/careerpilot/careerpilot.env   # fill in every REPLACE_ME value
```

Then run the setup script (idempotent) to lock down permissions and create the symlink
`docker-compose.yml` relies on:

```bash
sudo ./deployment/setup-env.sh
```

It: creates `/etc/careerpilot` if missing → verifies `careerpilot.env` exists (it will
**not** generate one for you — a missing file fails loudly rather than silently booting
with no config) → sets `chmod 600 root:root` on it → creates
`<repo>/.env -> /etc/careerpilot/careerpilot.env` via `ln -sfn` → verifies the
symlink resolves and is readable. `<repo>` is **wherever you actually cloned the repo** —
the script derives this automatically from its own location on disk (it doesn't assume
`/opt/careerpilot`; see §3).

> **If you're rotating credentials that were ever committed to git** (this repo had that
> exact incident — a real `JWT_SECRET` and database password sat in an early commit before
> the file was gitignored): generate fresh values, don't reuse anything that ever appeared
> in a commit, a chat transcript, or a screen share. `openssl rand -hex 48` for
> `JWT_SECRET`.

---

## 3. Where the code lives

```bash
sudo git clone <repo-url> /opt/careerpilot
```

`/opt/careerpilot` above is an **example, not a requirement**. Every script under
`deployment/` (`setup-env.sh`, `deploy.sh`, `verify.sh`, `rollback.sh`, `backup.sh`)
derives its own repository root dynamically from its own location on disk
(`SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`, then `REPO_DIR` is
`SCRIPT_DIR`'s parent) — none of them hardcode `/opt/careerpilot`. Clone the repo anywhere
(`/opt/careerpilot/careerpilot_ai`, `/home/opc/careerpilot`, a nested path, whatever your
VM's convention is) and every script keeps working unmodified, as long as you always run
them from inside that checkout (`./deployment/<script>.sh`, not a copy elsewhere).

**The one exception is `careerpilot.service`.** systemd unit files are static — a
`WorkingDirectory=` line cannot run `dirname`/`pwd` at daemon-reload time the way a shell
script can. So `WorkingDirectory=` in `deployment/careerpilot.service` **must be edited by
hand** to match wherever you actually cloned the repo, before installing the unit (§5).
This was the exact bug found in production: the repo was cloned to
`/opt/careerpilot/careerpilot_ai`, but `WorkingDirectory=/opt/careerpilot` (the example
path, left unedited) caused the stack to run against the wrong directory — `setup-env.sh`'s
symlink target followed suit, since at the time it *also* defaulted to the same hardcoded
path instead of deriving it. `setup-env.sh` (and every other script) now derives its own
default correctly regardless of clone location; `careerpilot.service` is the sole remaining
place a path must be set manually, because systemd leaves no other option.

---

## 4. Env var architecture

`docker-compose.yml`'s `backend` and `agent-service` services load `careerpilot.env` in
full via `env_file:`, using canonical, app-facing variable names throughout (`MINIO_*`,
`SPRING_DATASOURCE_*`, `REDIS_URL`, `DATABASE_URL_PY`) — the same names
`application.yml`/`agent-service/app/config.py` actually read, no separate Compose-only
substitution layer to keep in sync. The one exception is the `minio` service itself,
which needs `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` (required by the upstream image) —
that's handled entirely inside `docker-compose.yml`, sourced from the same canonical
`MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` values.

`docker-compose.yml`'s `env_file: .env` is a path relative to `WorkingDirectory` (set in
`careerpilot.service`, per §3 above) — so `<repo>/.env` must resolve to the real
`/etc/careerpilot/careerpilot.env` file, whatever `<repo>` actually is on this VM.
That's exactly what `setup-env.sh` (§2) creates: one real file, two paths pointing at it,
so there's nothing to keep in sync manually. `careerpilot.service`'s own `--env-file
/etc/careerpilot/careerpilot.env` flag (for Compose's YAML-level `${VAR}` substitution,
e.g. the `minio` rename above) keeps using the absolute path directly — both mechanisms
end up reading the identical content either way.

---

## 5. Running the stack — systemd unit

The stack is managed by a systemd unit (`careerpilot.service`) that wraps
`docker compose`, rather than running `docker compose up` by hand or relying on Docker's
own `restart:` policies alone. It:

- Waits for `docker.service` to be up before starting.
- Starts automatically on boot (`WantedBy=multi-user.target`).
- Restarts automatically if `docker compose up` itself fails (`Restart=on-failure`,
  `RestartSec=10`).
- Shuts down gracefully (`docker compose down --timeout 60`, long enough to clear the
  backend's own `spring.lifecycle.timeout-per-shutdown-phase` graceful-drain window;
  `TimeoutStopSec=120` gives systemd itself even more headroom before it force-kills).
- Contains no secrets — it only references `/etc/careerpilot/careerpilot.env` by path.

Install the unit (note the file lives under `deployment/`, not the repo root):

```bash
sudo cp deployment/careerpilot.service /etc/systemd/system/careerpilot.service

# REQUIRED: edit WorkingDirectory in the copy above to match wherever you
# actually cloned the repo (see §3) — it defaults to /opt/careerpilot as an
# example. Skipping this step is the exact bug that motivated this section:
# the unit silently runs `docker compose` against the wrong directory (or a
# directory that doesn't exist) if left unedited and the repo lives elsewhere.
sudo nano /etc/systemd/system/careerpilot.service   # fix WorkingDirectory=

sudo systemctl daemon-reload
sudo systemctl enable --now careerpilot
```

`docker` is assumed to resolve to `/usr/bin/docker` (the standard path for this RPM
install) — confirm with `which docker` if the unit fails to find it.

For the first deploy after installing the unit, and for every deploy after that
(including which *version* of the code to run), see §7 and
[deployment/README.md](deployment/README.md).

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

`CORS_ALLOWED_ORIGINS` in `careerpilot.env` must list the actual Vercel URL(s) the
frontend is served from, or the browser-hosted frontend will be rejected by the backend's
CORS policy.

> **Not yet documented here**: the actual Nginx install/config (reverse-proxy block,
> TLS certificate setup) isn't written up in this doc yet — this section currently only
> states the target architecture and the resulting Security List rule. Say the word if you
> want that added as its own section.

---

## 7. Deploying code — Git chooses the version, `deploy.sh` deploys it

`deployment/deploy.sh` deliberately has **no Git logic** — it never runs `fetch`, `pull`,
or `checkout`, and takes no branch/tag/commit argument. It only builds and starts whatever
is *already checked out* in the repository it lives in (wherever that is — see §3, it
doesn't assume `/opt/careerpilot`). Version selection is a separate, explicit step
performed with plain Git commands first — this keeps the script identical whether a human
runs it by hand or a CI/CD pipeline runs it after its own checkout stage:

```bash
cd /opt/careerpilot   # or wherever you actually cloned the repo — see §3
git fetch origin && git checkout main && git pull --ff-only   # or: git checkout v1.3.2

./deployment/deploy.sh
```

`deploy.sh` validates prerequisites, runs `docker compose build` + `up -d
--remove-orphans`, waits for containers to report healthy, runs `verify.sh`, and prints a
summary (running containers, Backend/Swagger/Actuator URLs, deployment duration). It never
touches volumes, the database, MinIO data, or `careerpilot.env`.

For rollback, backup, and standalone health verification, see
[deployment/README.md](deployment/README.md) — it covers `rollback.sh`, `backup.sh`,
`verify.sh`, and a troubleshooting command reference (`systemctl status careerpilot`,
`docker compose logs`, etc.) in full.

---

## 8. Local-dev-only overlay — do not bring to the VM

`docker-compose.local.yml` re-opens host ports for `redis`/`kafka`/`minio`/`agent-service`
and unsets `SPRING_PROFILES_ACTIVE` for local development convenience (direct
`localhost:8088/docs`, `localhost:9001` MinIO console access, verbose logging, Swagger UI).
It is **not auto-merged** — Compose only auto-applies a file named exactly
`docker-compose.override.yml`, and this one was deliberately renamed to require an explicit
`-f docker-compose.local.yml` flag, precisely so it can never accidentally apply itself on
the VM. Do not copy this file to the VM's repo checkout, and never pass `-f
docker-compose.local.yml` in `careerpilot.service` or any production command.
