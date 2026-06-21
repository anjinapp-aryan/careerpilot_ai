# Docker & Compose Services Skill

## Purpose
Start, stop, inspect, and debug a multi-container stack with Docker Compose. Generic to any
compose-based project — exact service names, ports, and which services exist live in that
repo's own `docker-compose.yml` and docs, not here.

---

## Workflows

### Workflow: First-Time Setup

```bash
cp .env.example .env   # if the project uses one
# Fill in required values per the project's own docs
```

After editing, sanity-check that the critical vars are actually non-empty before starting
anything — a silently-empty required var is the single most common first-run failure.

---

### Workflow: Start the Stack

```bash
docker compose --env-file .env up -d
docker compose logs -f      # watch startup, Ctrl+C to stop following
```

Wait for each service's own "ready" signal in its logs (a DB saying "ready to accept
connections", a broker saying "started", etc.) before assuming the stack is up.

---

### Workflow: Check Status

```bash
docker compose ps
```

All services should show a running/healthy state. Anything in a restart loop or "Exited" needs
investigation before relying on the stack.

---

### Workflow: View Logs

```bash
docker compose logs -f <service>          # follow one service
docker compose logs --tail=50 <service>   # last N lines, no follow
docker compose logs | grep -i error       # search across all services
```

---

### Workflow: Stop / Restart

```bash
docker compose down                  # stop, keep volumes (data preserved)
docker compose restart <service>     # restart one service
```

---

### Workflow: Full Reset (Destructive)

```bash
docker compose down -v               # stop AND delete volumes — data is gone
docker volume ls | grep <project>    # confirm volumes actually removed
docker compose --env-file .env up -d
```

Only do this in a disposable dev environment, and only when you actually intend to lose data.

---

### Workflow: Rebuild a Service After Code Changes

```bash
docker compose build --no-cache <service>
docker compose up -d <service>
```

---

### Workflow: Run a Command Inside a Container

```bash
docker compose exec <service> <command>
docker compose exec <service> env | grep <VAR_PREFIX>   # confirm config landed in the container
```

---

### Workflow: Resource Usage & Cleanup

```bash
docker stats                  # live CPU/memory per container
docker system df              # disk usage summary
docker system prune -a        # remove unused images/containers/networks
docker volume prune           # remove unused volumes (careful — irreversible)
```

---

## Checklists

### ✅ Pre-Start

- [ ] Docker daemon running (`docker ps` succeeds)
- [ ] `docker compose version` shows v2+
- [ ] Env file present and populated with all required vars
- [ ] All ports the stack needs are free on the host

### ✅ Post-Startup

- [ ] `docker compose ps` shows every service running/healthy
- [ ] Each stateful service (DB, cache, broker, object store) responds when probed directly
- [ ] No more than a couple of error-level lines in the aggregate logs at this point

### ✅ Before Relying on the Full Stack

- [ ] All project images actually built (`docker images | grep <project>`)
- [ ] No dangling images left over from prior builds
- [ ] Sufficient free disk space (`docker system df`)

---

## Troubleshooting

### ❌ "docker compose: command not found"

Means Docker Compose v1 (the standalone `docker-compose` binary) or no compose plugin at all.
Install/upgrade Docker Desktop or the `docker-compose-plugin` package to get v2 (`docker
compose`, no hyphen).

### ❌ Port Already Allocated

```bash
docker ps                       # see if a leftover container holds it
lsof -i :<port>                 # see if a host process holds it (Linux/Mac)
```
Stop whichever owns it, then retry.

### ❌ Container Exits Immediately

```bash
docker compose logs <service>
```
Almost always one of: a missing/empty required env var, a port conflict, a missing
volume/mount the entrypoint expects, or a dependency (DB/broker) not ready yet — add a
`depends_on` health condition or a startup retry instead of a fixed `sleep`.

### ❌ A Service Can't Reach Another Service

Inside the compose network, services reach each other by **service name**, not `localhost` —
`http://backend:8080`, not `http://localhost:8080`, when calling from inside another
container.

### ❌ Out of Disk Space

```bash
docker system df
docker system prune -a
```
If the host disk itself (not just Docker's data) is full, that needs cleaning up separately.

### ❌ Container Killed with OOMKilled

Check `docker stats` for actual usage, then raise the memory limit Docker Desktop is allowed,
or set an explicit `mem_limit` per service in `docker-compose.yml` if one service is the
runaway.

### ❌ Build Fails Partway Through

```bash
docker compose build --no-cache <service> 2>&1 | tail -50
```
Read the actual failing step — language-toolchain errors (Maven/npm/pip) show up here, not in
`docker compose up` output.

---

## Tips & Best Practices

1. Always pass `--env-file` explicitly when starting — compose auto-loads `.env` for variable
   substitution in the YAML, but services still need vars injected via the `environment:`
   block, so don't assume one implies the other.
2. Watch logs during the first startup after any change — silent failures show up there before
   anywhere else.
3. `down -v` deletes volumes — never run it against anything with data you care about without
   a backup.
4. Rebuild explicitly after dependency/code changes (`--no-cache` if you suspect stale layers)
   — compose doesn't automatically know your source changed.
5. Service-to-service traffic uses service names; only host-to-container traffic uses
   `localhost` + the published port.
