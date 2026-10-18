import os
from functools import lru_cache
from pathlib import Path
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

# Locate backend root directory and .env file location
BACKEND_DIR = Path(__file__).resolve().parent.parent
DEFAULT_ENV_FILE = BACKEND_DIR / ".env"


class Settings(BaseSettings):
    """Application configuration loaded from environment variables."""

    GEMINI_API_KEY: str = Field(
        default="your_api_key_here",
        description="Google Gemini/Gemma API key",
    )
    TAPTRAIL_AUTH_TOKEN: str = Field(
        default="dev_token_change_me",
        description="Bearer token for API authorization",
    )
    HOST: str = Field(
        default="0.0.0.0",
        description="Host address to bind the server",
    )
    PORT: int = Field(
        default=8000,
        description="Port to bind the server (default 8000)",
    )
    MODEL_NAME: str = Field(
        default="gemma-4-26b-a4b-it",
        description="Gemma model name to use for screen analysis",
    )
    MAX_IMAGE_SIZE_BYTES: int = Field(
        default=5 * 1024 * 1024,
        description="Maximum allowed image size in bytes (default 5MB)",
    )
    MAX_IMAGE_DIMENSION: int = Field(
        default=4096,
        description="Maximum allowed image width or height in pixels",
    )
    REQUEST_TIMEOUT_SECONDS: int = Field(
        default=30,
        description="Request processing timeout in seconds",
    )
    RATE_LIMIT_PER_MINUTE: int = Field(
        default=10,
        description="Maximum requests permitted per minute per IP",
    )

    model_config = SettingsConfigDict(
        env_file=(str(DEFAULT_ENV_FILE), ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    """Return cached application settings instance."""
    return Settings()
