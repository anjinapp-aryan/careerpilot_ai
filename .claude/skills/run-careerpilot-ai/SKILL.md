---
name: run-careerpilot-ai
description: Build, launch, and drive the CareerPilot AI stack (Spring Boot backend + Python LangGraph agent service + React/Vite frontend). Use when asked to run, start, boot, smoke-test, screenshot, or verify CareerPilot locally — the whole stack via docker compose, then the driver.mjs HTTP harness for an end-to-end register→login→dashboard→jobs flow.
---

# Run CareerPilot AI

CareerPilot AI is a three-service stack: a **Spring Boot 4 / Java 25** backend (`:8080`),
a **Python FastAPI + LangGraph** agent service (`:8088`), and a **React 18 + Vite** frontend
(`:5173`). They share one **Neon Postgres** (cloud, external to compose) plus local
Redis / Kafka / MinIO. The fastest faithful way to run everything is **docker compose**, and
the way to *drive* it is the committed HTTP harness
[.claude/skills/run-careerpilot-ai/driver.mjs](.claude/skills/run-careerpilot-ai/driver.mjs) —
a Node script (no deps) that probes all three services and runs a real
`register → login → dashboard → create-job` flow against the live backend + Neon DB.

All paths below are relative to the repo root (`d:\WORK_SPACE\careerpilot_ai`). This was
verified on **Windows 11** with Docker Desktop, Node 24, Java 25, Maven 3.9, Python 3.12.

## Prerequisites

- **Docker Desktop** (the only hard requirement for the agent path — Java/Node/Python
  are baked into the images).
- A populated **`.env`** at the repo root with real secrets: `JWT_SECRET` (≥32 chars),
  `GEMINI_API_KEY`, and Neon `DATABASE_URL` / `DATABASE_URL_PY` using Neon's **direct**
  endpoint (no `-pooler` in the hostname). The working tree already has a real `.env`.
- For the **screenshot** step: Microsoft Edge (present by default on Windows 11). There is
  **no `chromium-cli`** on this machine — use Edge's `--headless --screenshot` instead.

## Run (agent path) — this is the one to use

### 1. Bring the stack up

Images are already built in this repo, so this recreates containers in seconds (add
`--build` only after changing source):

```bash
docker compose --env-file .env up -d
```

Brings up `redis`, `zookeeper`, `kafka`, `minio`, `agent-service`, `backend`, `frontend`.
Postgres is **not** in compose — backend talks directly to Neon.

### 2. Wait for health (backend is slowest — ~20s)

```bash
for i in $(seq 1 30); do
  b=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health 2>/dev/null)
  a=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8088/health 2>/dev/null)
  f=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5173/ 2>/dev/null)
  echo "[$i] backend=$b agent=$a frontend=$f"
  if [ "$b" = "200" ] && [ "$a" = "200" ] && [ "$f" = "200" ]; then echo ALL_UP; break; fi
  sleep 5
done
```

`backend=000` for the first few iterations is normal (Spring Boot still booting).

### 3. Drive it with the harness

```bash
node .claude/skills/run-careerpilot-ai/driver.mjs --e2e
```

Expected: `10/10 checks passed` / `OK`. The `--e2e` flag runs a real
`POST /api/auth/register → POST /api/auth/login → GET /api/dashboard → POST /api/jobs`
against the live backend and Neon. Each run inserts a fresh `smoke+<timestamp>@careerpilot.local`
org/user into Neon (harmless, but it accumulates rows).

Useful flags (see header of `driver.mjs`):
- `node .claude/skills/run-careerpilot-ai/driver.mjs` — probe-only, all three services, no e2e.
- `--backend-only` / `--agent-only` / `--no-frontend` — scope to one service.
- `--backend http://host:port --agent ... --frontend ...` — point at non-default URLs.

Exit codes: `0` all checks passed, `1` a target failed (see the FAIL list), `2` bad args.

### 4. Screenshot the frontend (GUI verification)

`chromium-cli` is not installed; drive Edge headless instead. This wrote a 214 KB PNG of the
live login page:

