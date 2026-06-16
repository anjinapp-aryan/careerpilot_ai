# Public free demo deployment

Goal: get a publicly accessible URL to share with people, $0/month, no AWS account.

## Topology

```
            ┌──────────────────────────────────┐
   Visitor ─┤  https://careerpilot.vercel.app  │   (Vercel static, free)
            └────────────────┬─────────────────┘
                             │  HTTPS REST + JWT
                             ▼
            ┌──────────────────────────────────────┐
            │ https://careerpilot-backend.onrender │   (Render Free, sleeps after 15m idle)
            │   .com   Spring Boot · Java 21       │
            └──┬──────────────────────┬────────────┘
               │                      │
               ▼                      ▼
   ┌──────────────────────┐   ┌─────────────────────────────┐
   │ Neon Postgres        │   │ careerpilot-agent.onrender  │   (Render Free)
   │ (already set up)     │◄──┤  .com  FastAPI + LangGraph  │
   └──────────────────────┘   └─────────────────────────────┘
        Upstash Redis (free) · Cloudflare R2 (free, optional)
        Kafka NOT deployed (producer logs warnings, app keeps working)
```

**Trade-offs you accept on the free tier:**
- Render free services sleep after 15 minutes of inactivity. First request after sleep takes ~30–60s to wake. After that, snappy.
- Neon free tier autosuspends after ~5 min idle. First DB query after suspend takes 1–2s.
- A full workflow run hits Gemini for 30–90s, which is fine — Render free has 60-second HTTP timeouts on inbound requests, but the LangGraph workflow runs server-to-server so the constraint doesn't apply.
- Resume upload won't work until you finish step 4 (Cloudflare R2). You can demo register / login / dashboard / Jobs CRUD without it.

---

## Step 0 — Push to GitHub

Render and Vercel both deploy from a git remote. If you haven't yet:

```bash
cd d:/WORK_SPACE/careerpilot_ai
git init                                     # if not already
git add .
git commit -m "Initial CareerPilot AI commit"
gh repo create careerpilot-ai --public --source=. --remote=origin --push
```

(Or create the repo on github.com manually and `git push -u origin main`.)

> If you'd rather keep the repo private, both Render and Vercel can connect to private repos — they ask for permission during onboarding. Just demote `--public` to `--private` above.

---

## Step 1 — Upstash Redis (2 minutes, free)

1. Sign up at https://upstash.com — free tier, no credit card.
2. Create a Redis database. Region: pick the same region you'll use for Render (Oregon / US-West is fine).
3. From the database details page copy:
   - **Endpoint** → this is your `SPRING_DATA_REDIS_HOST` (e.g. `usw2-bright-bird-12345.upstash.io`)
   - **Port** → typically `6379`
   - **Password** → this is your `SPRING_DATA_REDIS_PASSWORD`
   - **TLS** must be enabled (it is by default on Upstash).

Keep these handy — you'll paste them into Render's dashboard in step 3.

---

## Step 2 — Cloudflare R2 (5 minutes, optional, free 10GB)

Skip this if you don't need resume uploads in the demo. The rest of the app works without it.

1. Sign up at https://dash.cloudflare.com — free tier.
2. Storage → R2 → "Create bucket" → name it `careerpilot`. Choose the closest jurisdiction.
3. R2 → Manage R2 API Tokens → "Create API token" → grant **Object Read & Write** on the bucket.
4. Copy:
   - **Access Key ID** → `S3_ACCESS_KEY`
   - **Secret Access Key** → `S3_SECRET_KEY`
   - **Endpoint** (the "S3 Compatibility" URL, format `https://<account-id>.r2.cloudflarestorage.com`) → `S3_ENDPOINT`
   - **Bucket** name → `S3_BUCKET`

Keep these for step 3.

---

## Step 3 — Deploy backend + agent to Render

1. Sign up at https://render.com — free tier, no credit card for free services.
2. **New → Blueprint** → Connect your GitHub account → pick the `careerpilot-ai` repo → Render reads [render.yaml](render.yaml) automatically.
3. Render creates two services: `careerpilot-backend` and `careerpilot-agent`. It will prompt for the env vars marked `sync: false`. Paste in:

   For **careerpilot-agent**:
   - `GEMINI_API_KEY` = your key from https://aistudio.google.com/apikey
   - `DATABASE_URL` = your Neon **direct** libpq URL — exactly the value from `DATABASE_URL_PY` in your local `.env` (form: `postgresql://neondb_owner:...@ep-falling-haze-aq84svji.c-8.us-east-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require`)

   For **careerpilot-backend**:
   - `GEMINI_API_KEY` = same key
   - `SPRING_DATASOURCE_URL` = your Neon **direct** JDBC URL (form: `jdbc:postgresql://ep-falling-haze-aq84svji.c-8.us-east-1.aws.neon.tech/neondb?sslmode=require&channelBinding=require`)
   - `SPRING_DATASOURCE_USERNAME` = `neondb_owner`
   - `SPRING_DATASOURCE_PASSWORD` = your Neon password
   - `SPRING_DATA_REDIS_HOST` = Upstash endpoint
   - `SPRING_DATA_REDIS_PASSWORD` = Upstash password
   - `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` = your R2 values (or leave blank if you skipped step 2 — resume upload will return errors but rest works)

