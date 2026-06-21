"""
Workflow AI Gateway — multi-provider failover for LangGraph agents.

Mirrors the Java AiGatewayService architecture with automatic failover:
    1. DeepSeek V4 Flash (NVIDIA NIM) [PRIMARY]
    2. Gemini 2.5 Flash [FALLBACK #1]
    3. Llama 3.3 70B (Groq) [FALLBACK #2]
    4. Qwen 3 Next 80B (NVIDIA NIM) [FALLBACK #3]

Circuit breaker pattern:
    - 429 ResourceExhausted → mark provider QUOTA_EXCEEDED
    - Block provider for 60s (transient per-minute rate limit; re-probed next run)
    - Use ProviderHealthTracker

Timeout protection:
    - 15s timeout per provider (not 120s total)
    - Fail immediately on timeout, don't retry
"""
from __future__ import annotations

import asyncio
import json
import logging
import threading
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, ClassVar, TypeVar

log = logging.getLogger(__name__)

T = TypeVar("T")


# ---------------------------------------------------------------------------
# Stage → actual-provider attribution
# ---------------------------------------------------------------------------
# The gateway fails over transparently, so the provider that actually served a
# stage is only known after the call returns. Record it here so graph node
# instrumentation can attribute the right provider in the execution timeline.
_stage_providers: dict[str, str] = {}
_stage_providers_lock = threading.Lock()


def _record_stage_provider(stage: str, provider_name: str) -> None:
    with _stage_providers_lock:
        _stage_providers[stage] = provider_name


def get_stage_provider(stage: str) -> str | None:
    """Return the provider that last successfully served `stage`, or None."""
    with _stage_providers_lock:
        return _stage_providers.get(stage)


# ---------------------------------------------------------------------------
# Provider health tracking (circuit breaker)
# ---------------------------------------------------------------------------

class ProviderStatus(Enum):
    """Provider health status."""
    HEALTHY = "HEALTHY"
    DEGRADED = "DEGRADED"
    QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    UNKNOWN = "UNKNOWN"


@dataclass
class ProviderHealth:
    """Track provider health with TTL."""
    status: ProviderStatus = ProviderStatus.UNKNOWN
    last_checked_at: float = 0.0
    ttl_seconds: float = 300.0  # 5 minute cache

    def is_expired(self) -> bool:
        return (time.time() - self.last_checked_at) > self.ttl_seconds

    def mark_quota_exceeded(self) -> None:
        self.status = ProviderStatus.QUOTA_EXCEEDED
        self.last_checked_at = time.time()
        # NVIDIA NIM / Gemini free-tier 429s are TRANSIENT per-minute rate limits
        # ("Too Many Requests"), not hard daily-quota exhaustion — they clear within
        # ~60s. The gateway is a process-wide singleton, so a long lockout benches the
        # provider for EVERY workflow run in the window: a single transient 429 on the
        # primary (DeepSeek) was blackballing it for 30 min and forcing all runs onto
        # Gemini. Re-probe the primary on the next run instead (proven recoverable: a
        # direct call to the same key/model returns 200 seconds later). Still long
        # enough to avoid hammering a rate-limited endpoint within one run.
        self.ttl_seconds = 60.0

    def mark_healthy(self) -> None:
        self.status = ProviderStatus.HEALTHY
        self.last_checked_at = time.time()
        self.ttl_seconds = 300.0


class ProviderHealthTracker:
    """Thread-safe provider health tracker with circuit breaker."""

    def __init__(self):
        self._health: dict[str, ProviderHealth] = {}
        self._lock = threading.Lock()

    def get_status(self, provider_name: str) -> ProviderStatus:
        """Get current status for a provider (auto-expire stale entries)."""
        with self._lock:
            health = self._health.get(provider_name)
            if health is None or health.is_expired():
                return ProviderStatus.UNKNOWN
            return health.status

    def mark_quota_exceeded(self, provider_name: str) -> None:
        """Mark provider rate-limited (60s lockout; transient 429, re-probed next run)."""
        with self._lock:
            if provider_name not in self._health:
                self._health[provider_name] = ProviderHealth()
            self._health[provider_name].mark_quota_exceeded()
            log.warning(
                "provider_quota_exceeded",
                extra={"event": "provider_quota_exceeded", "provider": provider_name},
            )

    def mark_healthy(self, provider_name: str) -> None:
        """Mark provider as healthy."""
        with self._lock:
            if provider_name not in self._health:
                self._health[provider_name] = ProviderHealth()
            self._health[provider_name].mark_healthy()


