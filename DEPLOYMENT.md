# Public free demo deployment (Vercel + Render + Neon)

Goal: get a publicly accessible URL to share with people, $0/month, no AWS account, while
preserving the enterprise architecture (service boundaries, JWT/RBAC, Flyway, LangGraph
checkpointing, pgvector).

> **Why Render instead of Koyeb:** this project originally targeted Koyeb's free tier, but
> Koyeb's free instance type was discontinued/became paid, so the deployment moved to
> Render's free web service tier instead. The architecture is identical — only the
> platform-specific deploy mechanics (CLI, manifest format, networking) changed.

## Topology

```
            ┌──────────────────────────────────┐
   Visitor ─┤  https://careerpilot.vercel.app  │   (Vercel static, free)
            └────────────────┬─────────────────┘
                             │  HTTPS REST + JWT (CORS_ALLOWED_ORIGINS allow-lists this origin)
                             ▼
            ┌─────────────────────────────────────────────┐
            │ https://careerpilot-backend.onrender.com    │   (Render Free web service)
            │   Spring Boot 4 · Java 25                     │
            └──┬──────────────────────┬──────────────────────┘
               │                      │ HTTPS by default (public URL). If both
               ▼                      │ services share a region, you can switch
   ┌──────────────────────┐           │ to Render's private network instead.
   │ Neon Postgres         │◄──────────┘
   │ (pgvector, direct     │   ┌─────────────────────────────────────────┐
   │  endpoint)             │   │ https://careerpilot-agent.onrender.com │
   └──────────────────────┘   │  FastAPI + LangGraph                    │
                                └─────────────────────────────────────────┘
        Upstash Redis (free) · Cloudflare R2 (free, optional)
        Kafka NOT deployed (producer logs warnings, app keeps working)
```

