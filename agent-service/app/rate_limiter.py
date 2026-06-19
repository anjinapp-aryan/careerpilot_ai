"""
Gemini API rate limiter — sits between LangGraph agents and GeminiProvider.

Architecture:
    LangGraph Agents
        → RateLimitedAIProvider   (decorator; transparent to agents)
            → GeminiRateLimiter   (singleton; enforces RPM + TPM + spacing + retry)
                → GeminiProvider  (raw SDK calls)
                    → Gemini API

Guarantees:
    - Thread-safe   (sync LangGraph nodes run in FastAPI's threadpool)
    - Async-safe    (async routes use asyncio.sleep, never time.sleep)
    - Singleton     (all 8 agents share one limiter instance)
    - Observable    (structured logs + Prometheus-ready counters)
"""
from __future__ import annotations

import asyncio
import logging
import math
import random
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, ClassVar, TypeVar

log = logging.getLogger(__name__)

T = TypeVar("T")


# ---------------------------------------------------------------------------
# Retryable error detection
# ---------------------------------------------------------------------------

def _is_retryable(exc: Exception) -> bool:
    """Return True for transient Gemini errors (429, 503) that should be retried."""
    try:
        from google.api_core.exceptions import ResourceExhausted, ServiceUnavailable  # type: ignore[import]
        if isinstance(exc, (ResourceExhausted, ServiceUnavailable)):
            return True
    except ImportError:
        pass
    msg = str(exc).lower()
    return any(token in msg for token in (
        "429", "quota", "rate limit", "resource exhausted",
        "503", "service unavailable", "overloaded",
    ))


def _jittered_backoff(attempt: int, base: float, cap: float) -> float:
    """
    Full-jitter exponential backoff (AWS recommended strategy).
    Returns uniform(0, min(cap, base * 2^attempt)).

    Attempt 0 → up to 2 s
    Attempt 1 → up to 4 s
    Attempt 2 → up to 8 s
    Attempt 3 → up to 16 s
    Attempt 4 → up to 32 s  (or cap)
    """
    ceiling = min(cap, base * math.pow(2.0, attempt))
    return random.uniform(0.0, ceiling)


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class RateLimiterConfig:
    """Immutable configuration for GeminiRateLimiter."""

    max_rpm: int = 10
    """Maximum requests per minute (RPM token-bucket capacity)."""

    max_tpm: int = 250_000
    """Maximum tokens per minute (TPM token-bucket capacity)."""

    min_request_interval: float = 2.0
    """Minimum wall-clock seconds between any two consecutive requests."""

    max_retries: int = 5
    """Max retry attempts after the first failure (total calls = max_retries + 1)."""

    base_retry_delay: float = 2.0
    """Base delay in seconds for the first retry backoff ceiling."""

    max_retry_delay: float = 60.0
    """Hard cap on backoff delay regardless of attempt number."""


# ---------------------------------------------------------------------------
# Metrics (Prometheus-ready counters)
# ---------------------------------------------------------------------------

@dataclass
class _Metrics:
    requests_total: int = 0
    requests_delayed: int = 0
    requests_retried: int = 0
    tokens_consumed: int = 0
    rate_limit_hits: int = 0

    def snapshot(self) -> dict[str, int]:
        return {
            "requests_total": self.requests_total,
            "requests_delayed": self.requests_delayed,
            "requests_retried": self.requests_retried,
            "tokens_consumed": self.tokens_consumed,
            "rate_limit_hits": self.rate_limit_hits,
        }


# ---------------------------------------------------------------------------
# Token Bucket
# ---------------------------------------------------------------------------

class TokenBucket:
    """
    Thread-safe token bucket.

    Uses threading.Lock so it is safely shared between the sync (LangGraph
    threadpool) and async (asyncio event-loop) callers at the same time.

    Operations:
        peek_wait(n)  — how long until n tokens are available (no side-effect)
        consume(n)    — deduct n tokens (call only after peek_wait returned 0.0)
        available     — current token count (for logging/debugging)
    """

    def __init__(self, rate_per_second: float, capacity: float) -> None:
        if rate_per_second <= 0 or capacity <= 0:
            raise ValueError("rate_per_second and capacity must be positive")
        self._rate = rate_per_second
        self._capacity = capacity
        self._tokens = capacity
        self._last_refill = time.monotonic()
        self._lock = threading.Lock()

    def _refill(self) -> None:
        """Top-up tokens based on elapsed time. Caller must hold self._lock."""
        now = time.monotonic()
        elapsed = now - self._last_refill
        self._tokens = min(self._capacity, self._tokens + elapsed * self._rate)
        self._last_refill = now

    def peek_wait(self, tokens: float) -> float:
        """Seconds to wait before `tokens` become available. Does NOT consume."""
        with self._lock:
            self._refill()
            if self._tokens >= tokens:
                return 0.0
            return (tokens - self._tokens) / self._rate

    def consume(self, tokens: float) -> None:
        """Deduct `tokens` (clips at 0 to handle floating-point drift)."""
        with self._lock:
            self._refill()
            self._tokens = max(0.0, self._tokens - tokens)

    @property
    def available(self) -> float:
        with self._lock:
            self._refill()
            return self._tokens


