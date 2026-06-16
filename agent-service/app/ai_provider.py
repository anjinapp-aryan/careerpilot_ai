"""AIProvider abstraction. Agents depend on this, never on Gemini directly."""
from __future__ import annotations

import json
import logging
from abc import ABC, abstractmethod
from typing import Any

import google.generativeai as genai
from tenacity import retry, stop_after_attempt, wait_exponential

from .config import settings

log = logging.getLogger(__name__)


class AIProvider(ABC):
    @abstractmethod
    def generate_response(self, prompt: str, *, system: str | None = None, temperature: float = 0.4) -> str: ...

    @abstractmethod
    def generate_structured_response(self, prompt: str, schema: dict, *, system: str | None = None) -> dict: ...

    @abstractmethod
    def generate_json(self, prompt: str, *, system: str | None = None) -> dict: ...

    @abstractmethod
    def estimate_cost(self, input_tokens: int, output_tokens: int) -> float: ...


class GeminiProvider(AIProvider):
    # Gemini 2.5 Pro pricing per 1M tokens (USD, indicative — refresh as needed).
    _PRICE_IN_PER_1M = 1.25
    _PRICE_OUT_PER_1M = 5.00

    def __init__(self, api_key: str, model: str):
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not configured")
        genai.configure(api_key=api_key)
        self._model_name = model

    def _model(self, system: str | None):
        return genai.GenerativeModel(self._model_name, system_instruction=system)

    @retry(stop=stop_after_attempt(3), wait=wait_exponential(min=1, max=8))
    def generate_response(self, prompt: str, *, system: str | None = None, temperature: float = 0.4) -> str:
        resp = self._model(system).generate_content(
            prompt,
            generation_config={"temperature": temperature},
        )
        return (resp.text or "").strip()

    @retry(stop=stop_after_attempt(3), wait=wait_exponential(min=1, max=8))
    def generate_structured_response(self, prompt: str, schema: dict, *, system: str | None = None) -> dict:
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
            log.warning("Non-JSON model output, returning empty")
            return {}

    def estimate_cost(self, input_tokens: int, output_tokens: int) -> float:
        return (input_tokens / 1_000_000) * self._PRICE_IN_PER_1M + (output_tokens / 1_000_000) * self._PRICE_OUT_PER_1M


_provider: AIProvider | None = None


def get_ai_provider() -> AIProvider:
    global _provider
    if _provider is not None:
        return _provider
    if settings.ai_provider == "gemini":
        _provider = GeminiProvider(settings.gemini_api_key, settings.ai_model)
    else:
        raise RuntimeError(f"Unsupported AI provider: {settings.ai_provider}")
    return _provider


def reset_provider() -> None:
    """Used in tests; not for production."""
    global _provider
    _provider = None
