from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql://careerpilot:careerpilot@localhost:5432/careerpilot"
    gemini_api_key: str = ""
    ai_provider: str = "gemini"
    ai_model: str = "gemini-2.5-pro"
    log_level: str = "INFO"


settings = Settings()
