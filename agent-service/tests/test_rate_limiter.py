"""
Unit tests for app.rate_limiter.

Run with:
    pip install -r requirements-dev.txt
    pytest tests/test_rate_limiter.py -v

All tests mock time.monotonic and asyncio.sleep so the suite finishes in <1 s.
"""
from __future__ import annotations

import asyncio
import threading
import time
from unittest.mock import MagicMock, call, patch

import pytest
import pytest_asyncio  # noqa: F401  (registers asyncio mode)

from app.rate_limiter import (
    GeminiRateLimiter,
    RateLimiterConfig,
    TokenBucket,
    _is_retryable,
    _jittered_backoff,
)

# ---------------------------------------------------------------------------
# Pytest-asyncio configuration
# ---------------------------------------------------------------------------

pytestmark = pytest.mark.asyncio


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _fast_config(**overrides) -> RateLimiterConfig:
    """Config with very small delays so sync tests don't actually wait."""
    defaults = dict(
        max_rpm=60,
        max_tpm=1_000_000,
        min_request_interval=0.0,
        max_retries=3,
        base_retry_delay=0.001,
        max_retry_delay=0.01,
    )
    defaults.update(overrides)
    return RateLimiterConfig(**defaults)


@pytest.fixture(autouse=True)
def reset_singleton():
    """Destroy the GeminiRateLimiter singleton before each test."""
    GeminiRateLimiter.reset()
    yield
    GeminiRateLimiter.reset()


# ===========================================================================
# _is_retryable
# ===========================================================================

class TestIsRetryable:
    def test_resource_exhausted_exception(self):
        try:
            from google.api_core.exceptions import ResourceExhausted
            exc = ResourceExhausted("quota exceeded")
            assert _is_retryable(exc)
        except ImportError:
            pytest.skip("google-api-core not installed")

    def test_service_unavailable_exception(self):
        try:
            from google.api_core.exceptions import ServiceUnavailable
            exc = ServiceUnavailable("backend down")
            assert _is_retryable(exc)
        except ImportError:
            pytest.skip("google-api-core not installed")

    def test_429_in_message(self):
        assert _is_retryable(Exception("HTTP 429: quota exceeded"))

    def test_rate_limit_in_message(self):
        assert _is_retryable(Exception("rate limit reached"))

    def test_503_in_message(self):
        assert _is_retryable(Exception("503 service unavailable"))

    def test_overloaded_in_message(self):
        assert _is_retryable(Exception("model overloaded"))

    def test_non_retryable_value_error(self):
        assert not _is_retryable(ValueError("bad input"))

    def test_non_retryable_json_error(self):
        import json
        assert not _is_retryable(json.JSONDecodeError("nope", "", 0))

    def test_non_retryable_key_error(self):
        assert not _is_retryable(KeyError("missing"))


# ===========================================================================
# _jittered_backoff
# ===========================================================================

class TestJitteredBackoff:
    def test_returns_float_in_range(self):
        for attempt in range(5):
            delay = _jittered_backoff(attempt, base=2.0, cap=60.0)
            ceiling = min(60.0, 2.0 * (2 ** attempt))
            assert 0.0 <= delay <= ceiling

    def test_never_exceeds_cap(self):
        for attempt in range(10):
            delay = _jittered_backoff(attempt, base=2.0, cap=5.0)
            assert delay <= 5.0

    def test_first_attempt_within_base(self):
        for _ in range(20):
            delay = _jittered_backoff(0, base=2.0, cap=60.0)
            assert 0.0 <= delay <= 2.0


# ===========================================================================
# TokenBucket
# ===========================================================================

