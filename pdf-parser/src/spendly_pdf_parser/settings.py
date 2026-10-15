"""Service configuration from environment variables (names match .env.example).

When run locally with uv, the repository-root ``.env`` (``../.env`` relative to this project) is read
as a fallback source; real environment variables always win. Values are never logged.
"""

from __future__ import annotations

import os
import re
from datetime import timedelta
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# src/spendly_pdf_parser/settings.py -> parents[2] is the pdf-parser project folder (when run from source)
PROJECT_DIR = Path(__file__).resolve().parents[2]


def _env_files() -> tuple[Path, ...]:
    explicit = os.environ.get("SPENDLY_ENV_FILE")
    if explicit:
        return (Path(explicit),)
    return (PROJECT_DIR.parent / ".env",)


class ConfigurationError(RuntimeError):
    """Raised at startup when required configuration is missing. Messages never contain secret values."""


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=_env_files(),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
        populate_by_name=True,
    )

    kafka_bootstrap_servers: str = Field(default="localhost:19092", alias="KAFKA_BOOTSTRAP_SERVERS")
    spendly_upload_dir: Path = Field(default=Path("uploads"), alias="SPENDLY_UPLOAD_DIR")
    spendly_upload_retention: str = Field(default="PT0S", alias="SPENDLY_UPLOAD_RETENTION")
    spendly_upload_max_size_mb: int = Field(default=20, alias="SPENDLY_UPLOAD_MAX_SIZE_MB")
    spendly_hmac_secret_path: Path | None = Field(default=None, alias="SPENDLY_HMAC_SECRET_PATH")
    spendly_contracts_dir: Path | None = Field(default=None, alias="SPENDLY_CONTRACTS_DIR")
    pdf_parser_port: int = Field(default=8090, alias="PDF_PARSER_PORT")
    ollama_base_url: str = Field(default="http://localhost:11434", alias="OLLAMA_BASE_URL")
    log_level: str = Field(default="INFO", alias="LOG_LEVEL")
    # Not in .env.example: lets the service run the HTTP API without the Kafka worker (debugging only).
    kafka_enabled: bool = Field(default=True, alias="PDF_PARSER_KAFKA_ENABLED")

    @field_validator("spendly_hmac_secret_path", "spendly_contracts_dir", mode="before")
    @classmethod
    def _empty_is_none(cls, value: object) -> object:
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @field_validator("spendly_upload_retention")
    @classmethod
    def _valid_duration(cls, value: str) -> str:
        parse_iso_duration(value)
        return value

    @property
    def contracts_dir(self) -> Path:
        return self.spendly_contracts_dir or (PROJECT_DIR.parent / "contracts")

    @property
    def upload_retention(self) -> timedelta:
        return parse_iso_duration(self.spendly_upload_retention)

    @property
    def max_upload_bytes(self) -> int:
        return self.spendly_upload_max_size_mb * 1024 * 1024


_DURATION = re.compile(
    r"^P(?!$)(?:(?P<d>[0-9]+)D)?(?:T(?=[0-9])(?:(?P<h>[0-9]+)H)?(?:(?P<m>[0-9]+)M)?(?:(?P<s>[0-9]+(?:\.[0-9]+)?)S)?)?$"
)


def parse_iso_duration(value: str) -> timedelta:
    """Parse the ISO-8601 subset used for retention: PnDTnHnMnS (e.g. PT0S, PT10M, P1D)."""
    match = _DURATION.match(value.strip().upper())
    if not match:
        raise ValueError("SPENDLY_UPLOAD_RETENTION must be an ISO-8601 duration like PT0S, PT10M or P1D")
    return timedelta(
        days=int(match["d"] or 0),
        hours=int(match["h"] or 0),
        minutes=int(match["m"] or 0),
        seconds=float(match["s"] or 0),
    )


def load_hmac_key(path: Path | None) -> bytes:
    """Read the HMAC key file: trimmed UTF-8 content. Fails fast without ever revealing the value."""
    if path is None:
        raise ConfigurationError("SPENDLY_HMAC_SECRET_PATH is not set; the HMAC key file is required")
    if not path.is_file():
        raise ConfigurationError("The HMAC key file configured by SPENDLY_HMAC_SECRET_PATH does not exist or is not a file")
    try:
        key = path.read_text(encoding="utf-8").strip().encode("utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise ConfigurationError(
            f"The HMAC key file configured by SPENDLY_HMAC_SECRET_PATH cannot be read ({type(exc).__name__})"
        ) from None
    if not key:
        raise ConfigurationError("The HMAC key file configured by SPENDLY_HMAC_SECRET_PATH is empty")
    return key
