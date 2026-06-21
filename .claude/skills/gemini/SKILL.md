# AI Providers Skill (Gemini, DeepSeek, Qwen)

## Purpose
Configure, test, debug, and monitor AI provider integration: Gemini primary, DeepSeek/Qwen fallback via NVIDIA.

---

## Workflows

### Workflow: Test Gemini API Key

```bash
# Method 1: Using Python directly
python3 << 'EOF'
import google.generativeai as genai

# Configure with your key
genai.configure(api_key="YOUR_GEMINI_API_KEY")

# Try generating content
model = genai.GenerativeModel('gemini-2.5-pro')
response = model.generate_content("Say 'Hello from Gemini'")
print("✅ Gemini works!")
print(response.text)
EOF

# Method 2: Using curl (simpler)
curl -s "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent" \
  -H "Content-Type: application/json" \
  -H "x-goog-api-key: YOUR_GEMINI_API_KEY" \
  -d '{
    "contents": [{
      "parts": [{"text": "Say hello"}]
    }]
  }' | jq '.candidates[0].content.parts[0].text'
```

**Expected output**: `✅ Gemini works!` and response text

---

### Workflow: Check Provider Health from Backend

```bash
# Backend exposes provider health at diagnostics endpoint
curl -s http://localhost:8080/api/diagnostics/ai | jq '.'

# Expected response shows:
{
  "api_keys": {
    "gemini_loaded": true,
    "nvidia_loaded": false  # OK if NVIDIA_API_KEY not set
  },
  "gateway_health": {
    "gemini": "UP",
    "deepseek": "NOT_CONFIGURED",
    "qwen": "NOT_CONFIGURED"
  },
  "gateway_stats": {
    "total_calls": 0,
    "fallbacks": 0,
    "failures": 0
  },
  "default_temperature": 0.7,
  "primary_provider": "gemini"
}
```

**Interpret status**:
- ✅ `UP` — Provider configured and responding
- ⚠️ `DEGRADED` — Provider responding slowly or errors
- ❌ `QUOTA_EXCEEDED` — Hit rate limit (will failover)
- ❌ `DOWN` — Provider unreachable
- ⏳ `UNKNOWN` — Not yet checked

---

### Workflow: Enable DeepSeek Provider (via NVIDIA)

```bash
# 1. Get NVIDIA NIM API key from https://build.nvidia.com/
# (requires free account, get key under "API Keys")

# 2. Update .env
cat >> .env << 'EOF'
NVIDIA_API_KEY=nvapi-...your-key...
NVIDIA_BASE_URL=https://integrate.api.nvidia.com/v1
NVIDIA_DEEPSEEK_MODEL=nvidia/deepseek-r1
AI_PROVIDER_ORDER=deepseek,qwen,gemini
EOF

# 3. Restart backend to pick up new config
pkill -f "spring-boot:run"
cd backend && mvn spring-boot:run

# 4. Verify DeepSeek enabled
curl -s http://localhost:8080/api/diagnostics/ai | jq '.gateway_health.deepseek'
# Should show "UP" or "NOT_CONFIGURED" (if key invalid)
```

---

### Workflow: Enable Qwen Provider (via NVIDIA)

```bash
# Prerequisites: NVIDIA_API_KEY already set (from DeepSeek workflow)

# Update .env
cat >> .env << 'EOF'
NVIDIA_QWEN_MODEL=nvidia/qwen2.5-72b-instruct
EOF

# Or modify existing AI_PROVIDER_ORDER
# OLD: deepseek,qwen,gemini
# NEW: deepseek,qwen,gemini (add qwen if not there)

# Restart backend
pkill -f "spring-boot:run"
cd backend && mvn spring-boot:run

# Verify Qwen enabled
curl -s http://localhost:8080/api/diagnostics/ai | jq '.gateway_health'
```

---

### Workflow: Change Default Model

