"""人工接管恢复链路测试：analyze 歧义 -> fail 暂停 -> 恢复后重新 analyze。"""

import asyncio
from uuid import uuid4

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from gzu_oj_agent.graph import Workflow
from gzu_oj_agent.models import (
    AnalysisResult,
    ArtifactResult,
    ReviewResult,
    SolutionResult,
    StartRunRequest,
)


class FakeModel:
    """首次 analyze 回报歧义，提示词带人工澄清时则成功。"""

    def __init__(self) -> None:
        self.analyze_prompts: list[str] = []

    async def generate(self, schema, system, prompt):
        if schema is AnalysisResult:
            self.analyze_prompts.append(prompt)
            if "人工澄清内容" in prompt:
                return AnalysisResult(summary="已解决", constraints=["a"], ambiguities=[])
            return AnalysisResult(summary="原始分析", constraints=["c"], ambiguities=["题面歧义"])
        if schema is SolutionResult:
            return SolutionResult(summary="s", source_code="int main(){}")
        if schema is ReviewResult:
            return ReviewResult(findings=["f"], ambiguities=[])
        if schema is ArtifactResult:
            return ArtifactResult(
                generator_source="int main(){}",
                validator_source="int main(){}",
                brute_force_source="int main(){}",
                seeds=[11, 12],
            )
        raise AssertionError(f"unexpected schema {schema}")


class FakeKotlin:
    """记录 fail 与 step 调用，便于断言。"""

    def __init__(self) -> None:
        self.fails: list[tuple] = []
        self.steps: list[tuple] = []

    async def progress(self, event):
        pass

    async def record_step(self, run_id, role, state, response, failure_reason=None, cost_microunits=0):
        self.steps.append((role, state, failure_reason))

    async def fail(self, run_id, reason, resume_target=None):
        self.fails.append((run_id, reason, resume_target))

    async def submit_sandbox(self, run_id, task):
        return uuid4()

    async def cancel(self, run_id):
        pass

    async def close(self):
        pass


def base_request(run_id):
    return StartRunRequest(
        run_id=run_id,
        problem_version_id=uuid4(),
        statement_markdown="题目",
        test_case_count=2,
        time_limit_ms=1000,
        memory_limit_mib=256,
        repair_round=0,
    )


async def test_analyze_ambiguity_fail_then_resume_reanalyze() -> None:
    run_id = uuid4()
    model = FakeModel()
    kotlin = FakeKotlin()
    graph = Workflow(model, kotlin).compile(InMemorySaver())
    config = {"configurable": {"thread_id": str(run_id)}}

    await graph.ainvoke(
        {"request": base_request(run_id).model_dump(mode="json"), "repair_round": 0},
        config,
    )
    snap = await graph.aget_state(config)
    assert snap.next == ("fail",)
    assert snap.values.get("failure_target") == "ANALYZING"
    assert kotlin.fails[-1][2] == "ANALYZING"
    assert len(model.analyze_prompts) == 1

    await graph.ainvoke(
        Command(resume={"action": "reanalyze", "correction": {"note": "已澄清"}}),
        config,
    )
    snap2 = await graph.aget_state(config)
    # 重跑 analyze 应携带人工澄清、清除歧义并继续到 submit 的瞬断点。
    assert snap2.next == ("submit",)
    assert "人工澄清内容" in model.analyze_prompts[1]
    # fail 节点恢复时会从开头重跑（幂等重复通知 Kotlin），首次通知必须携带 ANALYZING 目标。
    assert kotlin.fails[0][2] == "ANALYZING"
    analyze_steps = [s for s in kotlin.steps if s[0] == "analyze"]
    assert len(analyze_steps) == 2
    assert analyze_steps[0][2] == "题意存在未解决歧义"
    assert analyze_steps[1][2] is None
