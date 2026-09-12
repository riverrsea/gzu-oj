"""Agent 环境配置。"""

from enum import StrEnum

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class StructuredOutputMode(StrEnum):
    """OpenAI 兼容服务的结构化输出能力。

    三者能力递减：json_schema 由网关强校验字段（仅 OpenAI 等少数服务支持），
    json_object 只保证是合法 JSON，prompt_json 连 response_format 都不发送。
    兼容网关普遍只实现后两者，因此默认取兼容性最好的 json_object。
    """

    JSON_SCHEMA = "json_schema"
    JSON_OBJECT = "json_object"
    PROMPT_JSON = "prompt_json"


class Settings(BaseSettings):
    """仅从环境变量或本地 `.env` 读取的运行配置。"""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    llm_base_url: str = "http://127.0.0.1:11434/v1"
    llm_api_key: str = "ollama"
    llm_model: str = "qwen2.5"
    llm_structured_output_mode: StructuredOutputMode = StructuredOutputMode.JSON_OBJECT
    llm_timeout_seconds: float = Field(default=120, gt=0)
    llm_max_retries: int = Field(default=2, ge=0, le=10)
    llm_max_concurrent: int = Field(default=1, ge=1, le=32)
    agent_database_url: str = "postgresql://riversea@127.0.0.1:5432/gzu_oj_agent"
    kotlin_api_base_url: str = "http://127.0.0.1:8080"
    agent_internal_token: str = Field(default="disabled-change-me", min_length=8)
    startup_model_check: bool = True