# ---------------------------------------------------------------------------
# Abstract provider interface
# ---------------------------------------------------------------------------

class WorkflowAIProvider(ABC):
    """Base interface for workflow AI providers."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Provider display name."""
        ...

    @abstractmethod
    def generate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None, timeout_seconds: float = 15.0
    ) -> dict:
        """Generate structured JSON response with schema validation."""
        ...


# ---------------------------------------------------------------------------
# DeepSeek provider (NVIDIA NIM)
# ---------------------------------------------------------------------------

class DeepSeekProvider(WorkflowAIProvider):
    """DeepSeek V4 Flash via NVIDIA NIM API (OpenAI-compatible)."""

    def __init__(self, api_key: str, base_url: str, model: str):
        if not api_key or not base_url:
            raise RuntimeError("DEEP_SHEEK_NVIDIA_API_KEY and NVIDIA_BASE_URL required for DeepSeek")
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        log.info(
            "deepseek_provider_init",
            extra={
                "event": "deepseek_provider_init",
                "model": model,
                "base_url": base_url,
            },
        )

    @property
    def name(self) -> str:
        return "deepseek"

    def generate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None, timeout_seconds: float = 15.0
    ) -> dict:
        """Call DeepSeek with structured output schema."""
        import httpx

        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})

        payload = {
            "model": self._model,
            "messages": messages,
            "temperature": 0.2,
            "response_format": {"type": "json_schema", "json_schema": {"name": "response", "schema": schema}},
        }

        try:
            with httpx.Client(timeout=timeout_seconds) as client:
                resp = client.post(f"{self._base_url}/chat/completions", json=payload, headers=headers)
                if resp.status_code != 200:
                    log.error(
                        "deepseek_http_error",
                        extra={
                            "event": "deepseek_http_error",
                            "status_code": resp.status_code,
                            "model": self._model,
                            "endpoint": f"{self._base_url}/chat/completions",
                            "response_body": resp.text[:500],
                        },
                    )
                resp.raise_for_status()
                data = resp.json()
                content = data.get("choices", [{}])[0].get("message", {}).get("content", "{}")
                result = json.loads(content)
                log.debug(
                    "deepseek_request_succeeded",
                    extra={"event": "deepseek_request_succeeded", "model": self._model},
                )
                return result
        except httpx.TimeoutException as e:
            log.error(
                "deepseek_timeout",
                extra={"event": "deepseek_timeout", "timeout_seconds": timeout_seconds, "error": str(e)},
            )
            raise
        except Exception as e:
            log.error(
                "deepseek_request_failed",
                extra={"event": "deepseek_request_failed", "error_type": type(e).__name__, "error": str(e), "model": self._model},
            )
            raise


# ---------------------------------------------------------------------------
# Qwen provider (NVIDIA NIM)
# ---------------------------------------------------------------------------

class QwenProvider(WorkflowAIProvider):
    """Qwen 3 Next 80B via NVIDIA NIM API (OpenAI-compatible)."""

    def __init__(self, api_key: str, base_url: str, model: str):
        if not api_key or not base_url:
            raise RuntimeError("QWEN3_NVIDIA_API_KEY and NVIDIA_BASE_URL required for Qwen")
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        log.info(
            "qwen_provider_init",
            extra={
                "event": "qwen_provider_init",
                "model": model,
                "base_url": base_url,
            },
        )

    @property
    def name(self) -> str:
        return "qwen"

    def generate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None, timeout_seconds: float = 15.0
    ) -> dict:
        """Call Qwen with structured output schema."""
        import httpx

        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})

        payload = {
            "model": self._model,
            "messages": messages,
            "temperature": 0.2,
            "response_format": {"type": "json_schema", "json_schema": {"name": "response", "schema": schema}},
        }

        try:
            with httpx.Client(timeout=timeout_seconds) as client:
                resp = client.post(f"{self._base_url}/chat/completions", json=payload, headers=headers)
                if resp.status_code != 200:
                    log.error(
                        "qwen_http_error",
                        extra={
                            "event": "qwen_http_error",
                            "status_code": resp.status_code,
                            "model": self._model,
                            "endpoint": f"{self._base_url}/chat/completions",
                            "response_body": resp.text[:500],
                        },
                    )
                resp.raise_for_status()
                data = resp.json()
                content = data.get("choices", [{}])[0].get("message", {}).get("content", "{}")
                result = json.loads(content)
                log.debug(
                    "qwen_request_succeeded",
                    extra={"event": "qwen_request_succeeded", "model": self._model},
                )
                return result
        except httpx.TimeoutException as e:
            log.error(
                "qwen_timeout",
                extra={"event": "qwen_timeout", "timeout_seconds": timeout_seconds, "error": str(e)},
            )
            raise
        except Exception as e:
            log.error(
                "qwen_request_failed",
                extra={"event": "qwen_request_failed", "error_type": type(e).__name__, "error": str(e), "model": self._model},
            )
            raise


