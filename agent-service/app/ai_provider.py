"""
AIProvider abstraction + GeminiProvider + RateLimitedAIProvider decorator.

Dependency direction (agents never import Gemini directly):

    LangGraph Agents
        → get_ai_provider()           returns RateLimitedAIProvider
            → RateLimitedAIProvider   transparent decorator; adds rate limiting + retry
                → GeminiProvider      raw SDK calls; no retry logic here
                    → Gemini API

The decorator pattern means zero agent changes are needed to gain rate limiting.
"""
from __future__ import annotations

import asyncio
import json
import logging
import threading
from abc import ABC, abstractmethod

import google.generativeai as genai

from .config import settings
from .rate_limiter import GeminiRateLimiter, RateLimiterConfig

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Abstract base — agents program to this interface
# ---------------------------------------------------------------------------

class AIProvider(ABC):
    @abstractmethod
    def generate_response(
        self, prompt: str, *, system: str | None = None, temperature: float = 0.4
    ) -> str: ...

    @abstractmethod
    def generate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None
    ) -> dict: ...

    @abstractmethod
    def generate_json(self, prompt: str, *, system: str | None = None) -> dict: ...

    @abstractmethod
    def estimate_cost(self, input_tokens: int, output_tokens: int) -> float: ...


# ---------------------------------------------------------------------------
# Gemini implementation — raw SDK calls, no retry (handled by the decorator)
# ---------------------------------------------------------------------------

class GeminiProvider(AIProvider):
    """
    Thin wrapper around google-generativeai.
    Intentionally stateless and retry-free — retries live in RateLimitedAIProvider.
    """

    _PRICE_IN_PER_1M = 1.25   # USD per 1M input tokens (Gemini 2.5 Pro)
    _PRICE_OUT_PER_1M = 5.00  # USD per 1M output tokens

    def __init__(self, api_key: str, model: str) -> None:
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not configured")
        genai.configure(api_key=api_key)
        self._model_name = model

    def _model(self, system: str | None) -> genai.GenerativeModel:
        return genai.GenerativeModel(self._model_name, system_instruction=system)

    def generate_response(
        self, prompt: str, *, system: str | None = None, temperature: float = 0.4
    ) -> str:
        resp = self._model(system).generate_content(
            prompt,
            generation_config={"temperature": temperature},
        )
        return (resp.text or "").strip()

    def generate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None
    ) -> dict:
        resp = self._model(system).generate_content(
            prompt,
            generation_config={
                "response_mime_type": "application/json",
                "response_schema": schema,
                "temperature": 0.2,
            },
        )
        return json.loads(resp.text)

    def generate_json(self, prompt: str, *, system: str | None = None) -> dict:
        resp = self._model(system).generate_content(
            prompt,
            generation_config={"response_mime_type": "application/json", "temperature": 0.2},
        )
        try:
            return json.loads(resp.text)
        except json.JSONDecodeError:
            log.warning("gemini_non_json_output", extra={"event": "gemini_non_json_output"})
            return {}

    def estimate_cost(self, input_tokens: int, output_tokens: int) -> float:
        return (
            (input_tokens / 1_000_000) * self._PRICE_IN_PER_1M
            + (output_tokens / 1_000_000) * self._PRICE_OUT_PER_1M
        )


# ---------------------------------------------------------------------------
# RateLimitedAIProvider — decorator (wraps any AIProvider)
# ---------------------------------------------------------------------------