```powershell
$edge = @("$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe","${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
$out = "d:\WORK_SPACE\careerpilot_ai\.claude\skills\run-careerpilot-ai\frontend-login.png"
& $edge --headless=new --disable-gpu --hide-scrollbars --window-size=1280,900 --screenshot="$out" "http://localhost:5173/"
Start-Sleep -Seconds 2
if (Test-Path $out) { "OK size=$((Get-Item $out).Length) bytes" } else { "NO SCREENSHOT" }
```

The PNG lands at `.claude/skills/run-careerpilot-ai/frontend-login.png`. Open it — you should
see the "Your AI copilot for the entire career journey" splash and a working sign-in form.

### 5. Tear down

```bash
docker compose --env-file .env down
```

## Run (human path) — for eyeballing in a browser

`docker compose --env-file .env up -d`, then open:
- Frontend: http://localhost:5173
- Backend Swagger: http://localhost:8080/swagger-ui.html
- Agent service docs: http://localhost:8088/docs
- MinIO console: http://localhost:9001 (minioadmin / minioadmin)

### Per-service, no Docker (slower to set up)

There is **no `mvnw` wrapper** in `backend/` — use system Maven (Java 25 required):

```bash
cd backend && mvn spring-boot:run                              # needs Redis+Kafka+MinIO+Neon reachable
cd agent-service && pip install -r requirements.txt && uvicorn app.main:app --reload --port 8088
cd frontend && npm install && npm run dev                      # this gives real Vite dev mode on :5173
```

## Gotchas

- **The frontend driver reports "production-built bundle", not Vite dev mode.** The compose
  `frontend` image serves the pre-built `dist/` through nginx — there is no `/@vite/client` in
  the served HTML. That's expected for the docker path. Only `npm run dev` gives true dev mode;
  the driver detects and reports both.
- **`docker compose up` without `--env-file .env` silently misconfigures the stack.** The
  backend/agent read secrets from `.env`; always pass `--env-file .env`. (Compose auto-loads
  `.env` for variable *substitution*, but the services need it injected — keep the flag.)
- **Neon must be the direct (non-pooler) endpoint.** Flyway DDL and the LangGraph
  `PostgresSaver` use prepared statements that break under Neon's transaction-mode pgBouncer
  pooler. If backend boot fails with prepared-statement errors, check for `-pooler` in the
  `DATABASE_URL*` hostnames in `.env`.
- **Agent `/health` reports the *running* provider/model**, e.g. `provider=gemini
  model=gemini-2.5-flash` — even though `.env` also carries `NVIDIA_*` / `PRIMARY_PROVIDER`
  keys. Don't assume the model from `.env`; trust `/health`.
- **No `chromium-cli` and no `mvnw` on this Windows box.** Use Edge headless for screenshots
  and system `mvn` for non-docker backend runs.
- **The driver intentionally does *not* exercise the agent `/runs` workflow** (it would burn
  Gemini tokens and hit the human-approval interrupt). It only confirms the agent's routes
  exist via `/openapi.json`. To test the full LangGraph workflow, drive it from the UI /
  `POST /api/workflows/run` instead.
- **`backend=000` in the health poll is not an error** — Spring Boot takes ~15–20s; the agent
  and frontend are ready almost immediately.

## Troubleshooting

- **`driver.mjs` reports `GET /actuator/health — fetch failed`** → backend not up yet (re-run
  the health poll) or it crashed on boot: `docker compose logs backend --tail=80`.
- **`POST /api/auth/register — HTTP 500`** → almost always a DB connection problem (Neon
  unreachable, wrong/pooler endpoint, or pgvector extension missing). Check backend logs and
  the `DATABASE_URL*` values.
- **Screenshot step prints `NO SCREENSHOT`** → Edge not found at the standard paths; locate it
  with `(Get-Command msedge.exe).Source` and pass that path, or open http://localhost:5173
  manually.
- **Ports already bound** → a previous stack is still running: `docker compose --env-file .env down`
  then retry.