class TestTokenBucket:
    def test_initial_tokens_equal_capacity(self):
        bucket = TokenBucket(rate_per_second=1.0, capacity=10.0)
        assert bucket.available == pytest.approx(10.0, abs=0.01)

    def test_no_wait_when_tokens_available(self):
        bucket = TokenBucket(rate_per_second=1.0, capacity=10.0)
        assert bucket.peek_wait(5.0) == 0.0

    def test_wait_when_tokens_insufficient(self):
        bucket = TokenBucket(rate_per_second=1.0, capacity=10.0)
        bucket.consume(10.0)
        wait = bucket.peek_wait(1.0)
        assert wait == pytest.approx(1.0, abs=0.05)

    def test_consume_reduces_tokens(self):
        bucket = TokenBucket(rate_per_second=1.0, capacity=10.0)
        bucket.consume(3.0)
        assert bucket.available == pytest.approx(7.0, abs=0.01)

    def test_tokens_refill_over_time(self):
        bucket = TokenBucket(rate_per_second=10.0, capacity=10.0)
        bucket.consume(10.0)

        # Advance monotonic time by 0.5 s → should refill 5 tokens
        original_monotonic = time.monotonic
        start = original_monotonic()
        with patch("app.rate_limiter.time.monotonic", side_effect=lambda: start + 0.5):
            available = bucket.available
        assert available == pytest.approx(5.0, abs=0.1)

    def test_available_capped_at_capacity(self):
        bucket = TokenBucket(rate_per_second=100.0, capacity=5.0)
        # Even after a long time, available should not exceed capacity
        start = time.monotonic()
        with patch("app.rate_limiter.time.monotonic", side_effect=lambda: start + 100.0):
            assert bucket.available <= 5.0 + 0.01  # small fp tolerance

    def test_invalid_rate_raises(self):
        with pytest.raises(ValueError):
            TokenBucket(rate_per_second=0.0, capacity=10.0)

    def test_invalid_capacity_raises(self):
        with pytest.raises(ValueError):
            TokenBucket(rate_per_second=1.0, capacity=0.0)

    def test_peek_does_not_consume(self):
        bucket = TokenBucket(rate_per_second=1.0, capacity=10.0)
        bucket.peek_wait(5.0)
        bucket.peek_wait(5.0)
        assert bucket.available == pytest.approx(10.0, abs=0.01)

    def test_thread_safe_concurrent_consume(self):
        bucket = TokenBucket(rate_per_second=100.0, capacity=100.0)
        errors: list[Exception] = []

        def worker():
            try:
                for _ in range(10):
                    bucket.consume(1.0)
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=worker) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert not errors
        assert bucket.available >= 0.0


# ===========================================================================
# GeminiRateLimiter — singleton
# ===========================================================================

class TestGeminiRateLimiterSingleton:
    def test_same_instance_returned(self):
        a = GeminiRateLimiter.get_instance()
        b = GeminiRateLimiter.get_instance()
        assert a is b

    def test_reset_creates_new_instance(self):
        a = GeminiRateLimiter.get_instance()
        GeminiRateLimiter.reset()
        b = GeminiRateLimiter.get_instance()
        assert a is not b

    def test_config_respected(self):
        cfg = RateLimiterConfig(max_rpm=5, max_tpm=10_000)
        limiter = GeminiRateLimiter.get_instance(cfg)
        assert limiter.config.max_rpm == 5
        assert limiter.config.max_tpm == 10_000


# ===========================================================================
# GeminiRateLimiter — metrics
# ===========================================================================

class TestGeminiRateLimiterMetrics:
    def test_metrics_keys_present(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config())
        m = limiter.metrics
        assert set(m.keys()) == {
            "requests_total",
            "requests_delayed",
            "requests_retried",
            "tokens_consumed",
            "rate_limit_hits",
        }

    def test_requests_total_increments(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config())
        with patch("app.rate_limiter.time.sleep"):
            limiter.acquire_sync(10)
            limiter.acquire_sync(10)
        assert limiter.metrics["requests_total"] == 2

    def test_tokens_consumed_accumulates(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config())
        with patch("app.rate_limiter.time.sleep"):
            limiter.acquire_sync(100)
            limiter.acquire_sync(200)
        assert limiter.metrics["tokens_consumed"] == 300


# ===========================================================================
# GeminiRateLimiter — spacing enforcement (sync)
# ===========================================================================

class TestGeminiRateLimiterSpacingSync:
    def test_no_delay_on_first_request(self):
        cfg = _fast_config(min_request_interval=2.0)
        limiter = GeminiRateLimiter.get_instance(cfg)

        with patch("app.rate_limiter.time.sleep") as mock_sleep:
            limiter.acquire_sync(10)
        mock_sleep.assert_not_called()

    def test_delay_enforced_between_requests(self):
        cfg = _fast_config(min_request_interval=2.0, max_rpm=1000)
        limiter = GeminiRateLimiter.get_instance(cfg)

        sleeps: list[float] = []
        original_monotonic = time.monotonic
        tick = [original_monotonic()]

        def fake_monotonic():
            return tick[0]

        def fake_sleep(s: float):
            tick[0] += s
            sleeps.append(s)

        with patch("app.rate_limiter.time.monotonic", side_effect=fake_monotonic):
            with patch("app.rate_limiter.time.sleep", side_effect=fake_sleep):
                limiter.acquire_sync(10)  # first request — no wait
                limiter.acquire_sync(10)  # second request — should wait ~2s

        assert len(sleeps) >= 1
        assert sleeps[0] == pytest.approx(2.0, abs=0.05)


# ===========================================================================
# GeminiRateLimiter — RPM token bucket (sync)
# ===========================================================================