class RateLimitedAIProvider(AIProvider):
    """
    Transparent decorator that adds rate limiting, token-bucket enforcement,
    and exponential-backoff retry to any AIProvider.

    Sync methods (used by existing LangGraph nodes — zero code changes required):
        generate_response(...)
        generate_structured_response(...)
        generate_json(...)

    Async methods (for new async FastAPI routes or future async agents):
        agenerate_response(...)
        agenerate_structured_response(...)
        agenerate_json(...)

    The async path uses asyncio.to_thread() so the sync Gemini SDK never blocks
    the FastAPI event loop.

    Supports future providers (OpenAI, Claude, Azure) by accepting any AIProvider.
    """

    def __init__(
        self,
        inner: AIProvider,
        limiter: GeminiRateLimiter | None = None,
    ) -> None:
        self._inner = inner
        self._limiter = limiter or GeminiRateLimiter.get_instance()

    @staticmethod
    def _estimate_tokens(prompt: str) -> int:
        """
        Rough pre-call token estimate (~4 chars/token, 100-token minimum).
        Used to reserve TPM budget before the request is sent.
        """
        return max(100, len(prompt) // 4)

    # ------------------------------------------------------------------
    # Sync interface — backward compatible with all 8 existing agents
    # ------------------------------------------------------------------

    def generate_response(
        self, prompt: str, *, system: str | None = None, temperature: float = 0.4
    ) -> str:
        return self._limiter.execute_with_retry_sync(
            lambda: self._inner.generate_response(prompt, system=system, temperature=temperature),
            estimated_tokens=self._estimate_tokens(prompt),
        )

    def generate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None
    ) -> dict:
        return self._limiter.execute_with_retry_sync(
            lambda: self._inner.generate_structured_response(prompt, schema, system=system),
            estimated_tokens=self._estimate_tokens(prompt),
        )

    def generate_json(self, prompt: str, *, system: str | None = None) -> dict:
        return self._limiter.execute_with_retry_sync(
            lambda: self._inner.generate_json(prompt, system=system),
            estimated_tokens=self._estimate_tokens(prompt),
        )

    def estimate_cost(self, input_tokens: int, output_tokens: int) -> float:
        return self._inner.estimate_cost(input_tokens, output_tokens)

    # ------------------------------------------------------------------
    # Async interface — for future async routes / agents
    # ------------------------------------------------------------------

    async def agenerate_response(
        self, prompt: str, *, system: str | None = None, temperature: float = 0.4
    ) -> str:
        """Non-blocking async version of generate_response."""
        return await self._limiter.execute_with_retry(
            lambda: self._inner.generate_response(prompt, system=system, temperature=temperature),
            estimated_tokens=self._estimate_tokens(prompt),
        )

    async def agenerate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None
    ) -> dict:
        """Non-blocking async version of generate_structured_response."""
        return await self._limiter.execute_with_retry(
            lambda: self._inner.generate_structured_response(prompt, schema, system=system),
            estimated_tokens=self._estimate_tokens(prompt),
        )

    async def agenerate_json(self, prompt: str, *, system: str | None = None) -> dict:
        """Non-blocking async version of generate_json."""
        return await self._limiter.execute_with_retry(
            lambda: self._inner.generate_json(prompt, system=system),
            estimated_tokens=self._estimate_tokens(prompt),
        )


# ---------------------------------------------------------------------------
# Factory — always returns a RateLimitedAIProvider
# ---------------------------------------------------------------------------

_provider: RateLimitedAIProvider | None = None
_provider_lock = threading.Lock()


def get_ai_provider() -> RateLimitedAIProvider:
    """
    Return the process-wide rate-limited AI provider (double-checked locking).

    All 8 LangGraph agents call this; they receive a RateLimitedAIProvider
    that is transparent behind the AIProvider interface.
    """
    global _provider
    if _provider is not None:
        return _provider
    with _provider_lock:
        if _provider is not None:
            return _provider

        if settings.ai_provider == "gemini":
            inner: AIProvider = GeminiProvider(settings.gemini_api_key, settings.ai_model)
        else:
            raise RuntimeError(f"Unsupported AI provider: {settings.ai_provider}")

        limiter_cfg = RateLimiterConfig(
            max_rpm=settings.gemini_max_rpm,
            max_tpm=settings.gemini_max_tpm,
            min_request_interval=settings.gemini_min_request_interval,
            max_retries=settings.gemini_max_retries,
            base_retry_delay=settings.gemini_base_retry_delay,
            max_retry_delay=settings.gemini_max_retry_delay,
        )
        limiter = GeminiRateLimiter.get_instance(limiter_cfg)
        _provider = RateLimitedAIProvider(inner, limiter)

        log.info(
            "ai_provider_initialized",
            extra={
                "event": "ai_provider_initialized",
                "provider": settings.ai_provider,
                "model": settings.ai_model,
                "max_rpm": settings.gemini_max_rpm,
                "max_tpm": settings.gemini_max_tpm,
                "min_request_interval": settings.gemini_min_request_interval,
                "max_retries": settings.gemini_max_retries,
            },
        )
    return _provider


def reset_provider() -> None:
    """Reset the singleton. For test isolation only — never call in production."""
    global _provider
    _provider = None
    GeminiRateLimiter.reset()
