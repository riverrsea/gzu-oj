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
