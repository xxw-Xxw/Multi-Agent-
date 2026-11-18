import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


def load_env_file() -> None:
    """加载 agent-service/.env，已存在的环境变量优先，不会被覆盖。"""
    if not ENV_FILE.is_file():
        return
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and not os.getenv(key):
            os.environ[key] = value


@dataclass(frozen=True)
class Settings:
    provider: str
    base_url: str
    api_key: str | None
    model: str
    timeout_seconds: float

    @property
    def effective_provider(self) -> str:
        if self.provider == "openai" and self.api_key:
            return "openai"
        return "mock"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    load_env_file()
    provider = os.getenv("APP_LLM_PROVIDER", "mock").strip().lower()
    if provider not in {"mock", "openai"}:
        provider = "mock"
    api_key = os.getenv("APP_LLM_API_KEY", "").strip() or None
    try:
        timeout = float(os.getenv("APP_LLM_TIMEOUT_SECONDS", "180"))
    except ValueError:
        timeout = 180.0
    return Settings(
        provider=provider,
        base_url=os.getenv("APP_LLM_BASE_URL", "https://api.deepseek.com").rstrip("/"),
        api_key=api_key,
        model=os.getenv("APP_LLM_MODEL", "deepseek-chat"),
        timeout_seconds=max(timeout, 1.0),
    )
