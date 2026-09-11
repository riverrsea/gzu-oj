from types import SimpleNamespace

import pytest

from gzu_oj_agent.config import Settings, StructuredOutputMode
from gzu_oj_agent.llm import ModelClient
from gzu_oj_agent.models import AnalysisResult


class FlakyModel:
    def __init__(self) -> None:
        self.calls = 0

    async def ainvoke(self, _messages):
        self.calls += 1
        if self.calls == 1:
            raise RuntimeError("temporary")
        return SimpleNamespace(content='{"summary":"ok","constraints":[],"ambiguities":[]}')


@pytest.mark.asyncio
async def test_prompt_json_retries_then_validates() -> None:
    settings = Settings(
        agent_internal_token="test-token",
        llm_structured_output_mode=StructuredOutputMode.PROMPT_JSON,
        llm_max_retries=1,
    )
    model = FlakyModel()
    result = await ModelClient(settings, model=model).generate(AnalysisResult, "system", "prompt")
    assert result.summary == "ok"
    assert model.calls == 2


class RecordingJsonModel:
    """记录收到的消息，并返回被 Markdown 围栏包裹的 JSON。"""

    def __init__(self) -> None:
        self.messages = None

    def bind(self, **_kwargs):
        return self

    async def ainvoke(self, messages):
        self.messages = messages
        return SimpleNamespace(content='```json\n{"summary":"ok","constraints":[],"ambiguities":[]}\n```')


@pytest.mark.asyncio
async def test_json_object_mode_sends_json_hint_and_strips_fence() -> None:
    """json_object 模式必须让 messages 里出现 "json"（否则网关 400），并能剥离围栏。"""
    settings = Settings(
        agent_internal_token="test-token",
        llm_structured_output_mode=StructuredOutputMode.JSON_OBJECT,
    )
    model = RecordingJsonModel()
    result = await ModelClient(settings, model=model).generate(AnalysisResult, "system", "prompt")
    assert result.summary == "ok"
    assert model.messages is not None
    assert any("json" in str(message.content).lower() for message in model.messages)