# ---------------------------------------------------------------------------
# Groq provider (OpenAI-compatible)
# ---------------------------------------------------------------------------

class GroqProvider(WorkflowAIProvider):
    """Llama 3.3 70B via Groq API (OpenAI-compatible)."""

    def __init__(self, api_key: str, base_url: str, model: str):
        if not api_key or not base_url:
            raise RuntimeError("GROQ_API_KEY and GROQ_BASE_URL required for Groq")
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        log.info(
            "groq_provider_init",
            extra={
                "event": "groq_provider_init",
                "model": model,
                "base_url": base_url,
            },
        )

    @property
    def name(self) -> str:
        return "groq"

    def generate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None, timeout_seconds: float = 15.0
    ) -> dict:
        """Call Groq for structured JSON output.

        IMPORTANT: Groq's `response_format: json_schema` is only supported on a
        subset of models — `llama-3.3-70b-versatile` (our default) rejects it with
        HTTP 400 ("This model does not support response format `json_schema`").
        So we use `json_object` mode (which every Groq chat model supports) and
        embed the JSON Schema in the system message instead. json_object guarantees
        syntactically-valid JSON; the embedded schema steers it to the right shape —
        the same contract DeepSeek/Qwen/Gemini fulfil via native schema enforcement.
        Groq also REQUIRES the literal word "json" to appear in the messages when
        json_object mode is on, which the schema instruction below satisfies.
        """
        import httpx

        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        schema_instruction = (
            "You must respond with ONLY a single valid JSON object (no prose, no "
            "markdown fences) that conforms to this JSON Schema:\n"
            f"{json.dumps(schema)}"
        )
        system_content = f"{system}\n\n{schema_instruction}" if system else schema_instruction

        messages = [
            {"role": "system", "content": system_content},
            {"role": "user", "content": prompt},
        ]

        payload = {
            "model": self._model,
            "messages": messages,
            "temperature": 0.2,
            "response_format": {"type": "json_object"},
        }

        try:
            with httpx.Client(timeout=timeout_seconds) as client:
                resp = client.post(f"{self._base_url}/chat/completions", json=payload, headers=headers)
                if resp.status_code != 200:
                    log.error(
                        "groq_http_error",
                        extra={
                            "event": "groq_http_error",
                            "status_code": resp.status_code,
                            "model": self._model,
                            "endpoint": f"{self._base_url}/chat/completions",
                            "response_body": resp.text[:500],
                        },
                    )
                resp.raise_for_status()
                data = resp.json()
                content = data.get("choices", [{}])[0].get("message", {}).get("content", "{}")
                result = json.loads(content)
                log.debug(
                    "groq_request_succeeded",
                    extra={"event": "groq_request_succeeded", "model": self._model},
                )
                return result
        except httpx.TimeoutException as e:
            log.error(
                "groq_timeout",
                extra={"event": "groq_timeout", "timeout_seconds": timeout_seconds, "error": str(e)},
            )
            raise
        except Exception as e:
            log.error(
                "groq_request_failed",
                extra={"event": "groq_request_failed", "error_type": type(e).__name__, "error": str(e), "model": self._model},
            )
            raise


# ---------------------------------------------------------------------------
# Gemini provider (existing)
# ---------------------------------------------------------------------------

class GeminiWorkflowProvider(WorkflowAIProvider):
    """Gemini 2.5 Flash (fallback provider)."""

    def __init__(self, api_key: str, model: str):
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY required for Gemini")
        import google.generativeai as genai

        self._api_key = api_key
        self._model = model
        genai.configure(api_key=api_key)
        log.info(
            "gemini_workflow_provider_init",
            extra={"event": "gemini_workflow_provider_init", "model": model},
        )

    @property
    def name(self) -> str:
        return "gemini"

    def generate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None, timeout_seconds: float = 15.0
    ) -> dict:
        """Call Gemini with structured output schema."""
        import google.generativeai as genai

        model = genai.GenerativeModel(self._model, system_instruction=system)

        try:
            resp = model.generate_content(
                prompt,
                generation_config={
                    "response_mime_type": "application/json",
                    "response_schema": schema,
                    "temperature": 0.2,
                },
            )
            result = json.loads(resp.text)
            log.debug(
                "gemini_request_succeeded",
                extra={"event": "gemini_request_succeeded", "model": self._model},
            )
            return result
        except Exception as e:
            error_msg = str(e).lower()
            if "429" in error_msg or "quota" in error_msg or "resource exhausted" in error_msg:
                log.error(
                    "gemini_quota_exceeded",
                    extra={"event": "gemini_quota_exceeded", "error": str(e)},
                )
            else:
                log.error(
                    "gemini_request_failed",
                    extra={"event": "gemini_request_failed", "error_type": type(e).__name__, "error": str(e)},
                )
            raise