class TestGeminiRateLimiterRPMSync:
    def test_rpm_bucket_delays_when_exhausted(self):
        cfg = _fast_config(max_rpm=2, min_request_interval=0.0, max_tpm=1_000_000)
        limiter = GeminiRateLimiter.get_instance(cfg)

        sleeps: list[float] = []
        original_monotonic = time.monotonic
        tick = [original_monotonic()]

        def fake_monotonic():
            return tick[0]

        def fake_sleep(s: float):
            tick[0] += s
            sleeps.append(s)

        with patch("app.rate_limiter.time.monotonic", side_effect=fake_monotonic):
            with patch("app.rate_limiter.time.sleep", side_effect=fake_sleep):
                limiter.acquire_sync(1)   # consumes RPM token 1
                limiter.acquire_sync(1)   # consumes RPM token 2
                limiter.acquire_sync(1)   # RPM exhausted — must wait

        assert len(sleeps) >= 1
        assert limiter.metrics["rate_limit_hits"] >= 1


# ===========================================================================
# GeminiRateLimiter — async acquire
# ===========================================================================

class TestGeminiRateLimiterAsync:
    async def test_async_acquire_no_delay_first_request(self):
        cfg = _fast_config(min_request_interval=0.0)
        limiter = GeminiRateLimiter.get_instance(cfg)

        with patch("app.rate_limiter.asyncio.sleep") as mock_sleep:
            await limiter.acquire(100)
        mock_sleep.assert_not_called()

    async def test_async_acquire_records_metrics(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config())
        with patch("app.rate_limiter.asyncio.sleep"):
            await limiter.acquire(500)
        assert limiter.metrics["requests_total"] == 1
        assert limiter.metrics["tokens_consumed"] == 500

    async def test_async_acquire_spacing_delay(self):
        cfg = _fast_config(min_request_interval=1.5, max_rpm=1000)
        limiter = GeminiRateLimiter.get_instance(cfg)

        slept: list[float] = []
        original_monotonic = time.monotonic
        tick = [original_monotonic()]

        def fake_monotonic():
            return tick[0]

        async def fake_sleep(s: float):
            tick[0] += s
            slept.append(s)

        with patch("app.rate_limiter.time.monotonic", side_effect=fake_monotonic):
            with patch("app.rate_limiter.asyncio.sleep", side_effect=fake_sleep):
                await limiter.acquire(10)   # first — no delay
                await limiter.acquire(10)   # second — spacing delay

        assert len(slept) >= 1
        assert slept[0] == pytest.approx(1.5, abs=0.05)


# ===========================================================================
# GeminiRateLimiter — execute_with_retry_sync
# ===========================================================================

class TestExecuteWithRetrySync:
    def test_success_on_first_try(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config())
        fn = MagicMock(return_value="ok")

        with patch("app.rate_limiter.time.sleep"):
            result = limiter.execute_with_retry_sync(fn, estimated_tokens=50)

        assert result == "ok"
        assert fn.call_count == 1
        assert limiter.metrics["requests_retried"] == 0

    def test_retries_on_429(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config(max_retries=2))
        fn = MagicMock(side_effect=[
            Exception("HTTP 429: quota"),
            Exception("HTTP 429: quota"),
            "ok",
        ])

        with patch("app.rate_limiter.time.sleep"):
            result = limiter.execute_with_retry_sync(fn, estimated_tokens=50)

        assert result == "ok"
        assert fn.call_count == 3
        assert limiter.metrics["requests_retried"] == 2

    def test_reraises_after_exhausting_retries(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config(max_retries=2))
        fn = MagicMock(side_effect=Exception("HTTP 429: quota"))

        with patch("app.rate_limiter.time.sleep"):
            with pytest.raises(Exception, match="429"):
                limiter.execute_with_retry_sync(fn, estimated_tokens=50)

        assert fn.call_count == 3  # 1 initial + 2 retries

    def test_no_retry_on_non_retryable_error(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config(max_retries=3))
        fn = MagicMock(side_effect=ValueError("schema mismatch"))

        with patch("app.rate_limiter.time.sleep"):
            with pytest.raises(ValueError):
                limiter.execute_with_retry_sync(fn, estimated_tokens=50)

        assert fn.call_count == 1
        assert limiter.metrics["requests_retried"] == 0

    def test_retry_increments_retried_counter(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config(max_retries=3))
        fn = MagicMock(side_effect=[
            Exception("rate limit"),
            Exception("rate limit"),
            "done",
        ])

        with patch("app.rate_limiter.time.sleep"):
            limiter.execute_with_retry_sync(fn, estimated_tokens=10)

        assert limiter.metrics["requests_retried"] == 2


# ===========================================================================
# GeminiRateLimiter — execute_with_retry (async)
# ===========================================================================

