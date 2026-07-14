# Deploying the backend to Google Cloud Run

This is an alternative compute target for the **backend only** (`backend/`). It reuses every
external dependency already wired up for Render in [DEPLOYMENT.md](DEPLOYMENT.md) — Neon
Postgres, Upstash Redis, Cloudflare R2, the agent service, and the AI provider keys — because
those are all configured via env vars with no code path that assumes Render specifically. Only
the compute layer changes.

**Why you might do this instead of / alongside Render:** Cloud Run gives full CPU during
container startup by default (a specific feature — "startup CPU boost" — aimed at exactly this
JVM cold-start problem), and memory limits go well past Render free tier's fixed 512MB, which
directly addresses the two issues diagnosed on Render: the OOM crash and the 433-second boot
time (see `git log` for `Fix Render 512MB OOM` and the SpringDoc-disable commit).

---

## Prerequisites

```bash
gcloud --version                         # gcloud CLI installed
gcloud auth login
gcloud config set project <PROJECT_ID>
gcloud services enable run.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com cloudbuild.googleapis.com
```

---

## Step 1 — One-time setup: Artifact Registry + secrets

```bash
# Repo to hold the built image
gcloud artifacts repositories create careerpilot \
  --repository-format=docker \
  --location=<REGION> \
  --description="CareerPilot AI images"

# Secrets — same credential material as Render's render.yaml sync:false keys.
# Never pass these as --set-env-vars; Secret Manager keeps them out of `gcloud run services describe`
# output and Cloud Console env-var listings.
echo -n "<jwt-secret-from-openssl-rand-hex-48>"     | gcloud secrets create JWT_SECRET --data-file=-
echo -n "<neon-direct-jdbc-url>"                     | gcloud secrets create DATABASE_URL --data-file=-
echo -n "<neon-username>"                            | gcloud secrets create DB_USERNAME --data-file=-
echo -n "<neon-password>"                            | gcloud secrets create DB_PASSWORD --data-file=-
echo -n "<gemini-api-key>"                           | gcloud secrets create GEMINI_API_KEY --data-file=-
echo -n "<nvidia-deepseek-key>"                      | gcloud secrets create NVIDIA_DEEPSEEK_KEY --data-file=-
echo -n "<nvidia-qwen-key>"                          | gcloud secrets create NVIDIA_QWEN_KEY --data-file=-
echo -n "<groq-api-key>"                             | gcloud secrets create GROQ_API_KEY --data-file=-
echo -n "<openrouter-api-key>"                       | gcloud secrets create OPENROUTER_API_KEY --data-file=-
echo -n "<upstash-rediss-url>"                       | gcloud secrets create REDIS_URL --data-file=-
echo -n "<r2-endpoint>"                              | gcloud secrets create MINIO_ENDPOINT --data-file=-
echo -n "<r2-access-key>"                            | gcloud secrets create MINIO_ACCESS_KEY --data-file=-
echo -n "<r2-secret-key>"                            | gcloud secrets create MINIO_SECRET_KEY --data-file=-

# Only create secrets for providers you actually have keys for — same
# "unconfigured provider is auto-skipped" rule as Render.
```

---

## Step 2 — Build and push the image

The existing [backend/Dockerfile](backend/Dockerfile) works as-is — no changes needed for
Cloud Run. Build with Cloud Build so you don't need Docker installed locally, or substitute
`docker build`/`docker push` if you prefer building on your own machine (make sure to target
`linux/amd64` if building on Apple Silicon: `docker buildx build --platform linux/amd64 ...`).

```bash
cd backend
gcloud builds submit \
  --tag <REGION>-docker.pkg.dev/<PROJECT_ID>/careerpilot/backend:latest \
  .
```

---

## Step 3 — Deploy

```bash
cat > env.yaml <<'EOF'
SPRING_PROFILES_ACTIVE: prod
AI_PROVIDER: gemini
AI_MODEL: gemini-2.5-flash
PRIMARY_PROVIDER: deepseek
AI_PROVIDER_ORDER: "deepseek,gemini,groq,qwen,openrouter"
GEMINI_MODEL: gemini-2.5-flash
NVIDIA_BASE_URL: https://integrate.api.nvidia.com/v1
NVIDIA_DEEPSEEK_MODEL: deepseek-ai/deepseek-v4-flash
NVIDIA_QWEN_MODEL: qwen/qwen3-next-80b-a3b-instruct
GROQ_MODEL: llama-3.3-70b-versatile
GROQ_BASE_URL: https://api.groq.com/openai/v1
OPENROUTER_MODEL: qwen/qwen3-next-80b-a3b-instruct
OPENROUTER_BASE_URL: https://openrouter.ai/api/v1
S3_REGION: auto
AGENT_SERVICE_URL: https://careerpilot-agent.onrender.com
CORS_ALLOWED_ORIGINS: https://your-app.vercel.app
KAFKA_BOOTSTRAP_SERVERS: localhost:9092
EOF
# A plain --set-env-vars string breaks here because AI_PROVIDER_ORDER's value itself
# contains commas, which is the same delimiter gcloud uses between KEY=VALUE pairs.
# The env.yaml file sidesteps that entirely — use --env-vars-file, not --set-env-vars.

gcloud run deploy careerpilot-backend \
  --image=<REGION>-docker.pkg.dev/<PROJECT_ID>/careerpilot/backend:latest \
  --region=<REGION> \
  --platform=managed \
  --allow-unauthenticated \
  --port=8080 \
  --memory=1Gi \
  --cpu=2 \
  --cpu-boost \
  --min-instances=1 \
  --max-instances=3 \
  --timeout=300 \
  --concurrency=80 \
  --env-vars-file=env.yaml \
  --set-secrets="JWT_SECRET=JWT_SECRET:latest,DATABASE_URL=DATABASE_URL:latest,SPRING_DATASOURCE_USERNAME=DB_USERNAME:latest,SPRING_DATASOURCE_PASSWORD=DB_PASSWORD:latest,GEMINI_API_KEY=GEMINI_API_KEY:latest,DEEP_SHEEK_NVIDIA_API_KEY=NVIDIA_DEEPSEEK_KEY:latest,QWEN3_NVIDIA_API_KEY=NVIDIA_QWEN_KEY:latest,GROQ_API_KEY=GROQ_API_KEY:latest,OPENROUTER_API_KEY=OPENROUTER_API_KEY:latest,REDIS_URL=REDIS_URL:latest,MINIO_ENDPOINT=MINIO_ENDPOINT:latest,MINIO_ACCESS_KEY=MINIO_ACCESS_KEY:latest,MINIO_SECRET_KEY=MINIO_SECRET_KEY:latest"
```