```bash
# Update .env
sed -i 's/AI_MODEL=.*/AI_MODEL=gemini-2.5-flash/' .env

# Available models:
# - gemini-2.5-pro (most capable, slower, expensive)
# - gemini-2.5-flash (faster, cheaper, good for most tasks)
# - nvidia/deepseek-r1 (via NVIDIA NIM)
# - nvidia/qwen2.5-72b-instruct (via NVIDIA NIM)

# Restart backend
pkill -f "spring-boot:run"
cd backend && mvn spring-boot:run

# Verify model changed
curl -s http://localhost:8080/api/diagnostics/ai | jq '.default_model'
```

---

### Workflow: Change Provider Failover Order

```bash
# Edit .env to customize provider chain
# Default order: deepseek, qwen, gemini
# (attempts each in sequence on failure)

# To use only Gemini:
AI_PROVIDER_ORDER=gemini

# To try Qwen first:
AI_PROVIDER_ORDER=qwen,deepseek,gemini

# To disable DeepSeek but keep Qwen:
NVIDIA_DEEPSEEK_MODEL=
AI_PROVIDER_ORDER=qwen,gemini

# Restart backend
pkill -f "spring-boot:run"
cd backend && mvn spring-boot:run

# Verify order
curl -s http://localhost:8080/api/diagnostics/ai | jq '.provider_order'
```

---

### Workflow: Test AI in Copilot

```bash
# 1. Get JWT token (register/login)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "organizationName": "Test Org",
    "email": "test@example.com",
    "password": "TestPass123!",
    "fullName": "Test User"
  }' | jq -r '.accessToken')

# 2. Send message to Copilot (streams response as SSE)
curl -X POST http://localhost:8080/api/copilot/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What makes a good resume?",
    "context": "resume_review"
  }' \
  -N  # No buffering, see stream in real-time

# 3. Listen for response tokens
# Each line is: data: {token}
# Last line is: data: [DONE] {provider_name}

# Example output:
# data: "A"
# data: " good"
# data: " resume"
# data: " is"
# ...
# data: [DONE] {"provider":"gemini"}
```

---

### Workflow: Test AI in Workflow

```bash
# 1. Get token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{...}' | jq -r '.accessToken')

# 2. Create resume
RESUME=$(curl -s -X POST http://localhost:8080/api/resumes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "filename": "resume.txt",
    "parsedText": "Senior Software Engineer with 5+ years experience..."
  }')
RESUME_ID=$(echo $RESUME | jq -r '.id')

# 3. Create job
JOB=$(curl -s -X POST http://localhost:8080/api/jobs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Senior Software Engineer",
    "company": "Tech Corp",
    "description": "We are looking for...",
    "location": "San Francisco",
    "salaryRange": "$200k - $300k"
  }')
JOB_ID=$(echo $JOB | jq -r '.id')

# 4. Start workflow (calls AI agents)
WORKFLOW=$(curl -s -X POST http://localhost:8080/api/workflows/run \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"resumeId\": \"$RESUME_ID\",
    \"jobIds\": [\"$JOB_ID\"],
    \"targetRole\": \"Senior Engineer\",
    \"targetSeniority\": \"5+ years\"
  }")

echo $WORKFLOW | jq .
# Should show status: RUNNING or INTERRUPTED (waiting for approval)
# Should NOT show JsonNode error

# 5. Check workflow status (calls agents behind scenes)
THREAD_ID=$(echo $WORKFLOW | jq -r '.threadId')
curl -s http://localhost:8080/api/workflows/$THREAD_ID \
  -H "Authorization: Bearer $TOKEN" | jq '.state'
```

---

### Workflow: Check Agent Service Rate Limiter

```bash
# Agent service enforces rate limits on Gemini
# View metrics at:
curl http://localhost:8088/metrics | grep gemini_rate_limiter

# Metrics show:
# - Requests per minute (RPM) current/limit
# - Tokens per minute (TPM) current/limit
# - Queue depth
# - Retry attempts

# Example:
# gemini_rate_limiter_requests_per_minute 45/120
# gemini_rate_limiter_tokens_per_minute 8500/100000
```

---

### Workflow: Debug Provider Failures

