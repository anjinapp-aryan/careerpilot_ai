"""
Live provider connectivity / auth validator - "does the failover actually work?"

WHY THIS EXISTS
---------------
The workflow failover chain is DeepSeek -> Gemini -> Groq -> Qwen. In normal runs
the 3rd/4th links (Groq, Qwen) almost never execute, because DeepSeek or Gemini
usually answer first. So a broken Groq/Qwen key stays invisible until the day
everything above it is rate-limited at once - the worst possible time to discover it.

This utility proves each provider's key + endpoint + model are working by making
ONE real, tiny call per provider ("Say hi") through the EXACT provider classes the
workflow uses (same auth header, same /chat/completions payload, same schema path).
It does NOT run the LangGraph workflow and does NOT loop, so it spends ~1 request /
a handful of tokens per provider - it will not burn your Gemini/DeepSeek limits.

DEFAULT: tests ONLY groq + qwen (the rarely-exercised fallback links). Gemini and
DeepSeek are skipped unless you ask for them, so you can validate the fallback path
without touching the primary providers' quotas.

USAGE (from agent-service/)
---------------------------
    python validate_providers.py                 # groq + qwen only (default)
    python validate_providers.py --all           # every configured provider
    python validate_providers.py --providers groq # just one
    python validate_providers.py --providers deepseek,gemini,groq,qwen

Exit code: 0 if every TARGETED + configured provider answered; 1 otherwise.
(A provider with no key configured is reported as SKIPPED, not a failure.)
"""
from __future__ import annotations

import argparse
import os
import sys
import time

# Load the repo-root .env into os.environ BEFORE importing app.config (which
# instantiates Settings() at import time). pydantic-settings reads os.environ
# first, so this makes the script work when run from agent-service/ locally -
# the real .env lives at the repo root, one level up - while still deferring to
# already-present env vars when run inside the container.
try:
    from dotenv import load_dotenv

    _root_env = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env")
    if os.path.exists(_root_env):
        load_dotenv(_root_env, override=False)
except Exception:  # dotenv missing or unreadable - rely on real env vars
    pass

from app.config import settings
from app.workflow_ai_gateway import (
    DeepSeekProvider,
    GeminiWorkflowProvider,
    GroqProvider,
    QwenProvider,
)

# A trivial structured request - one short string field. Works across all four
# providers (NVIDIA/Groq json_schema response_format + Gemini response_schema)
# and costs only a few tokens.
_PROMPT = "Reply with a one-word greeting."
_SCHEMA = {
    "type": "object",
    "properties": {"reply": {"type": "string"}},
    "required": ["reply"],
}
_TIMEOUT_S = 20.0


def _mask(key: str) -> str:
    if not key:
        return "<empty>"
    return f"{key[:8]}...{key[-4:]} (len={len(key)})" if len(key) > 12 else "<set>"


def _classify_error(exc: Exception) -> str:
    msg = str(exc).lower()
    if "401" in msg or "unauthor" in msg:
        return "AUTH_ERROR (401) - key rejected"
    if "403" in msg or "forbidden" in msg:
        return "FORBIDDEN (403) - key lacks access to this model"
    if "404" in msg or "not found" in msg:
        return "MODEL_NOT_FOUND (404) - wrong model id / namespace"
    if "429" in msg or "too many requests" in msg or "rate limit" in msg or "quota" in msg:
        return "RATE_LIMITED (429) - key works, just throttled right now"
    if "timeout" in msg or "timed out" in msg:
        return "TIMEOUT - endpoint unreachable or slow"
    if "connect" in msg:
        return "CONNECTION_ERROR - endpoint unreachable"
    return f"{type(exc).__name__}: {exc}"


def _build(provider_name: str):
    """Return (provider_instance_or_None, configured_bool, key_for_display)."""
    if provider_name == "deepseek":
        key = settings.deep_sheek_nvidia_api_key
        if not key:
            return None, False, key
        return DeepSeekProvider(key, settings.nvidia_base_url, settings.nvidia_deepseek_model), True, key
    if provider_name == "qwen":
        key = settings.qwen3_nvidia_api_key
        if not key:
            return None, False, key
        return QwenProvider(key, settings.nvidia_base_url, settings.nvidia_qwen_model), True, key
    if provider_name == "groq":
        key = settings.groq_api_key
        if not key:
            return None, False, key
        return GroqProvider(key, settings.groq_base_url, settings.groq_model), True, key
    if provider_name == "gemini":
        key = settings.gemini_api_key
        if not key:
            return None, False, key
        return GeminiWorkflowProvider(key, settings.ai_model), True, key
    raise ValueError(f"unknown provider '{provider_name}'")


def _test_provider(provider_name: str) -> str:
    """Run one tiny call. Returns 'PASS' | 'FAIL' | 'SKIP'."""
    try:
        provider, configured, key = _build(provider_name)
    except Exception as e:  # construction itself failed (bad config)
        print(f"  [{provider_name:9}] FAIL - could not construct provider: {e}")
        return "FAIL"

    if not configured:
        print(f"  [{provider_name:9}] SKIP - no API key configured")
        return "SKIP"

    print(f"  [{provider_name:9}] key={_mask(key)}")
    t0 = time.monotonic()
    try:
        result = provider.generate_structured_response(
            _PROMPT, _SCHEMA, system="You are a test probe.", timeout_seconds=_TIMEOUT_S
        )
        latency_ms = int((time.monotonic() - t0) * 1000)
        reply = result.get("reply") if isinstance(result, dict) else result
        print(f"  [{provider_name:9}] PASS - {latency_ms}ms - reply={reply!r}")
        return "PASS"
    except Exception as e:
        latency_ms = int((time.monotonic() - t0) * 1000)
        print(f"  [{provider_name:9}] FAIL - {latency_ms}ms - {_classify_error(e)}")
        return "FAIL"


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate AI provider keys with one tiny live call each.")
    parser.add_argument(
        "--providers",
        default="groq,qwen",
        help="comma-separated subset to test (default: groq,qwen - the fallback links)",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="test every provider (deepseek,gemini,groq,qwen) - touches primary quotas",
    )
    args = parser.parse_args()

    order = ["deepseek", "gemini", "groq", "qwen"]
    if args.all:
        targets = order
    else:
        requested = [p.strip().lower() for p in args.providers.split(",") if p.strip()]
        bad = [p for p in requested if p not in order]
        if bad:
            print(f"ERROR: unknown provider(s): {bad}. Valid: {order}")
            return 2
        targets = [p for p in order if p in requested]  # keep canonical order

    print("=" * 70)
    print("AI PROVIDER CONNECTIVITY CHECK (one tiny live call per provider)")
    print(f"Targets: {', '.join(targets)}")
    print("=" * 70)

    results = {name: _test_provider(name) for name in targets}

    print("-" * 70)
    passed = [n for n, r in results.items() if r == "PASS"]
    failed = [n for n, r in results.items() if r == "FAIL"]
    skipped = [n for n, r in results.items() if r == "SKIP"]
    print(f"PASS: {passed or '-'}")
    print(f"SKIP (no key): {skipped or '-'}")
    print(f"FAIL: {failed or '-'}")
    print("=" * 70)

    # A rate-limited primary is NOT a failure of this check's intent (the key works);
    # but for a clear exit code we treat any FAIL as non-zero so CI/operators notice.
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
