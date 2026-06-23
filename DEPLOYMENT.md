# Public free demo deployment (Vercel + Koyeb + Neon)

Goal: get a publicly accessible URL to share with people, $0/month, no AWS account, while
preserving the enterprise architecture (service boundaries, JWT/RBAC, Flyway, LangGraph
checkpointing, pgvector).

## Topology

```
            ┌──────────────────────────────────┐
   Visitor ─┤  https://careerpilot.vercel.app  │   (Vercel static, free)
            └────────────────┬─────────────────┘
                             │  HTTPS REST + JWT (CORS_ALLOWED_ORIGINS allow-lists this origin)
                             ▼
            ┌──────────────────────────────────────┐
            │ https://backend-<org>.koyeb.app      │   (Koyeb Free instance)
            │   Spring Boot 4 · Java 25              │
            └──┬──────────────────────┬──────────────┘
               │                      │ HTTPS (public URL — Koyeb free tier
               ▼                      │ has no shared private network across
   ┌──────────────────────┐           │ services on different apps)
   │ Neon Postgres         │◄──────────┘
   │ (pgvector, direct     │   ┌─────────────────────────────┐
   │  endpoint)             │   │ https://agent-<org>.koyeb.app│
   └──────────────────────┘   │  FastAPI + LangGraph         │
                                └─────────────────────────────┘
        Upstash Redis (free) · Cloudflare R2 (free, optional)
        Kafka NOT deployed (producer logs warnings, app keeps working)
```

**Trade-offs you accept on the free tier:**
- Koyeb free instances sleep/scale-to-zero after a period of inactivity; cold start adds latency to the first request after idle. Health checks (configured below) keep it from being killed mid-traffic, but won't prevent scale-to-zero on a fully idle service.
- Neon free tier autosuspends after ~5 min idle. First DB query after suspend takes 1–2s.
- A full LangGraph workflow run hits the primary AI provider (DeepSeek by default, falls back through Gemini → Groq → Qwen → OpenRouter) for 30–90s. This happens server-to-server (Koyeb backend → Koyeb agent service), so it isn't constrained by the visitor's browser timeout — but it is constrained by Koyeb's own request timeout on the **inbound** edge if the backend blocks on it. Test this; if it's a problem, move the workflow trigger to fire-and-poll instead of synchronous.
- Free-tier AI models have rate limits (e.g., Groq ~10 req/min, OpenRouter free models ~3 req/min). The AI Gateway automatically fails over to the next provider if one is rate-limited, so configure multiple providers for resilience.
- Resume upload won't work until you finish Step 2 (Cloudflare R2). You can demo register / login / dashboard / Jobs CRUD without it.

---

## Step 0 — Push to GitHub

Koyeb and Vercel both deploy from a git remote.

```bash
cd d:/WORK_SPACE/careerpilot_ai
git add .
git commit -m "Configure for Vercel + Koyeb + Neon deployment"
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
2. Create a Redis database (region close to your Koyeb region, e.g. `was`/US-East).
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

## Step 4 — Deploy the agent service to Koyeb

1. Sign up at https://app.koyeb.com — free tier, no credit card for the free instance type.
2. Install the CLI (optional but easier for repeatable deploys): https://www.koyeb.com/docs/cli/installation
3. Authenticate: `koyeb login`
4. Create the app and the agent service from the repo:

   ```bash
   koyeb app init careerpilot
   koyeb service create agent \
     --app careerpilot \
     --git github.com/<you>/careerpilot_ai \
     --git-branch main \
     --git-builder docker \
     --git-docker-dockerfile agent-service/Dockerfile \
     --git-docker-context agent-service \
     --ports 8088:http \
     --routes /:8088 \
     --instance-type free \
     --region was \
     --env PORT=8088 \
     --env AI_PROVIDER=gemini \
     --env AI_MODEL=gemini-2.5-flash \
     --env GEMINI_API_KEY=<your-gemini-key> \
     --env DATABASE_URL_PY="<your Neon DIRECT libpq URL>" \
     --env CORS_ALLOWED_ORIGINS="https://your-app.vercel.app"
   ```

   Or skip the flags and use the manifest: `koyeb deploy --file koyeb.agent.yaml` (fill in the `SECRET` values via `koyeb secret create` first, or paste them in the dashboard after import — see comments in [koyeb.agent.yaml](koyeb.agent.yaml)).

5. Wait for the service to show **Healthy** in the dashboard, then note its public URL, e.g. `https://agent-<your-org>.koyeb.app`.
6. Smoke-test:
   ```bash
   curl https://agent-<your-org>.koyeb.app/health
   # {"status":"ok","provider":"gemini","model":"gemini-2.5-flash"}
   ```

> **Note:** The agent service uses Gemini directly for LangGraph agents. For backend AI Gateway failover (DeepSeek → Gemini → Groq → Qwen → OpenRouter), see Step 5.

---

## Step 5 — Deploy the backend to Koyeb (AI Gateway with Failover)