# ---------------------------------------------------------------------------
# GeminiRateLimiter — the core singleton
# ---------------------------------------------------------------------------

class GeminiRateLimiter:
    """
    Process-wide singleton that serialises all Gemini API calls across every
    LangGraph agent running in the service.

    Three independent throttles applied simultaneously:
        1. Wall-clock spacing  — min_request_interval seconds between requests
        2. RPM token bucket    — at most max_rpm requests per minute
        3. TPM token bucket    — at most max_tpm tokens per minute (estimated)

    Two acquire interfaces:
        acquire_sync(tokens)  — blocking; safe for sync LangGraph nodes
        acquire(tokens)       — suspends the coroutine; safe for async routes

    Retry helpers (wrap the actual API call):
        execute_with_retry_sync(fn, ...)
        execute_with_retry(fn, ...)
    """

    _instance: ClassVar[GeminiRateLimiter | None] = None
    _class_lock: ClassVar[threading.Lock] = threading.Lock()

    # ------------------------------------------------------------------
    # Construction / singleton
    # ------------------------------------------------------------------

    def __init__(self, config: RateLimiterConfig) -> None:
        self._config = config
        self._rpm_bucket = TokenBucket(
            rate_per_second=config.max_rpm / 60.0,
            capacity=float(config.max_rpm),
        )
        self._tpm_bucket = TokenBucket(
            rate_per_second=config.max_tpm / 60.0,
            capacity=float(config.max_tpm),
        )
        self._last_request_at: float = 0.0   # monotonic timestamp

        # Separate locks: threading for sync path, asyncio for async path.
        # Both guard _last_request_at and the two bucket consume() calls.
        self._sync_lock = threading.Lock()
        # asyncio.Lock is lazily created inside a running loop (Python 3.12+
        # allows creation outside a loop but lazy init is still safest).
        self._async_lock: asyncio.Lock | None = None

        self._metrics = _Metrics()

    @classmethod
    def get_instance(cls, config: RateLimiterConfig | None = None) -> "GeminiRateLimiter":
        """Return (or create) the process-wide singleton."""
        if cls._instance is None:
            with cls._class_lock:
                if cls._instance is None:
                    cls._instance = cls(config or RateLimiterConfig())
        return cls._instance

    @classmethod
    def reset(cls) -> None:
        """Destroy the singleton. For test isolation only — never call in production."""
        with cls._class_lock:
            cls._instance = None

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def config(self) -> RateLimiterConfig:
        return self._config

    @property
    def metrics(self) -> dict[str, int]:
        return self._metrics.snapshot()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _spacing_wait(self) -> float:
        elapsed = time.monotonic() - self._last_request_at
        return max(0.0, self._config.min_request_interval - elapsed)

    def _total_wait(self, tokens: int) -> float:
        return max(
            self._spacing_wait(),
            self._rpm_bucket.peek_wait(1.0),
            self._tpm_bucket.peek_wait(float(tokens)),
        )

    def _commit(self, tokens: int) -> None:
        """Consume from both buckets and update request timestamp. Call under a lock."""
        self._rpm_bucket.consume(1.0)
        self._tpm_bucket.consume(float(tokens))
        self._last_request_at = time.monotonic()
        self._metrics.tokens_consumed += tokens
        log.debug(
            "gemini_request_accepted",
            extra={"event": "gemini_request_accepted", "estimated_tokens": tokens},
        )

    def _get_async_lock(self) -> asyncio.Lock:
        if self._async_lock is None:
            self._async_lock = asyncio.Lock()
        return self._async_lock

    # ------------------------------------------------------------------
    # Async acquire (suspends coroutine; does NOT block a thread)
    # ------------------------------------------------------------------

    async def acquire(self, estimated_tokens: int = 100) -> None:
        """
        Wait until a Gemini API call is permitted, then consume quota.
        Uses asyncio.sleep — safe to call from async FastAPI handlers.
        """
        tokens = max(1, estimated_tokens)
        async with self._get_async_lock():
            self._metrics.requests_total += 1
            while True:
                wait = self._total_wait(tokens)
                if wait <= 0.0:
                    self._commit(tokens)
                    return
                self._metrics.requests_delayed += 1
                self._metrics.rate_limit_hits += 1
                log.info(
                    "gemini_request_delayed",
                    extra={
                        "event": "gemini_request_delayed",
                        "delay_seconds": round(wait, 3),
                        "estimated_tokens": tokens,
                        "rpm_available": round(self._rpm_bucket.available, 2),
                        "tpm_available": int(self._tpm_bucket.available),
                    },
                )
                await asyncio.sleep(wait)

    # ------------------------------------------------------------------
    # Sync acquire (blocks the calling thread; safe for LangGraph nodes)
    # ------------------------------------------------------------------

    def acquire_sync(self, estimated_tokens: int = 100) -> None:
        """
        Wait until a Gemini API call is permitted, then consume quota.
        Blocks the calling thread — safe in FastAPI's sync threadpool.
        """
        tokens = max(1, estimated_tokens)
        with self._sync_lock:
            self._metrics.requests_total += 1
            while True:
                wait = self._total_wait(tokens)
                if wait <= 0.0:
                    self._commit(tokens)
                    return
                self._metrics.requests_delayed += 1
                self._metrics.rate_limit_hits += 1
                log.info(
                    "gemini_request_delayed",
                    extra={
                        "event": "gemini_request_delayed",
                        "delay_seconds": round(wait, 3),
                        "estimated_tokens": tokens,
                        "rpm_available": round(self._rpm_bucket.available, 2),
                        "tpm_available": int(self._tpm_bucket.available),
                    },
                )
                time.sleep(wait)

    # ------------------------------------------------------------------
    # Retry wrapper — sync
    # ------------------------------------------------------------------

    def execute_with_retry_sync(
        self,
        fn: Callable[[], T],
        *,
        estimated_tokens: int = 100,
    ) -> T:
        """
        Execute fn() with rate limiting + retry (sync / blocking).

        Retry schedule (full-jitter, 5 retries):
            Attempt 1 → up to  2 s
            Attempt 2 → up to  4 s
            Attempt 3 → up to  8 s
            Attempt 4 → up to 16 s
            Attempt 5 → up to 32 s  (or max_retry_delay)

        Re-raises immediately on non-retryable errors or after exhausting retries.
        """
        cfg = self._config
        for attempt in range(cfg.max_retries + 1):
            self.acquire_sync(estimated_tokens)
            try:
                result = fn()
                log.debug(
                    "gemini_request_succeeded",
                    extra={"event": "gemini_request_succeeded", "attempt": attempt + 1},
                )
                return result
            except Exception as exc:
                retryable = _is_retryable(exc)
                is_last = attempt == cfg.max_retries

                if not retryable or is_last:
                    log.error(
                        "gemini_request_failed",
                        extra={
                            "event": "gemini_request_failed",
                            "attempt": attempt + 1,
                            "retryable": retryable,
                            "exhausted": is_last,
                            "error_type": type(exc).__name__,
                            "error": str(exc),
                        },
                    )
                    raise

                self._metrics.requests_retried += 1
                delay = _jittered_backoff(attempt, cfg.base_retry_delay, cfg.max_retry_delay)
                log.warning(
                    "gemini_retry",
                    extra={
                        "event": "gemini_retry",
                        "attempt": attempt + 1,
                        "max_retries": cfg.max_retries,
                        "delay_seconds": round(delay, 3),
                        "error_type": type(exc).__name__,
                        "error": str(exc),
                    },
                )
                time.sleep(delay)

        raise RuntimeError("execute_with_retry_sync: unreachable")  # pragma: no cover

    # ------------------------------------------------------------------
    # Retry wrapper — async
    # ------------------------------------------------------------------

    async def execute_with_retry(
        self,
        fn: Callable[[], Any],
        *,
        estimated_tokens: int = 100,
    ) -> Any:
        """
        Execute fn() (sync callable) with rate limiting + retry (async).

        fn is run via asyncio.to_thread so the event loop is never blocked.
        Uses asyncio.sleep for backoff delays.
        """
        cfg = self._config
        for attempt in range(cfg.max_retries + 1):
            await self.acquire(estimated_tokens)
            try:
                result = await asyncio.to_thread(fn)
                log.debug(
                    "gemini_request_succeeded",
                    extra={"event": "gemini_request_succeeded", "attempt": attempt + 1},
                )
                return result
            except Exception as exc:
                retryable = _is_retryable(exc)
                is_last = attempt == cfg.max_retries

                if not retryable or is_last:
                    log.error(
                        "gemini_request_failed",
                        extra={
                            "event": "gemini_request_failed",
                            "attempt": attempt + 1,
                            "retryable": retryable,
                            "exhausted": is_last,
                            "error_type": type(exc).__name__,
                            "error": str(exc),
                        },
                    )
                    raise

                self._metrics.requests_retried += 1
                delay = _jittered_backoff(attempt, cfg.base_retry_delay, cfg.max_retry_delay)
                log.warning(
                    "gemini_retry",
                    extra={
                        "event": "gemini_retry",
                        "attempt": attempt + 1,
                        "max_retries": cfg.max_retries,
                        "delay_seconds": round(delay, 3),
                        "error_type": type(exc).__name__,
                        "error": str(exc),
                    },
                )
                await asyncio.sleep(delay)

        raise RuntimeError("execute_with_retry: unreachable")  # pragma: no cover