4. Hit **Apply**. First build takes 5–10 minutes (Maven downloads everything; Python pip installs).
5. When both services show **Live**, copy the URLs from the dashboard — they look like:
   - `https://careerpilot-agent.onrender.com`
   - `https://careerpilot-backend.onrender.com`
6. Smoke-test from your laptop:
   ```bash
   curl https://careerpilot-agent.onrender.com/health
   # {"status":"ok","provider":"gemini","model":"gemini-2.5-flash"}
   curl https://careerpilot-backend.onrender.com/actuator/health
   # {"status":"UP","groups":["liveness","readiness"]}
   ```

   First call wakes the service from sleep — expect a ~30s delay. After that it's instant for 15 min.

---

## Step 4 — Deploy frontend to Vercel

1. Sign up at https://vercel.com — free Hobby tier.
2. **Add New → Project** → import your GitHub repo → set:
   - **Root Directory:** `frontend`
   - **Framework Preset:** Vite (Vercel auto-detects from [vercel.json](frontend/vercel.json))
   - **Build Command:** `npm run build` (already in vercel.json)
   - **Output Directory:** `dist` (already in vercel.json)
3. **Environment Variables** — add one:
   - `VITE_API_BASE_URL` = your Render backend URL, e.g. `https://careerpilot-backend.onrender.com`
4. **Deploy.** First deploy takes ~1 minute.
5. Vercel gives you a URL like `https://careerpilot-ai.vercel.app`. Open it.

---

## Step 5 — End-to-end smoke

1. Visit your Vercel URL. The frontend should serve.
2. Click **Create one** → register with any email + 8+ char password. The first POST will be slow (~30s) if Render is cold; subsequent requests are fast.
3. You should land on the Dashboard with all scores = 0.
4. **Jobs tab** → "Add job" → paste a real job description → Save.
5. **Resumes tab** → upload a resume (only works if you finished step 4 / R2).
6. **AI Workflow tab** → paste the resume ID and job ID → "Start workflow". Wait 30–90s. Workflow pauses at "Human Approval". Click **Approve**.
7. **Dashboard** updates — Career Health, Resume Score, ATS Score, Job Match Score, Interview Readiness, Offer Probability all populated.

You now have a public URL you can share. 🎉

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Backend logs: `relation "users" does not exist` | Flyway didn't run because of pooler weirdness | Confirm `SPRING_DATASOURCE_URL` uses the **direct** Neon endpoint (no `-pooler`). |
| Backend logs: `password authentication failed for user "neondb_owner"` | You rotated the Neon password but only updated some env vars | Re-paste the new password into `SPRING_DATASOURCE_PASSWORD` in Render dashboard. |
| Agent service logs: `RuntimeError: GEMINI_API_KEY is not configured` | Env var not set on the agent service | Render dashboard → careerpilot-agent → Environment → add `GEMINI_API_KEY`. |
| Frontend can register but workflow runs hang | The backend can't reach the agent service | Check that `AGENT_SERVICE_URL` on the backend points at the **public** agent URL (Render fills this automatically via `fromService` in `render.yaml` — verify in dashboard). |
| First page load takes 60s | Render free tier cold start — service is waking | Normal. Stays warm for 15 min after a hit. To avoid sleep, use a cron pinger like https://cron-job.org to GET `/actuator/health` every 10 min. |
| Resume upload returns 500 | R2 not configured or wrong endpoint | Either finish step 4 or accept that file uploads won't work. The rest of the demo doesn't need it. |
| Kafka producer error spam in backend logs | Expected — Kafka isn't deployed | Ignorable. Workflow events are fire-and-forget; the app doesn't depend on delivery. |

---

## Keeping costs at $0

- **Neon free tier:** 0.5 GB storage, autosuspend. Plenty for a demo.
- **Upstash free:** 10,000 commands per day. The backend barely uses Redis right now, so you're well under.
- **Cloudflare R2 free:** 10 GB storage, 1 million class-A operations/month, free egress.
- **Gemini free tier:** generous rate limits on `gemini-2.5-flash`. A single workflow run is ~7 API calls. Stay on Flash.
- **Render free:** 750 instance-hours/month per service, sleep after 15 min idle. Two services × 730h/month = enough.
- **Vercel free Hobby:** 100 GB bandwidth/month — laughably more than a demo needs.

You only start paying when the demo gets real traffic, at which point you scale a specific component.
