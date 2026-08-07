# CareerPilot AI — Deployment Runbook

Operational runbook for running CareerPilot AI (`backend`, `agent-service`, `redis`,
`minio`) on a single Oracle Cloud VM (Oracle Linux 9) via Docker
Compose. The frontend is deployed separately on Vercel and is not covered here.

For *why* the stack is shaped the way it is (network topology, healthcheck choices,
env var architecture), see [../DEPLOYMENT_ORACLE_CLOUD.md](../DEPLOYMENT_ORACLE_CLOUD.md)
and [../CLAUDE.md](../CLAUDE.md). This document is the *how* — the commands you actually
run, in order, for each operational task.

## Directory contents

```
deployment/
├── README.md            this file
├── install-docker.sh    one-time: install Docker CE + Compose plugin
├── setup-env.sh          one-time: prepare /etc/careerpilot + the .env symlink
├── deploy.sh              every deploy: build, start, verify
├── verify.sh              health check: run anytime, also called by deploy.sh
├── rollback.sh            roll back to a previous commit
├── backup.sh              back up config (not data)
└── careerpilot.service    systemd unit for the stack
```

## Design principle: Git chooses the version, deploy.sh deploys it

`deploy.sh` **never** runs `git fetch`/`pull`/`checkout` and takes **no arguments**. It
only builds and starts whatever is currently checked out in the working tree. Version
selection — which branch, tag, or commit to run — is a separate, explicit step you (or a
CI/CD pipeline) perform with plain Git commands *before* calling `deploy.sh`. This keeps
the script identical whether a human runs it by hand or a pipeline runs it after its own
checkout stage.

```bash
# Deploy latest main
git fetch origin && git checkout main && git pull --ff-only
./deployment/deploy.sh

# Deploy a tagged release
git fetch --tags && git checkout v1.3.2
./deployment/deploy.sh

# Deploy a specific hotfix commit
git checkout 8fd92aa
./deployment/deploy.sh
```

---

## First-time VM setup

Run once, from a fresh Oracle Linux 9 VM.

```bash
# 1. Install Docker CE + Compose plugin, enable/start the daemon
sudo ./deployment/install-docker.sh

# 2. Clone the repo, then start from the checked-in template and fill in
#    real values — setup-env.sh will refuse to proceed if careerpilot.env
#    is missing, since it never generates one for you.
sudo git clone <repo-url> /opt/careerpilot
sudo mkdir -p /etc/careerpilot
sudo cp /opt/careerpilot/careerpilot.env.example /etc/careerpilot/careerpilot.env
sudo nano /etc/careerpilot/careerpilot.env   # fill in every REPLACE_ME value

# 3. Lock down permissions on careerpilot.env and create the
#    /opt/careerpilot/.env -> /etc/careerpilot/careerpilot.env symlink that
#    docker-compose.yml's `env_file: - .env` relies on
sudo ./deployment/setup-env.sh

# 4. Install and enable the systemd unit
sudo cp deployment/careerpilot.service /etc/systemd/system/careerpilot.service
sudo systemctl daemon-reload
sudo systemctl enable --now careerpilot

# 5. Confirm everything is healthy
./deployment/verify.sh
```

After this, `careerpilot.service` starts the stack automatically on every boot — the
steps below are for deploying new code, not for re-running initial setup.

---

## Regular deployment

```bash
cd /opt/careerpilot

# Choose the version (Git only — see "Design principle" above)
git fetch origin && git checkout main && git pull --ff-only

# Deploy it
./deployment/deploy.sh
```

`deploy.sh` will, in order: validate prerequisites (Docker running, Compose available,
`docker-compose.yml` present, `.env` symlink present, `careerpilot.env` present, warn if
the working tree is dirty), `docker compose build`, `docker compose up -d
--remove-orphans`, wait for containers to report healthy, run `verify.sh`, then print a
summary (running containers, Backend/Swagger/Actuator URLs, deployment duration).

It never touches volumes, the database, or MinIO data, and never modifies
`careerpilot.env`.