The backend includes an **AI Gateway** that implements automatic transparent failover across 5 providers:

```
DeepSeek (NVIDIA) → Gemini (Google) → Groq → Qwen (NVIDIA) → OpenRouter
```

Each provider is optional — the gateway skips unconfigured ones. **Minimum config: just Gemini.** For higher reliability, configure as many as you have API keys for.

```bash
koyeb service create backend \
  --app careerpilot \
  --git github.com/<you>/careerpilot_ai \
  --git-branch main \
  --git-builder docker \
  --git-docker-dockerfile backend/Dockerfile \
  --git-docker-context backend \
  --ports 8080:http \
  --routes /:8080 \
  --instance-type free \
  --region was \
  --env PORT=8080 \
  --env JWT_SECRET="$(openssl rand -hex 48)" \
  --env DATABASE_URL="<your Neon DIRECT jdbc URL>" \
  --env SPRING_DATASOURCE_USERNAME=neondb_owner \
  --env SPRING_DATASOURCE_PASSWORD=<your-neon-password> \
  --env AGENT_SERVICE_URL="https://agent-<your-org>.koyeb.app" \
  --env CORS_ALLOWED_ORIGINS="https://your-app.vercel.app" \
  --env REDIS_URL="<your Upstash rediss:// URL>" \
  --env KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  --env MINIO_ENDPOINT="<your R2 endpoint>" \
  --env MINIO_ACCESS_KEY="<your R2 access key>" \
  --env MINIO_SECRET_KEY="<your R2 secret key>" \
  --env S3_REGION=auto \
  \
  --env PRIMARY_PROVIDER=deepseek \
  --env AI_PROVIDER_ORDER=deepseek,gemini,groq,qwen,openrouter \
  \
  --env GEMINI_API_KEY=<your-gemini-key> \
  --env GEMINI_MODEL=gemini-2.5-flash \
  \
  --env DEEP_SHEEK_NVIDIA_API_KEY=<your-nvidia-key-for-deepseek> \
  --env NVIDIA_DEEPSEEK_MODEL=deepseek-ai/deepseek-v4-flash \
  --env NVIDIA_BASE_URL=https://integrate.api.nvidia.com/v1 \
  \
  --env GROQ_API_KEY=<your-groq-key> \
  --env GROQ_MODEL=llama-3.3-70b-versatile \
  --env GROQ_BASE_URL=https://api.groq.com/openai/v1 \
  \
  --env QWEN3_NVIDIA_API_KEY=<your-nvidia-key-for-qwen> \
  --env NVIDIA_QWEN_MODEL=qwen/qwen3-next-80b-a3b-instruct \
  \
  --env OPENROUTER_API_KEY=<your-openrouter-key> \
  --env OPENROUTER_MODEL=qwen/qwen3-next-80b-a3b-instruct \
  --env OPENROUTER_BASE_URL=https://openrouter.ai/api/v1
```

Or `koyeb deploy --file koyeb.backend.yaml` after filling in secrets — see [koyeb.backend.yaml](koyeb.backend.yaml).

**Provider keys are optional.** If you only have Gemini, just set `GEMINI_API_KEY` and skip the others — the gateway will detect missing keys and skip those providers, falling back to Gemini only.

**Important:** `AGENT_SERVICE_URL` must be the agent service's **public** Koyeb URL from Step 4. Unlike Render's `fromService` cross-reference, Koyeb's free tier doesn't give you a shared private network across separately-created services, so this is a normal public HTTPS call — same as any other service-to-service call.

First build takes 5–10 minutes (Maven downloads dependencies). Once **Healthy**:

```bash
curl https://backend-<your-org>.koyeb.app/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}

# View AI Gateway health & failover stats
curl https://backend-<your-org>.koyeb.app/api/diagnostics/ai
# Shows all 5 providers, their health status, and failover metrics
```

First call wakes a scaled-to-zero instance — expect a delay if it had gone idle.

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
   - `VITE_API_BASE_URL` = your Koyeb backend URL, e.g. `https://backend-<your-org>.koyeb.app`
4. **Deploy.**
5. Vercel gives you a URL like `https://careerpilot-ai.vercel.app`.

---

## Step 7 — Lock down CORS with the real Vercel URL

You set `CORS_ALLOWED_ORIGINS` to a placeholder in Steps 4–5. Now that you have the real Vercel URL:

1. Koyeb dashboard → `agent` service → Environment → update `CORS_ALLOWED_ORIGINS` → redeploy.
2. Koyeb dashboard → `backend` service → Environment → update `CORS_ALLOWED_ORIGINS` → redeploy.

If Vercel gives you preview-deployment URLs too (e.g. `https://careerpilot-ai-git-main-<org>.vercel.app`) and you want previews to work, add them as a comma-separated list — both the Spring `SecurityConfig` and the FastAPI CORS middleware split on commas and match origins exactly, with no wildcards.

---

## Step 8 — End-to-end smoke test