**Trade-offs you accept on the free tier:**
- Render free instances **spin down after ~15 min of inactivity**; the first request after idle can take 50+ seconds to wake the container (you'll see this banner in the Render dashboard). Health checks (configured below) keep it alive while traffic flows, but won't prevent spin-down on a fully idle service.
- Neon free tier autosuspends after ~5 min idle. First DB query after suspend takes 1–2s.
- A full LangGraph workflow run hits the primary AI provider (DeepSeek by default, falls back through Gemini → Groq → Qwen → OpenRouter) for 30–90s. This happens server-to-server (backend → agent service), so it isn't constrained by the visitor's browser timeout — but it is constrained by Render's own request timeout on the **inbound** edge if the backend blocks on it. Test this; if it's a problem, move the workflow trigger to fire-and-poll instead of synchronous.
- Free-tier AI models have rate limits (e.g., Groq ~10 req/min, OpenRouter free models ~3 req/min). The AI Gateway automatically fails over to the next provider if one is rate-limited, so configure multiple providers for resilience.
- Resume upload won't work until you finish Step 2 (Cloudflare R2). You can demo register / login / dashboard / Jobs CRUD without it.

---

## Step 0 — Push to GitHub

Render and Vercel both deploy from a git remote.

```bash
cd d:/WORK_SPACE/careerpilot_ai
git add .
git commit -m "Configure for Vercel + Render + Neon deployment"
git push -u origin main
```

> **.env must never be committed.** Confirm it's in `.gitignore` before pushing — it has your real Neon password and JWT secret.

---

## Step 1 — Neon Postgres setup

Already done in this project, but for reference:

1. https://console.neon.tech → your project → **Connection Details**.
2. Copy the **direct** connection string (hostname has **no** `-pooler` suffix). Flyway DDL and LangGraph's `PostgresSaver` both use prepared statements that break under Neon's pgBouncer transaction-mode pooler.
3. Build both URL forms:
   - JDBC (backend): `jdbc:postgresql://<host>/<db>?sslmode=require&channelBinding=require`
   - libpq (agent service): `postgresql://<user>:<pass>@<host>/<db>?sslmode=require&channel_binding=require`
4. Confirm `CREATE EXTENSION IF NOT EXISTS vector;` has been run (pgvector) and `V1__init.sql` has been applied.

---

## Step 2 — Upstash Redis (2 minutes, free)

1. Sign up at https://upstash.com — free tier, no credit card.
2. Create a Redis database (region close to your Render region, e.g. `oregon`/US-West).
3. Copy the **Redis URL** Upstash gives you directly — it's already in `rediss://default:<password>@<host>:<port>` form. That's your `REDIS_URL`.

---

## Step 3 — Cloudflare R2 (5 minutes, optional, free 10GB)

Skip this if you don't need resume uploads in the demo.

1. Sign up at https://dash.cloudflare.com — free tier.
2. Storage → R2 → **Create bucket** → name it `careerpilot`.
3. R2 → Manage R2 API Tokens → **Create API token** → grant **Object Read & Write** on the bucket.
4. Copy:
   - Access Key ID → `MINIO_ACCESS_KEY`
   - Secret Access Key → `MINIO_SECRET_KEY`
   - Endpoint (the "S3 Compatibility" URL, `https://<account-id>.r2.cloudflarestorage.com`) → `MINIO_ENDPOINT`
   - Bucket name → `MINIO_BUCKET`

---

## Step 4 — Deploy the agent service to Render

1. Sign up at https://render.com — free tier, no credit card required.
2. Easiest path: use the [render.yaml](render.yaml) Blueprint (covers both the agent service and the backend in one file) —
   **Dashboard → New → Blueprint → connect your GitHub repo → Render auto-detects `render.yaml`** and proposes both services. Review and click **Apply**.
3. After the Blueprint creates `careerpilot-agent`, go to its **Environment** tab and fill in the secrets it left blank (marked `sync: false` in the manifest):
   - `GEMINI_API_KEY`
   - `DATABASE_URL_PY` (Neon **direct**, no `-pooler`, libpq form)
   - `CORS_ALLOWED_ORIGINS` (placeholder for now — you'll fix this in Step 7 once Vercel gives you a real URL)
4. Trigger **Manual Deploy** if it doesn't redeploy automatically after you save env vars.

   Prefer manual setup over the Blueprint? Dashboard → **New → Web Service** → connect repo → set **Runtime: Docker**, **Dockerfile Path: `agent-service/Dockerfile`**, **Docker Build Context: `agent-service`**, **Plan: Free**, **Health Check Path: `/health`** — then add the same env vars by hand.

5. Wait for the service to show **Live** in the dashboard, then note its public URL, e.g. `https://careerpilot-agent.onrender.com`.
6. Smoke-test:
   ```bash
   curl https://careerpilot-agent.onrender.com/health
   # {"status":"ok","provider":"gemini","model":"gemini-2.5-flash"}
   ```

> **Note:** The agent service uses Gemini directly for LangGraph agents. For backend AI Gateway failover (DeepSeek → Gemini → Groq → Qwen → OpenRouter), see Step 5.

---

## Step 5 — Deploy the backend to Render (AI Gateway with Failover)

The backend includes an **AI Gateway** that implements automatic transparent failover across 5 providers:

```
DeepSeek (NVIDIA) → Gemini (Google) → Groq → Qwen (NVIDIA) → OpenRouter
```

Each provider is optional — the gateway skips unconfigured ones. **Minimum config: just Gemini.** For higher reliability, configure as many as you have API keys for.

### Spring profiles

The backend uses Spring Boot profiles to switch configuration:
- **`application.yml`** (default) — Local development with docker-compose
- **`application-prod.yml`** — Production on Render (quiet logs, optimized pools, Kafka disabled)
- **`application-local.yml`** — Explicit local dev profile (DEBUG logging, liberal pools)

For Render, [render.yaml](render.yaml) already sets `SPRING_PROFILES_ACTIVE=prod` for you. If you create the service manually instead of via the Blueprint, set that env var yourself.

If you applied the [render.yaml](render.yaml) Blueprint in Step 4, `careerpilot-backend` was already created alongside the agent service — skip to filling in its secrets below. Otherwise, create it manually:

Dashboard → **New → Web Service** → connect repo → **Runtime: Docker**, **Dockerfile Path: `backend/Dockerfile`**, **Docker Build Context: `backend`**, **Plan: Free**, **Health Check Path: `/actuator/health`**.

Either way, go to `careerpilot-backend` → **Environment** and fill in every `sync: false` key from [render.yaml](render.yaml):

| Key | Value |
|---|---|
| `JWT_SECRET` | `openssl rand -hex 48` |
| `DATABASE_URL` | Neon **direct** (no `-pooler`) JDBC URL |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Neon credentials |
| `AGENT_SERVICE_URL` | `https://careerpilot-agent.onrender.com` (from Step 4) |
| `GEMINI_API_KEY` | your Gemini key |
| `DEEP_SHEEK_NVIDIA_API_KEY` / `QWEN3_NVIDIA_API_KEY` | NVIDIA NIM keys (optional) |
| `GROQ_API_KEY` | Groq key (optional) |
| `OPENROUTER_API_KEY` | OpenRouter key (optional) |
| `REDIS_URL` | Upstash `rediss://` URL |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | Cloudflare R2 credentials |
| `CORS_ALLOWED_ORIGINS` | placeholder for now, fixed in Step 7 |

**Provider keys are optional.** If you only have Gemini, just set `GEMINI_API_KEY` and leave the others blank — the gateway detects missing keys and skips those providers, falling back to Gemini only.

**Internal networking (optional optimization):** Render's Blueprint env vars can't string-concatenate a scheme onto `fromService`, so `AGENT_SERVICE_URL` defaults to the agent's **public** `https://careerpilot-agent.onrender.com` URL — a normal HTTPS hop, same as Koyeb required. If both services end up in the same Render region, you can manually change `AGENT_SERVICE_URL` to the internal address `http://careerpilot-agent:8088` in the dashboard to skip the public hop entirely (lower latency, no TLS overhead) — something the old Koyeb free tier couldn't offer since it had no shared private network across services.

First build takes 5–10 minutes (Maven downloads dependencies). Once **Live**:

```bash
curl https://careerpilot-backend.onrender.com/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}

# View AI Gateway health & failover stats
curl https://careerpilot-backend.onrender.com/api/diagnostics/ai
# Shows all 5 providers, their health status, and failover metrics
```

First call wakes a spun-down instance — expect a 50+ second delay if it had gone idle (Render free tier).

---

## Step 5.1 — Understanding AI Gateway Failover

The backend includes an **AI Gateway** that provides transparent, automatic failover across 5 LLM providers. Here's how it works:

**Provider Chain (in order):**
```
DeepSeek (NVIDIA) → Gemini (Google) → Groq → Qwen (NVIDIA) → OpenRouter
```

**How failover works:**
1. Request comes in (e.g., copilot message, workflow task).
2. Gateway tries the primary provider (DeepSeek).
3. If DeepSeek succeeds → response sent, done.
4. If DeepSeek fails (timeout, error, rate-limit 429) → try Gemini.
5. If Gemini fails → try Groq, then Qwen, then OpenRouter.
6. If all providers fail → return error to client (with detailed diagnostics).

**Key behaviors:**
- **Rate-limit detection:** HTTP 429 errors trigger immediate failover without retrying (preserves quota).
- **Health tracking:** Each provider has 5-minute health cache. Unhealthy providers are skipped.
- **Resilience4j:** Per-provider retry + circuit breaker; a failing provider won't cascade.
- **No configuration required:** If an API key is missing, the gateway auto-skips that provider.

**Minimum viable setup:** Just `GEMINI_API_KEY` — the gateway works with one provider.

**Recommended setup:** Gemini + Groq + OpenRouter = 3 different vendors, better uptime.

**Monitoring:** Check `/api/diagnostics/ai` endpoint to see:
- Which providers are configured and healthy.
- Real-time failover statistics (calls, successes, failures, rate-limits per provider).

---

## Step 6 — Deploy the frontend to Vercel

1. Sign up at https://vercel.com — free Hobby tier.
2. **Add New → Project** → import your GitHub repo → set:
   - **Root Directory:** `frontend`
   - **Framework Preset:** Vite (auto-detected via [vercel.json](frontend/vercel.json))
3. **Environment Variables**:
   - `VITE_API_BASE_URL` = your Render backend URL, e.g. `https://careerpilot-backend.onrender.com`
4. **Deploy.**
5. Vercel gives you a URL like `https://careerpilot-ai.vercel.app`.

---

## Step 7 — Lock down CORS with the real Vercel URL

You set `CORS_ALLOWED_ORIGINS` to a placeholder in Steps 4–5. Now that you have the real Vercel URL:

1. Render dashboard → `careerpilot-agent` → Environment → update `CORS_ALLOWED_ORIGINS` → save (auto-redeploys).
2. Render dashboard → `careerpilot-backend` → Environment → update `CORS_ALLOWED_ORIGINS` → save (auto-redeploys).

If Vercel gives you preview-deployment URLs too (e.g. `https://careerpilot-ai-git-main-<org>.vercel.app`) and you want previews to work, add them as a comma-separated list — both the Spring `SecurityConfig` and the FastAPI CORS middleware split on commas and match origins exactly, with no wildcards.

---

## Step 8 — End-to-end smoke test

1. Visit your Vercel URL.
2. **Create one** → register with any email + 8+ char password. First POST may be slow if Render is cold.
3. Land on the Dashboard with all scores = 0.
4. **Jobs tab** → "Add job" → paste a job description → Save.
5. **Resumes tab** → upload a resume (only works if you finished Step 3 / R2).
6. **AI Workflow tab** → paste resume ID + job ID → "Start workflow". Wait 30–90s. Workflow pauses at "Human Approval" → click **Approve**.
7. **Dashboard** updates — Career Health, Resume Score, ATS Score, Job Match Score, Interview Readiness, Offer Probability all populated.

---

## Production readiness review

**Deployment risks**
- The backend → agent-service call is now over the public internet (HTTPS, not a private network) — there's no mTLS or shared secret between them. For a real production deployment, add a shared bearer token or IP allow-list on the agent service so it isn't an open unauthenticated endpoint reachable by anyone who finds the URL. Today, `/health` and the LangGraph endpoints have no auth in front of them. (If you switch `AGENT_SERVICE_URL` to Render's internal address per the note in Step 5, this risk goes away — internal traffic never leaves Render's network.)
- **AI provider API keys:** Each key grants access to potentially expensive API calls. In production:
  - Never commit `.env` (already in `.gitignore`).
  - Use Render's Environment tab (`sync: false` keys in [render.yaml](render.yaml)) or a secret manager — don't paste into commits or chat.
  - Rotate API keys periodically (especially if a developer leaves).
  - Monitor usage via `/api/diagnostics/ai` endpoint (shows real-time call counts and failover metrics).
  - Consider per-provider quota/budgets if your keys allow it (e.g., NVIDIA, Groq, OpenRouter all support quotas).
- `flyway.baseline-on-migrate: true` will silently baseline against whatever schema state already exists in Neon. Fine for this single shared dev DB; risky if you ever point a second environment at the same flag without checking schema drift first.

**Free-tier limitations**
- Render free instance: 1 instance per service, **spins down after ~15 min idle** (cold start 50+ seconds), limited CPU/RAM (sized the JVM via `-XX:MaxRAMPercentage=75` in the backend Dockerfile to avoid OOM-kills).
- Neon free tier: 0.5 GB storage, autosuspend — fine for a demo, not for real traffic.
- Upstash free: 10,000 commands/day — the backend barely uses Redis today, comfortably under.
- Cloudflare R2 free: 10 GB storage, 1M Class-A ops/month.
- **AI providers (free tier usage):**
  - **Gemini** (`gemini-2.5-flash`): free tier available, rate-limited but reasonable for demos.
  - **DeepSeek** (NVIDIA NIM): requires NVIDIA API key (free tier available via account setup).
  - **Groq** (Llama 3.3 70B): free tier, rate-limited (~10 req/min).
  - **Qwen** (NVIDIA NIM): requires NVIDIA API key (free tier available via account setup).
  - **OpenRouter**: free-tier models available (e.g., `qwen/qwen3-next-80b-a3b-instruct:free`) but with aggressive rate limits (~3–5 req/min); use paid models in production or restore other providers for failover.

**Networking considerations**
- CORS is now origin-allow-listed (no wildcards) on both the Spring Boot and FastAPI sides — update `CORS_ALLOWED_ORIGINS` on both services whenever the frontend domain changes.
- Kafka is not deployed; `KAFKA_BOOTSTRAP_SERVERS=localhost:9092` makes the producer fail open (logs warnings, doesn't block requests). If you wire `@KafkaListener` consumers later, you'll need a real broker (Upstash Kafka or Confluent Cloud both have free tiers).

**Scaling considerations**
- Both services in [render.yaml](render.yaml) run a single free instance (no autoscaling on the free plan). LangGraph's `PostgresSaver` checkpoint state lives in Neon, not in-memory, so horizontal scaling of the agent service is safe to enable later (a paid plan with multiple instances) without losing in-flight workflow state.
- HikariCP pool sizes were trimmed to 10/2 (max/min-idle) to fit Neon's free-tier connection limits if you scale instances later.

**Persistence considerations**
- All durable state (users, jobs, resumes, workflow runs, LangGraph checkpoints) lives in Neon — Render instances are stateless and disposable, which is the correct posture for free-tier spin-down.
- Resume file bytes live in R2 if configured; without it, uploads will fail but no other feature depends on file storage.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Backend logs: `relation "users" does not exist` | Flyway didn't run because of pooler weirdness | Confirm `DATABASE_URL` uses the Neon **direct** endpoint (no `-pooler`). |
| Backend logs: `password authentication failed` | Stale password in `SPRING_DATASOURCE_PASSWORD` | Re-paste the current Neon password in the Render dashboard, redeploy. |
| Agent service logs: `GEMINI_API_KEY is not configured` | Env var not set on the agent service | Render dashboard → `careerpilot-agent` → Environment → add `GEMINI_API_KEY`, save. |
| Backend logs: `AI Gateway initialized — order=..., configured=[]` | No AI provider keys configured | Set at least one of: `GEMINI_API_KEY`, `DEEP_SHEEK_NVIDIA_API_KEY`, `GROQ_API_KEY`, `QWEN3_NVIDIA_API_KEY`, `OPENROUTER_API_KEY` in Render dashboard → `careerpilot-backend` → Environment. |
| Copilot returns 500: "All AI providers are unavailable" | All configured providers are exhausted (rate-limited or down) | Check `GET /api/diagnostics/ai` to see provider health. Add more API keys to increase fallover options, or wait for rate limits to reset. |
| Browser console: CORS error | `CORS_ALLOWED_ORIGINS` doesn't match the exact Vercel origin (scheme+host) | Update on both `careerpilot-backend` and `careerpilot-agent` services — must match exactly, no trailing slash. |
| Frontend can register but workflow runs hang | Backend can't reach the agent service | Confirm `AGENT_SERVICE_URL` on the backend is the agent's public `https://careerpilot-agent.onrender.com` URL (or the correct internal address if you switched to private networking). |
| First page load is slow | Render free tier spin-down — instance is waking | Normal on free tier; can take 50+ seconds. A cron pinger (e.g. cron-job.org hitting `/actuator/health` every few minutes) reduces cold starts at the cost of more usage. |
| Resume upload returns 500 | R2 not configured or wrong endpoint | Either finish Step 3 or accept uploads won't work — nothing else depends on it. |
| Kafka producer error spam in backend logs | Expected — Kafka isn't deployed | Ignorable; workflow events are fire-and-forget. |

---

## Keeping costs at $0

The entire CareerPilot stack has free-tier options across all components:

- **Render free:** 1 free web service instance per app component, spins down after ~15 min idle.
- **Neon free:** 0.5 GB storage, autosuspend.
- **Upstash free:** 10,000 Redis commands/day.
- **Cloudflare R2 free:** 10 GB storage, 1M Class-A ops/month, free egress.
- **Vercel free Hobby:** 100 GB bandwidth/month.
- **AI providers — **all free**:**
  - Gemini (`gemini-2.5-flash`): free tier, rate-limited but sufficient for demos.
  - Groq (Llama 3.3 70B): free tier, ~10 req/min.
  - OpenRouter: free-tier models available (e.g., `qwen/qwen3-next-80b-a3b-instruct:free`), aggressive limits.
  - DeepSeek & Qwen: Both available via NVIDIA NIM free account (sign up for free credits).

**Recommended minimum for zero-cost demo:** Gemini only (requires just one API key).

**Recommended for better reliability:** Gemini + Groq + OpenRouter (3 API keys, automatic failover).

You only start paying when the demo gets real, sustained traffic — at which point you scale the specific component that's bottlenecked, not the whole stack.
