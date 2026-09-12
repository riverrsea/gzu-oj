from types import SimpleNamespace

import pytest

from gzu_oj_agent.config import Settings, StructuredOutputMode
from gzu_oj_agent.llm import ModelClient
from gzu_oj_agent.models import AnalysisResult, SolutionResult


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


class RecordingSchemaModel:
    """记录 with_structured_output 收到的消息。"""

    def __init__(self) -> None:
        self.messages = None

    def with_structured_output(self, schema, **_kwargs):
        outer = self

        class _Runnable:
            async def ainvoke(self, messages):
                outer.messages = messages
                return schema(summary="ok", constraints=[], ambiguities=[])

        return _Runnable()


@pytest.mark.asyncio
async def test_json_schema_mode_also_sends_json_hint() -> None:
    """部分网关会把 json_schema 降级成 json_object，因此 json_schema 模式同样要带 json 关键词。"""
    settings = Settings(
        agent_internal_token="test-token",
        llm_structured_output_mode=StructuredOutputMode.JSON_SCHEMA,
    )
    model = RecordingSchemaModel()
    result = await ModelClient(settings, model=model).generate(AnalysisResult, "system", "prompt")
    assert result.summary == "ok"
    assert model.messages is not None
    assert any("json" in str(message.content).lower() for message in model.messages)


class LinkThenCodeModel:
    """第一次用链接代替源码，第二次才给出合法源码。"""

    def __init__(self) -> None:
        self.calls: list[list] = []

    def bind(self, **_kwargs):
        return self

    async def ainvoke(self, messages):
        self.calls.append(list(messages))
        if len(self.calls) == 1:
            return SimpleNamespace(content='{"summary":"s","source_code":"https://example.com/a.cpp"}')
        return SimpleNamespace(content='{"summary":"s","source_code":"int main(){return 0;}"}')


@pytest.mark.asyncio
async def test_rejected_source_reason_is_fed_back_on_retry() -> None:
    """返回链接代替源码时必须重试，并把被拒绝的原因回喂给模型。"""
    settings = Settings(
        agent_internal_token="test-token",
        llm_structured_output_mode=StructuredOutputMode.JSON_OBJECT,
        llm_max_retries=1,
    )
    model = LinkThenCodeModel()
    result = await ModelClient(settings, model=model).generate(SolutionResult, "system", "prompt")

    assert result.source_code == "int main(){return 0;}"
    assert len(model.calls) == 2
    feedback = " ".join(str(message.content) for message in model.calls[1])
    assert "被拒绝的原因" in feedback
    assert "链接" in feedback