1. Visit your Vercel URL.
2. **Create one** → register with any email + 8+ char password. First POST may be slow if Koyeb is cold.
3. Land on the Dashboard with all scores = 0.
4. **Jobs tab** → "Add job" → paste a job description → Save.
5. **Resumes tab** → upload a resume (only works if you finished Step 3 / R2).
6. **AI Workflow tab** → paste resume ID + job ID → "Start workflow". Wait 30–90s. Workflow pauses at "Human Approval" → click **Approve**.
7. **Dashboard** updates — Career Health, Resume Score, ATS Score, Job Match Score, Interview Readiness, Offer Probability all populated.

---

## Production readiness review

**Deployment risks**
- The backend → agent-service call is now over the public internet (HTTPS, not a private network) — there's no mTLS or shared secret between them. For a real production deployment, add a shared bearer token or IP allow-list on the agent service so it isn't an open unauthenticated endpoint reachable by anyone who finds the URL. Today, `/health` and the LangGraph endpoints have no auth in front of them.
- **AI provider API keys:** Each key grants access to potentially expensive API calls. In production:
  - Never commit `.env` (already in `.gitignore`).
  - Use Koyeb Secrets or your platform's secret manager — don't paste into the dashboard UI.
  - Rotate API keys periodically (especially if a developer leaves).
  - Monitor usage via `/api/diagnostics/ai` endpoint (shows real-time call counts and failover metrics).
  - Consider per-provider quota/budgets if your keys allow it (e.g., NVIDIA, Groq, OpenRouter all support quotas).
- `flyway.baseline-on-migrate: true` will silently baseline against whatever schema state already exists in Neon. Fine for this single shared dev DB; risky if you ever point a second environment at the same flag without checking schema drift first.

**Free-tier limitations**
- Koyeb free instance: 1 instance per service, scales to zero on idle, limited CPU/RAM (sized the JVM via `-XX:MaxRAMPercentage=75` in the backend Dockerfile to avoid OOM-kills).
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
- `scaling.max: 1` in both Koyeb manifests means no horizontal scaling yet. LangGraph's `PostgresSaver` checkpoint state lives in Neon, not in-memory, so horizontal scaling of the agent service is safe to enable later (`scaling.max: N`) without losing in-flight workflow state.
- HikariCP pool sizes were trimmed to 10/2 (max/min-idle) to fit Neon's free-tier connection limits if you scale instances later.

**Persistence considerations**
- All durable state (users, jobs, resumes, workflow runs, LangGraph checkpoints) lives in Neon — Koyeb instances are stateless and disposable, which is the correct posture for free-tier scale-to-zero.
- Resume file bytes live in R2 if configured; without it, uploads will fail but no other feature depends on file storage.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Backend logs: `relation "users" does not exist` | Flyway didn't run because of pooler weirdness | Confirm `DATABASE_URL` uses the Neon **direct** endpoint (no `-pooler`). |
| Backend logs: `password authentication failed` | Stale password in `SPRING_DATASOURCE_PASSWORD` | Re-paste the current Neon password in the Koyeb dashboard, redeploy. |
| Agent service logs: `GEMINI_API_KEY is not configured` | Env var not set on the agent service | Koyeb dashboard → `agent` → Environment → add `GEMINI_API_KEY`, redeploy. |
| Backend logs: `AI Gateway initialized — order=..., configured=[]` | No AI provider keys configured | Set at least one of: `GEMINI_API_KEY`, `DEEP_SHEEK_NVIDIA_API_KEY`, `GROQ_API_KEY`, `QWEN3_NVIDIA_API_KEY`, `OPENROUTER_API_KEY` in Koyeb dashboard → `backend` → Environment. |
| Copilot returns 500: "All AI providers are unavailable" | All configured providers are exhausted (rate-limited or down) | Check `GET /api/diagnostics/ai` to see provider health. Add more API keys to increase fallover options, or wait for rate limits to reset. |
| Browser console: CORS error | `CORS_ALLOWED_ORIGINS` doesn't match the exact Vercel origin (scheme+host) | Update on both `backend` and `agent` services — must match exactly, no trailing slash. |
| Frontend can register but workflow runs hang | Backend can't reach the agent service | Confirm `AGENT_SERVICE_URL` on the backend is the agent's public `https://agent-<org>.koyeb.app` URL, not a private hostname. |
| First page load is slow | Koyeb scale-to-zero — instance is waking | Normal on free tier. A cron pinger (e.g. cron-job.org hitting `/actuator/health` every few minutes) reduces cold starts at the cost of more instance-hours. |
| Resume upload returns 500 | R2 not configured or wrong endpoint | Either finish Step 3 or accept uploads won't work — nothing else depends on it. |
| Kafka producer error spam in backend logs | Expected — Kafka isn't deployed | Ignorable; workflow events are fire-and-forget. |

---

## Keeping costs at $0

The entire CareerPilot stack has free-tier options across all components:

- **Koyeb free:** 1 free web service instance type per app component, scale-to-zero.
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
