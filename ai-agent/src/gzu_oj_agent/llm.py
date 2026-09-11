"""OpenAI 兼容模型适配器。"""

import asyncio
import json
from typing import TypeVar

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from .config import Settings, StructuredOutputMode

T = TypeVar("T", bound=BaseModel)

# 部分 OpenAI 兼容网关（如 DashScope/Qwen）在 json_object 结构化输出下要求 messages 里
# 必须出现 "json"，否则直接返回 400；这里统一补一句，并抑制模型附加的 Markdown 围栏。
_JSON_OUTPUT_HINT = "只返回一个合法的 json 对象，不要包含解释或 Markdown 代码围栏。"


def _strip_outer_fence(content: str) -> str:
    """去掉模型整体输出外层的 Markdown 代码围栏，便于直接按 JSON 解析。"""
    text = content.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else ""
        text = text.rsplit("```", 1)[0]
    return text.strip()


class ModelClient:
    """统一处理并发、重试和三种结构化输出模式。"""

    def __init__(self, settings: Settings, model: ChatOpenAI | None = None) -> None:
        self.settings = settings
        self.model = model or ChatOpenAI(
            base_url=settings.llm_base_url,
            api_key=settings.llm_api_key,
            model=settings.llm_model,
            timeout=settings.llm_timeout_seconds,
            max_retries=0,
        )
        self._semaphore = asyncio.Semaphore(settings.llm_max_concurrent)

    async def healthcheck(self) -> None:
        """确认模型端点可用；不会触发 Ollama 拉取模型。"""
        await self.model.ainvoke([HumanMessage(content="Reply with OK.")])

    async def generate(self, schema: type[T], system: str, prompt: str) -> T:
        """调用模型并在返回边界执行 Pydantic 严格校验。"""
        last_error: Exception | None = None
        for attempt in range(self.settings.llm_max_retries + 1):
            try:
                async with self._semaphore:
                    return await self._generate_once(schema, system, prompt)
            except Exception as error:  # 兼容网关抛出的异常类型并不统一。
                last_error = error
                if attempt < self.settings.llm_max_retries:
                    await asyncio.sleep(min(2**attempt, 4))
        assert last_error is not None
        raise last_error

    async def _generate_once(self, schema: type[T], system: str, prompt: str) -> T:
        # 结构化输出统一附带一句提到 "json" 的指令，兼容要求该关键词的网关。
        messages = [
            SystemMessage(content=system),
            HumanMessage(content=prompt),
            HumanMessage(content=_JSON_OUTPUT_HINT),
        ]
        mode = self.settings.llm_structured_output_mode
        if mode is StructuredOutputMode.JSON_SCHEMA:
            runnable = self.model.with_structured_output(schema, method="json_schema", strict=True)
            result = await runnable.ainvoke(messages)
            return result if isinstance(result, schema) else schema.model_validate(result)
        if mode is StructuredOutputMode.JSON_OBJECT:
            result = await self.model.bind(response_format={"type": "json_object"}).ainvoke(messages)
            return schema.model_validate_json(_strip_outer_fence(str(result.content)))
        result = await self.model.ainvoke(
            messages
            + [
                HumanMessage(
                    content="只返回一个符合该 JSON Schema 的 JSON 对象："
                    + json.dumps(schema.model_json_schema(), ensure_ascii=False)
                )
            ]
        )
        return schema.model_validate_json(_strip_outer_fence(str(result.content)))