```bash
# 1. Check provider health
curl -s http://localhost:8080/api/diagnostics/ai | jq '.gateway_health'

# 2. Check recent failures
curl -s http://localhost:8080/api/diagnostics/ai | jq '.gateway_stats'
# Shows total_calls, fallbacks, failures per provider

# 3. Check logs for provider errors
docker compose logs backend | grep -i "provider\|gemini\|deepseek" | tail -20

# 4. Check if it's rate limiting (429)
docker compose logs backend | grep "429\|QUOTA"

# 5. Check if API key is invalid
docker compose logs backend | grep -i "invalid.*key\|unauthorized\|403"

# 6. Check network issues
docker compose logs backend | grep -i "timeout\|connection\|unreachable"
```

---

## Checklists

### ✅ Before Using AI Features

- [ ] GEMINI_API_KEY set: `grep GEMINI_API_KEY .env | grep -v "^#"`
- [ ] API key is valid: `curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent" -H "x-goog-api-key: $GEMINI_API_KEY" -d '{"contents":[{"parts":[{"text":"test"}]}]}' | jq` returns response
- [ ] Backend restarted after .env changes: `docker compose logs backend | grep "Started"`
- [ ] Health endpoint shows UP: `curl http://localhost:8080/api/diagnostics/ai | jq '.gateway_health.gemini'` = `UP`
- [ ] No quota errors in logs: `docker compose logs backend | grep -i "429\|quota" | wc -l` = 0

### ✅ Testing AI Endpoints

- [ ] Copilot streams work: `curl -N http://localhost:8080/api/copilot/stream` returns tokens
- [ ] Workflow starts: POST /api/workflows/run returns 200 (not 500)
- [ ] Agent service healthy: `curl http://localhost:8088/health` returns 200
- [ ] Rate limiter not blocking: `/metrics` shows reasonable RPM/TPM usage
- [ ] No "Type definition error: JsonNode"**: Responses are valid JSON

### ✅ For Production Deployment

- [ ] GEMINI_API_KEY in secure vault (not in git): `git log -p | grep GEMINI_API_KEY` returns nothing
- [ ] Fallback providers configured (optional): AI_PROVIDER_ORDER set
- [ ] Rate limits tuned: Check agent-service config matches traffic patterns
- [ ] Monitoring in place: Log provider failures, track failover rate
- [ ] Error handling: UI shows user-friendly messages on provider failure

---

## Troubleshooting

### ❌ Issue: "Gemini API key not loaded"

**Error message**: In backend logs or diagnostics endpoint shows `gemini_loaded: false`

**Fix**:
```bash
# Check .env
grep GEMINI_API_KEY .env | grep -v "^#"

# Should NOT be empty, should be: GEMINI_API_KEY=sk-...

# Verify it's set in container environment
docker compose exec backend env | grep GEMINI_API_KEY

# If not set, rebuild
docker compose build --no-cache backend
docker compose up -d backend

# Restart and verify
curl http://localhost:8080/api/diagnostics/ai | jq '.api_keys'
```

---

### ❌ Issue: HTTP 429 (Rate Limited)

**Error**: Provider returns 429 Too Many Requests

**Symptoms**:
- Workflow times out
- Copilot stops responding mid-stream
- Logs show "429" or "QUOTA_EXCEEDED"

**Fix**:
```bash
# Check rate limiter metrics
curl http://localhost:8088/metrics | grep "per_minute"

# Wait for rate limit window to reset
# RPM limits typically reset every minute

# Check if hitting token limit (TPM)
# Each request uses tokens, limits vary by provider
# May need to use faster model: gemini-2.5-flash instead of pro

# Update .env
AI_MODEL=gemini-2.5-flash

# Restart
pkill -f "spring-boot:run"
cd backend && mvn spring-boot:run
```

---

### ❌ Issue: HTTP 401 (Unauthorized)

**Error**: "Invalid API key", "Unauthorized"

**Symptoms**: Diagnostics shows `gemini: DOWN`