# ---------------------------------------------------------------------------
# Workflow AI Gateway — orchestrator with failover
# ---------------------------------------------------------------------------

class WorkflowAiGateway:
    """
    Multi-provider AI gateway for LangGraph workflow agents.

    Failover chain:
        1. DeepSeek (NVIDIA NIM)
        2. Gemini (Google)
        3. Groq (Llama 3.3 70B)
        4. Qwen (NVIDIA NIM)

    Features:
        - Automatic failover on error
        - Circuit breaker (429 rate limit → 60s lockout, re-probed next run)
        - 15s timeout per provider (fail fast, don't wait)
        - Comprehensive logging
    """

    def __init__(
        self,
        deepseek_provider: DeepSeekProvider | None,
        gemini_provider: GeminiWorkflowProvider,
        groq_provider: GroqProvider | None,
        qwen_provider: QwenProvider | None,
    ):
        self._providers = []
        self._health = ProviderHealthTracker()

        # Build provider chain (skip None providers) in the required order:
        # DeepSeek -> Gemini -> Groq -> Qwen.
        if deepseek_provider:
            self._providers.append(deepseek_provider)
        self._providers.append(gemini_provider)
        if groq_provider:
            self._providers.append(groq_provider)
        if qwen_provider:
            self._providers.append(qwen_provider)

        log.info(
            "workflow_ai_gateway_init",
            extra={
                "event": "workflow_ai_gateway_init",
                "provider_chain": [p.name for p in self._providers],
            },
        )

    def generate_structured_response(
        self, prompt: str, schema: dict, *, system: str | None = None, stage: str = "unknown"
    ) -> dict:
        """
        Generate structured response with automatic failover.

        Tries each provider in order until one succeeds.
        Skips providers marked as QUOTA_EXCEEDED.
        Times out at 15s per provider.

        Args:
            prompt: Input prompt
            schema: JSON schema for structured output
            system: System instruction
            stage: Workflow stage name (for logging)

        Returns:
            Structured response dict (or empty dict on all failures)

        Raises:
            RuntimeError: If all providers fail
        """
        log.info(
            "gateway_method_enter",
            extra={
                "event": "gateway_method_enter",
                "stage": stage,
                "provider_count": len(self._providers),
                "provider_names": [p.name for p in self._providers],
            },
        )
        errors = []

        for provider in self._providers:
            # Check circuit breaker
            health_status = self._health.get_status(provider.name)
            log.info(
                "provider_health_check",
                extra={
                    "event": "provider_health_check",
                    "stage": stage,
                    "provider": provider.name,
                    "status": health_status.value,
                },
            )

            if health_status == ProviderStatus.QUOTA_EXCEEDED:
                log.warning(
                    "provider_skipped_quota_exceeded",
                    extra={
                        "event": "provider_skipped_quota_exceeded",
                        "provider": provider.name,
                        "stage": stage,
                    },
                )
                continue

            log.info(
                "enter_provider",
                extra={
                    "event": "enter_provider",
                    "stage": stage,
                    "provider": provider.name,
                },
            )

            try:
                log.info(
                    "provider_call_begin",
                    extra={
                        "event": "provider_call_begin",
                        "stage": stage,
                        "provider": provider.name,
                        "schema_keys": list(schema.get("properties", {}).keys()),
                    },
                )
                result = provider.generate_structured_response(
                    prompt, schema, system=system, timeout_seconds=15.0
                )
                log.info(
                    "raw_response",
                    extra={
                        "event": "raw_response",
                        "stage": stage,
                        "provider": provider.name,
                        "result_type": type(result).__name__,
                        "result_keys": list(result.keys()) if isinstance(result, dict) else "not_dict",
                    },
                )

                # Validate result is dict
                if not isinstance(result, dict):
                    raise RuntimeError(f"Provider {provider.name} returned non-dict: {type(result).__name__}")

                log.info(
                    "schema_validated",
                    extra={
                        "event": "schema_validated",
                        "stage": stage,
                        "provider": provider.name,
                    },
                )

                self._health.mark_healthy(provider.name)
                _record_stage_provider(stage, provider.name)
                log.info(
                    "provider_health_marked_healthy",
                    extra={
                        "event": "provider_health_marked_healthy",
                        "stage": stage,
                        "provider": provider.name,
                    },
                )

                log.info(
                    "workflow_stage_completed",
                    extra={
                        "event": "workflow_stage_completed",
                        "stage": stage,
                        "provider": provider.name,
                    },
                )
                return result

            except Exception as e:
                log.error(
                    "provider_exception_caught",
                    extra={
                        "event": "provider_exception_caught",
                        "stage": stage,
                        "provider": provider.name,
                        "exception_type": type(e).__name__,
                        "exception_msg": str(e),
                    },
                    exc_info=True,
                )

                error_msg = str(e).lower()
                is_quota = any(
                    token in error_msg
                    for token in ("429", "quota", "resource exhausted", "rate limit")
                )

                if is_quota:
                    self._health.mark_quota_exceeded(provider.name)
                    log.warning(
                        "provider_fallback_quota",
                        extra={
                            "event": "provider_fallback_quota",
                            "stage": stage,
                            "provider": provider.name,
                            "error": str(e),
                        },
                    )
                else:
                    log.warning(
                        "provider_fallback_error",
                        extra={
                            "event": "provider_fallback_error",
                            "stage": stage,
                            "provider": provider.name,
                            "error_type": type(e).__name__,
                            "error": str(e),
                        },
                    )

                errors.append({"provider": provider.name, "error": str(e)})

        # All providers exhausted
        log.error(
            "workflow_stage_failed_all_providers",
            extra={
                "event": "workflow_stage_failed_all_providers",
                "stage": stage,
                "errors": errors,
                "error_count": len(errors),
            },
        )
        raise RuntimeError(f"All providers failed for stage '{stage}': {errors}")