Recommended: take a config backup before deploying (see below) so a bad deploy is one
`rollback.sh` away from a known-good config.

---

## Rollback

`rollback.sh` rolls back **application code only** — it stops the stack, checks out the
previous commit (or an explicit ref you pass), rebuilds, restarts, and verifies health.
Unlike `deploy.sh`, it *is* allowed to touch Git state, since rolling back is inherently a
Git operation.

```bash
# Roll back one commit
./deployment/rollback.sh

# Roll back to a specific ref
./deployment/rollback.sh /opt/careerpilot v1.3.1
```

It refuses to run if the working tree is dirty (commit, stash, or discard local changes
first), and it never touches the database, volumes, MinIO data, or `careerpilot.env`.

---

## Backup

Backs up **configuration only** — `careerpilot.env`, `careerpilot.service`,
`docker-compose.yml` — timestamped under `/var/backups/careerpilot`, keeping the latest
10 and pruning older ones automatically. It does **not** back up volumes, the database,
MinIO data, images, or logs — those have their own recovery paths (Neon point-in-time
recovery, MinIO bucket replication, etc.).

```bash
sudo ./deployment/backup.sh
```

Run it before every deploy, or on a cron schedule for drift protection — it's cheap and
idempotent to run often.

---

## Health verification

```bash
./deployment/verify.sh
```

Checks: Docker daemon, Compose plugin, the Compose network, every container's health
status (`redis`, `minio`, `agent-service`, `backend`), and the two
HTTP health endpoints (`backend` `/actuator/health`, `agent-service` `/health`, both
checked via `docker exec` since neither port is published to the host). Exits non-zero if
anything critical is down — safe to wire into external monitoring/alerting.

---

## Common troubleshooting commands

```bash
# Is the systemd unit up? When did it last restart?
sudo systemctl status careerpilot

# Tail the unit's own logs (docker compose up/down output, restarts)
sudo journalctl -u careerpilot -f

# What's running, and what's its reported health?
docker compose -f /opt/careerpilot/docker-compose.yml ps

# Logs for one service (add -f to follow, --tail=200 to limit)
docker compose -f /opt/careerpilot/docker-compose.yml logs backend
docker compose -f /opt/careerpilot/docker-compose.yml logs -f agent-service

# Every container across the whole VM, not just this stack
docker ps -a

# Inspect a single container's health check history
docker inspect --format '{{json .State.Health}}' <container_id> | jq

# Confirm the env_file mechanism is actually reaching a container
docker compose -f /opt/careerpilot/docker-compose.yml exec backend env | grep -E 'SHUTDOWN_TIMEOUT|DB_POOL_MAX'

# Confirm the .env symlink resolves correctly
readlink -f /opt/careerpilot/.env
# should print: /etc/careerpilot/careerpilot.env

# Restart just one service without a full deploy
docker compose -f /opt/careerpilot/docker-compose.yml restart backend

# Full stack restart via systemd (preferred over raw `docker compose up/down`
# on the VM, since it keeps systemd's view of the unit's state consistent)
sudo systemctl restart careerpilot

# Disk pressure from old images/build cache
docker system df
docker image prune -f   # safe: only removes dangling, untagged images
```

### If `deploy.sh` fails at the prerequisite checks
- `docker daemon not running` → `sudo systemctl status docker`, `sudo systemctl start docker`
- `.env symlink not found` → re-run `sudo ./deployment/setup-env.sh`
- `careerpilot.env not found` → it must be created manually at
  `/etc/careerpilot/careerpilot.env` first; no script generates it

### If a container is `unhealthy`
- Check its logs first (`docker compose logs <service>`) — the healthcheck failing is
  usually a symptom, not the root cause
- `backend` has a 45s `start_period` before healthchecks count against it — a fresh boot
  failing health in the first 45s is expected, not a bug

### If `verify.sh` reports HTTP check failures but containers look healthy
- The HTTP checks run `docker exec` inside the container itself (no host port is
  published for `agent-service`, and `backend`'s health check also runs internally) — a
  failure here means the app process itself isn't responding, not a network/firewall
  issue
