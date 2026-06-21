# Gemini API Skill

## Purpose
Configure, test, and debug integration with Google's Gemini API (`google-generativeai` /
`google-genai`). Generic across projects — which model, which framework wraps it, and any
multi-provider failover design are repository-specific and belong in that repo's own docs.

---

## Workflows

### Workflow: Test an API Key Directly

```bash
curl -s "https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent" \
  -H "Content-Type: application/json" \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  -d '{"contents":[{"parts":[{"text":"Say hello"}]}]}' \
  | jq '.candidates[0].content.parts[0].text'
```

Or via the Python SDK:

```python
import google.generativeai as genai
genai.configure(api_key="...")
model = genai.GenerativeModel("<model>")
print(model.generate_content("Say hello").text)
```

A successful response confirms the key is valid and the model name is correct — isolate this
before debugging anything built on top of it.

---

### Workflow: Request Structured (Schema-Constrained) Output

```python
response = model.generate_content(
    prompt,
    generation_config={
        "response_mime_type": "application/json",
        "response_schema": YOUR_JSON_SCHEMA,
    },
)
```

Use this whenever the caller needs typed/structured output rather than free text — it removes
an entire class of "parse the model's prose into JSON" bugs. Validate the returned JSON against
the schema you expect; a 200 response doesn't guarantee every field is populated.

---

### Workflow: Stream a Response

```python
for chunk in model.generate_content(prompt, stream=True):
    print(chunk.text, end="")
```

Streaming has one structural constraint worth remembering: once the first chunk has reached
the caller, you cannot transparently swap to a different model/provider for the rest of that
response — any retry/failover logic must happen *before* the first chunk is emitted.

---

### Workflow: Handle Rate Limits & Quota

- HTTP 429 = rate limited or quota exceeded. Don't blindly retry — back off (ideally with
  jitter) or fail over to a different model/provider if one is configured.
- If hitting request-per-minute or token-per-minute limits, the two practical levers are: use a
  smaller/faster model for high-volume calls, or add client-side throttling ahead of the API
  call (a token-bucket limiter) rather than relying on retries alone.

```python
import time, random
for attempt in range(max_retries):
    try:
        return model.generate_content(prompt)
    except ResourceExhausted:
        time.sleep((2 ** attempt) + random.uniform(0, 1))
```

---

### Workflow: Switch Models

```bash
# Faster/cheaper, good for high-volume or latency-sensitive calls
AI_MODEL=gemini-2.5-flash
# More capable, slower, costlier — reserve for tasks that need it
AI_MODEL=gemini-2.5-pro
```

Whatever config mechanism the project uses (env var, settings file), changing it usually
requires restarting the process that holds the configured client.

---

### Workflow: Debug a Failing Call

```bash
# 1. Isolate: does the key work at all, outside the app?
curl ... # see "Test an API Key Directly" above

# 2. Check the actual HTTP status and error body the app received
# 401/403 → bad/revoked key, or key lacks access to the requested model
# 404     → wrong model name
# 429     → rate limit / quota
# 5xx     → transient provider-side issue, safe to retry with backoff

# 3. Check the app's own logs for what it sent (prompt size, model name) right before the failure
```

---

## Checklists

### ✅ Before Using Gemini in an App

- [ ] API key set and non-empty in the environment the process actually runs in
- [ ] Key verified independently (curl/SDK call outside the app) before debugging app code
- [ ] Model name spelled exactly as the API expects (model names change/retire — check current
      availability if you get a 404)
- [ ] If structured output is needed, the JSON schema is defined and validated against a real
      response, not just assumed correct

### ✅ Testing AI-Backed Endpoints

- [ ] A non-streaming call succeeds end-to-end through the app, not just against the raw API
- [ ] A streaming call (if used) delivers chunks and a clean terminal/done signal
- [ ] Rate-limit handling is exercised at least once deliberately (not just hoped to work)
- [ ] Error responses from the provider surface as a clear, typed error in the app — not a
      generic 500 with no context

### ✅ Before Production

- [ ] API key stored in a secret manager / env injection, never committed to source control —
      `git log -p | grep -i api_key` should return nothing
- [ ] Sensible default model chosen for cost/latency tradeoffs at expected volume
- [ ] Backoff/retry (and failover, if multiple providers are configured) actually tested under
      a simulated 429, not just read in the code
- [ ] User-facing error messages on provider failure are graceful, not a stack trace

---

## Troubleshooting

### ❌ "API key not loaded" / Empty Key in App Logs

Confirm the env var is actually set in the process's environment (not just a local shell or
`.env` file the process never reads), then restart the process — most clients read the key
once at construction time and won't pick up a later change without a restart.

### ❌ HTTP 429 (Rate Limited / Quota Exceeded)

Don't retry immediately in a tight loop — that burns quota faster. Back off with jitter, or
reduce request volume (batch, cache, or use a cheaper/faster model for high-volume paths).

### ❌ HTTP 401/403 (Unauthorized / Forbidden)

The key is invalid, revoked, or doesn't have access to the requested model/feature. Generate a
fresh key from the provider's console and re-test it directly (see the test workflow above)
before assuming it's an app bug.

### ❌ HTTP 404 (Model Not Found)

Model names are versioned and occasionally retired — check the provider's current model list;
don't assume a model name from older code/docs is still valid.

### ❌ Request Times Out / "Deadline Exceeded"

Check whether the prompt is unusually large (long context, many attachments) — large requests
take longer and are more likely to hit a client-side timeout that's set too aggressively.
Increase the client timeout or trim the request size.

### ❌ Streamed Response Cuts Off Mid-Way

This is a structural limitation, not necessarily a bug: once a stream has started, you can't
transparently retry/fail over to another provider for the remainder. The caller should surface
a clear "response interrupted, please retry" rather than silently truncating; the retry then
starts a fresh stream from the (possibly different) provider.

### ❌ Structured Output Doesn't Match the Schema

A 200 response with `response_schema` set still isn't a hard guarantee every field is present
and correctly typed in edge cases — validate the parsed JSON against your schema/types in code
rather than trusting it blindly, and log the raw response when validation fails so you can see
what the model actually returned.

---

## Tips & Best Practices

1. Default to the fastest/cheapest model that meets the quality bar for a given call; reserve
   the most capable model for tasks that actually need it.
2. Use schema-constrained (`response_schema`) output whenever the caller needs typed data —
   it's strictly more reliable than parsing free text.
3. Treat 429s and 5xxs differently: 429 means slow down or fail over, 5xx means a transient
   retry with backoff is usually fine.
4. Never log full prompts/responses containing user data in plaintext logs without considering
   what's in them — treat AI request/response logging like any other PII-adjacent logging.
5. If a project needs resilience beyond a single provider, design the failover boundary
   explicitly around the streaming constraint above — failover before the first token, commit
   to one provider after.