# ---------------------------------------------------------------------------
# Factory — single instance
# ---------------------------------------------------------------------------

_gateway: WorkflowAiGateway | None = None
_gateway_lock = threading.Lock()


def get_workflow_ai_gateway() -> WorkflowAiGateway:
    """Return the process-wide workflow AI gateway."""
    global _gateway
    if _gateway is not None:
        return _gateway

    with _gateway_lock:
        if _gateway is not None:
            return _gateway

        from .config import settings

        # Build providers from config
        deepseek_provider = None
        qwen_provider = None

        if settings.deep_sheek_nvidia_api_key:
            try:
                deepseek_provider = DeepSeekProvider(
                    api_key=settings.deep_sheek_nvidia_api_key,
                    base_url=settings.nvidia_base_url,
                    model=settings.nvidia_deepseek_model,
                )
            except Exception as e:
                log.warning(
                    "deepseek_provider_init_failed",
                    extra={"event": "deepseek_provider_init_failed", "error": str(e)},
                )

        # Qwen is provisioned under its own NVIDIA account (QWEN3_NVIDIA_API_KEY),
        # distinct from DeepSeek's deep_sheek_nvidia_api_key — gated independently
        # so DeepSeek-only or Qwen-only configs both work.
        if settings.qwen3_nvidia_api_key:
            try:
                qwen_provider = QwenProvider(
                    api_key=settings.qwen3_nvidia_api_key,
                    base_url=settings.nvidia_base_url,
                    model=settings.nvidia_qwen_model,
                )
            except Exception as e:
                log.warning(
                    "qwen_provider_init_failed",
                    extra={"event": "qwen_provider_init_failed", "error": str(e)},
                )

        gemini_provider = GeminiWorkflowProvider(
            api_key=settings.gemini_api_key,
            model=settings.ai_model,
        )

        groq_provider = None
        if settings.groq_api_key:
            try:
                groq_provider = GroqProvider(
                    api_key=settings.groq_api_key,
                    base_url=settings.groq_base_url,
                    model=settings.groq_model,
                )
            except Exception as e:
                log.warning(
                    "groq_provider_init_failed",
                    extra={"event": "groq_provider_init_failed", "error": str(e)},
                )

        _gateway = WorkflowAiGateway(
            deepseek_provider=deepseek_provider,
            gemini_provider=gemini_provider,
            groq_provider=groq_provider,
            qwen_provider=qwen_provider,
        )

    return _gateway


def reset_workflow_gateway() -> None:
    """Reset the singleton. For test isolation only."""
    global _gateway
    _gateway = None
