from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = Field(
        default="postgresql://careerpilot:careerpilot@localhost:5432/careerpilot",
        validation_alias="DATABASE_URL_PY",
    )
    gemini_api_key: str = ""
    ai_provider: str = "gemini"
    ai_model: str = "gemini-2.5-flash"  # free-tier eligible; pro = 429 limit:0 on free keys
    log_level: str = "INFO"

    # Comma-separated exact origins — no wildcards. Set to your Vercel URL(s).
    cors_allowed_origins: str = "http://localhost:5173"

    # ---------------------------------------------------------------------------
    # Rate limiter — maps to GEMINI_* environment variables
    # ---------------------------------------------------------------------------

    # Free-tier Google AI Studio defaults:
    #   RPM: 10 requests/minute for gemini-2.5-pro
    #   TPM: 250,000 tokens/minute
    gemini_max_rpm: int = 10
    gemini_max_tpm: int = 250_000

    # Minimum wall-clock gap between any two Gemini calls (seconds).
    # Prevents burst spikes even when the token buckets have capacity.
    gemini_min_request_interval: float = 2.0

    # Retry strategy for 429 / 503 responses.
    gemini_max_retries: int = 5
    gemini_base_retry_delay: float = 2.0   # ceiling for first retry (seconds)
    gemini_max_retry_delay: float = 60.0   # hard cap on any single backoff

    # ---------------------------------------------------------------------------
    # NVIDIA NIM API — DeepSeek and Qwen providers for workflow failover
    # ---------------------------------------------------------------------------

    # DeepSeek and Qwen are each provisioned under their OWN, dedicated NVIDIA
    # account/key — do not assume they share a key, even though both hit the
    # same NIM base URL.
    deep_sheek_nvidia_api_key: str = ""
    qwen3_nvidia_api_key: str = ""
    nvidia_base_url: str = "https://integrate.api.nvidia.com/v1"
    # NVIDIA NIM model IDs are namespaced by vendor. The wrong namespace
    # (e.g. "nvidia/deepseek-...") yields a 404 from /chat/completions, so these
    # defaults must match the catalog exactly. Overridable via NVIDIA_*_MODEL env.
    nvidia_deepseek_model: str = "deepseek-ai/deepseek-v4-flash"
    nvidia_qwen_model: str = "qwen/qwen3-next-80b-a3b-instruct"

    # ---------------------------------------------------------------------------
    # Groq API — OpenAI-compatible, used as the 3rd link in the workflow failover
    # chain (DeepSeek -> Gemini -> Groq -> Qwen). Empty key skips the provider,
    # matching the dedicated-key convention above.
    # ---------------------------------------------------------------------------

    groq_api_key: str = ""
    groq_base_url: str = "https://api.groq.com/openai/v1"
    groq_model: str = "llama-3.3-70b-versatile"


settings = Settings()