**Fix**:
```bash
# Verify API key is correct
# 1. Get key from Google AI Studio: https://aistudio.google.com/
# 2. Update .env
GEMINI_API_KEY=<correct-key>

# 3. Rebuild and restart
docker compose build --no-cache backend
docker compose up -d backend

# 4. Verify
curl http://localhost:8080/api/diagnostics/ai | jq '.gateway_health.gemini'
```

---

### ❌ Issue: Provider Timeout

**Error**: "Provider request timed out", "Deadline exceeded"

**Symptoms**: Workflows fail slowly, copilot stops responding

**Causes**:
1. Provider is slow (DeepSeek can be slow)
2. Network latency
3. Request too large (long resume + multiple jobs)

**Fix**:
```bash
# 1. Check which provider is timing out
docker compose logs backend | grep -i "timeout\|deepseek\|gemini"

# 2. Use faster provider
AI_PROVIDER_ORDER=gemini
AI_MODEL=gemini-2.5-flash

# Restart
pkill -f "spring-boot:run"
cd backend && mvn spring-boot:run

# 3. Check network
ping generativelanguage.googleapis.com  # If Gemini slow
ping integrate.api.nvidia.com            # If DeepSeek slow
```

---

### ❌ Issue: Copilot Stream Stops Mid-Response

**Symptoms**: Response cuts off, no [DONE] message

**Cause**: Provider failed mid-stream, failover not possible

**Why it happens**:
- Streaming responses can't failover after first token
- Once browser starts receiving from Provider A, can't switch to B
- SSE connection is already open

**Fix**:
```bash
# 1. This is NOT a bug, it's by design (can't switch mid-stream)
# 2. User should retry, will failover on next request

# 3. To reduce mid-stream failures:
# - Use more reliable provider (Gemini > DeepSeek)
# - Use faster model (flash > pro, smaller responses)
# - Check provider health before sending

# Check logs for what failed
docker compose logs backend | grep -B5 "Stream error\|timeout"

# Switch to reliable provider
AI_PROVIDER_ORDER=gemini
AI_MODEL=gemini-2.5-flash
```

---

### ❌ Issue: "Unable to reach agent-service"

**Error**: Workflow fails to start, backend logs show agent service unreachable

**Fix**:
```bash
# Check agent service running
docker compose ps | grep agent-service
# Should show RUNNING

# Verify it's responding
curl http://localhost:8088/health

# Check backend has correct URL
docker compose exec backend env | grep AGENT_SERVICE_URL
# Should be: http://agent-service:8088 (not localhost)

# If URL wrong, update docker-compose.yml or .env:
AGENT_SERVICE_URL=http://agent-service:8088

# Restart backend
docker compose restart backend
```

---

### ❌ Issue: Workflow Completes but State is Empty

**Symptoms**: workflow.state is empty or missing keys

**Possible causes**:
1. Agent nodes didn't run successfully
2. Agent service error (check agent logs)
3. State not persisted (database issue)

**Debug**:
```bash
# Check agent service logs
docker compose logs agent-service | tail -50 | grep -i "error"

# Check workflow run in database
docker compose exec db psql -U postgres -d careerpilot -c "SELECT state FROM workflow_runs LIMIT 1;"

# Check if PostgresSaver checkpoint created
docker compose exec db psql -U postgres -d careerpilot -c "SELECT * FROM checkpoint_writes LIMIT 1;"

# If empty, agents may not have run. Check:
docker compose logs agent-service | grep -i "node\|resume_intelligence"
```

---

## Tips & Best Practices

1. **Use Gemini for reliability**: It's the most stable, use it as primary
2. **Use flash model for speed**: Faster + cheaper than pro, good enough for most tasks
3. **Monitor rate limits**: Check `/metrics` endpoint regularly, tune if hitting limits
4. **Handle timeouts gracefully**: UI should show "Provider unavailable, try again" not crash
5. **Log provider failures**: Track which providers fail most, adjust order
6. **Cache responses**: Consider caching common queries to reduce API calls
7. **Test failover**: Intentionally break primary provider to verify failover works
8. **Stream carefully**: Streaming responses can't failover, document this to users

---

**Status**: 🟢 Ready  
**Last Updated**: 2026-06-20