**Why each non-default flag:**
| Flag | Why |
|---|---|
| `--port=8080` | Matches `EXPOSE 8080` in the Dockerfile and `server.port: ${PORT:8080}` in `application.yml`. Cloud Run injects `PORT` for you — never pass `PORT` via `--set-env-vars`/`env.yaml`, it's a reserved name and `gcloud` will reject it. |
| `--memory=1Gi` | Well past Render free tier's hard 512MB ceiling that caused the OOM crash-loop. Bump higher if you still see memory pressure in Cloud Monitoring. |
| `--cpu=2` `--cpu-boost` | Directly targets the 433-second boot time — Render free tier throttles to 0.1 vCPU; this gives full CPU (and a startup boost on top) for the Hibernate-validate + Flyway-checksum work during context refresh. |
| `--min-instances=1` | Keeps one instance always warm — eliminates the cold-start-vs-axios-timeout class of bug entirely, at a small constant cost. Omit (defaults to 0) if you want Cloud Run's free-tier scale-to-zero instead and are OK with cold starts. |
| `--timeout=300` | Request timeout headroom in case a workflow call to the agent service runs long, same caveat DEPLOYMENT.md already calls out for Render. |
| `--allow-unauthenticated` | Cloud Run defaults to requiring IAM auth on every request; the frontend calls this over plain HTTPS with its own JWT auth, so the service itself needs to be publicly invokable. |

DeepSeek and Qwen use two **separate** env vars — `DEEP_SHEEK_NVIDIA_API_KEY` (note the typo is
in the actual property name, `application.yml:118`) and `QWEN3_NVIDIA_API_KEY`
(`application.yml:132`) — not a single shared `NVIDIA_API_KEY` as CLAUDE.md's config table
implies; that note is stale. Both are wired above so both providers can be live at once.

---

## Step 4 — Verify

```bash
SERVICE_URL=$(gcloud run services describe careerpilot-backend --region=<REGION> --format='value(status.url)')
curl -s -o /dev/null -w "HTTP_STATUS:%{http_code} TIME:%{time_total}s\n" "$SERVICE_URL/actuator/health"
curl -s "$SERVICE_URL/api/diagnostics/ai" | head -c 500
```

Then update the frontend's `VITE_API_BASE_URL` (Vercel env var) and both services'
`CORS_ALLOWED_ORIGINS` to point at `$SERVICE_URL`, same as Step 7 in [DEPLOYMENT.md](DEPLOYMENT.md).

---

## Redeploying after a code change

```bash
cd backend
gcloud builds submit --tag <REGION>-docker.pkg.dev/<PROJECT_ID>/careerpilot/backend:latest .
gcloud run deploy careerpilot-backend --image=<REGION>-docker.pkg.dev/<PROJECT_ID>/careerpilot/backend:latest --region=<REGION>
```
Cloud Run keeps the previous revision available for instant rollback:
```bash
gcloud run revisions list --service=careerpilot-backend --region=<REGION>
gcloud run services update-traffic careerpilot-backend --region=<REGION> --to-revisions=<PREVIOUS_REVISION>=100
```

---

## What stays the same as Render

- Neon, Upstash Redis, Cloudflare R2, and the agent service can all stay exactly where they are
  — every reference to them is an env var, not a hardcoded host.
- Flyway migrations still run automatically on boot against Neon.
- Kafka still isn't deployed; `KAFKA_BOOTSTRAP_SERVERS=localhost:9092` still fails open (no
  `@KafkaListener` consumers exist anywhere in this codebase).

## What's genuinely different

- Cloud Run's request timeout, concurrency model, and cold-start behavior differ from Render's —
  re-run the Step 8 smoke test from [DEPLOYMENT.md](DEPLOYMENT.md) end-to-end against the new
  URL before treating this as a like-for-like swap.
- Logs live in Cloud Logging (`gcloud run services logs read careerpilot-backend --region=<REGION>`),
  not Render's dashboard log tail.