class TestExecuteWithRetryAsync:
    async def test_success_first_try(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config())
        fn = MagicMock(return_value="async_ok")

        with patch("app.rate_limiter.asyncio.sleep"):
            with patch("app.rate_limiter.asyncio.to_thread", side_effect=lambda f: asyncio.coroutine(lambda: f())()):
                # Simpler: patch to_thread to call fn directly in-process
                pass

        # Use a real to_thread call (fast fn, no actual thread overhead)
        with patch("app.rate_limiter.asyncio.sleep"):
            result = await limiter.execute_with_retry(fn, estimated_tokens=50)

        assert result == "async_ok"
        assert fn.call_count == 1

    async def test_retries_on_503_async(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config(max_retries=2))
        fn = MagicMock(side_effect=[
            Exception("503 service unavailable"),
            "recovered",
        ])

        with patch("app.rate_limiter.asyncio.sleep"):
            result = await limiter.execute_with_retry(fn, estimated_tokens=50)

        assert result == "recovered"
        assert limiter.metrics["requests_retried"] == 1

    async def test_no_retry_on_non_retryable_async(self):
        limiter = GeminiRateLimiter.get_instance(_fast_config(max_retries=3))
        fn = MagicMock(side_effect=ValueError("bad schema"))

        with patch("app.rate_limiter.asyncio.sleep"):
            with pytest.raises(ValueError):
                await limiter.execute_with_retry(fn, estimated_tokens=50)

        assert fn.call_count == 1


# ===========================================================================
# RateLimitedAIProvider — integration smoke test
# ===========================================================================

class TestRateLimitedAIProvider:
    """Smoke tests that verify the decorator wires correctly without real Gemini calls."""

    def _make_provider(self):
        from app.ai_provider import AIProvider, RateLimitedAIProvider

        class FakeInner(AIProvider):
            def generate_response(self, prompt, *, system=None, temperature=0.4):
                return f"response:{prompt}"

            def generate_structured_response(self, prompt, schema, *, system=None):
                return {"ok": True}

            def generate_json(self, prompt, *, system=None):
                return {"json": True}

            def estimate_cost(self, input_tokens, output_tokens):
                return 0.0

        limiter = GeminiRateLimiter.get_instance(_fast_config())
        return RateLimitedAIProvider(FakeInner(), limiter)

    def test_generate_response_sync(self):
        provider = self._make_provider()
        with patch("app.rate_limiter.time.sleep"):
            result = provider.generate_response("hello")
        assert result == "response:hello"

    def test_generate_structured_response_sync(self):
        provider = self._make_provider()
        with patch("app.rate_limiter.time.sleep"):
            result = provider.generate_structured_response("p", {"type": "object"})
        assert result == {"ok": True}

    def test_generate_json_sync(self):
        provider = self._make_provider()
        with patch("app.rate_limiter.time.sleep"):
            result = provider.generate_json("p")
        assert result == {"json": True}

    def test_estimate_cost_delegates(self):
        provider = self._make_provider()
        assert provider.estimate_cost(1_000, 500) == 0.0

    async def test_agenerate_response_async(self):
        provider = self._make_provider()
        with patch("app.rate_limiter.asyncio.sleep"):
            result = await provider.agenerate_response("async hello")
        assert result == "response:async hello"

    async def test_agenerate_structured_response_async(self):
        provider = self._make_provider()
        with patch("app.rate_limiter.asyncio.sleep"):
            result = await provider.agenerate_structured_response("p", {"type": "object"})
        assert result == {"ok": True}

    def test_sync_metrics_update_on_calls(self):
        provider = self._make_provider()
        limiter = GeminiRateLimiter.get_instance()
        with patch("app.rate_limiter.time.sleep"):
            provider.generate_response("a")
            provider.generate_json("b")
        assert limiter.metrics["requests_total"] == 2
        assert limiter.metrics["tokens_consumed"] > 0

    def test_retry_on_429_in_provider(self):
        from app.ai_provider import AIProvider, RateLimitedAIProvider

        call_count = 0

        class FlakyInner(AIProvider):
            def generate_response(self, prompt, *, system=None, temperature=0.4):
                nonlocal call_count
                call_count += 1
                if call_count < 3:
                    raise Exception("HTTP 429: quota exceeded")
                return "ok after retry"

            def generate_structured_response(self, prompt, schema, *, system=None):
                return {}

            def generate_json(self, prompt, *, system=None):
                return {}

            def estimate_cost(self, i, o):
                return 0.0

        limiter = GeminiRateLimiter.get_instance(_fast_config(max_retries=5))
        provider = RateLimitedAIProvider(FlakyInner(), limiter)

        with patch("app.rate_limiter.time.sleep"):
            result = provider.generate_response("test retry")

        assert result == "ok after retry"
        assert call_count == 3
        assert limiter.metrics["requests_retried"] == 2
