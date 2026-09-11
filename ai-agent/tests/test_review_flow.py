"""对抗审查 findings/ambiguities 的图级路由测试。"""

from uuid import uuid4

from langgraph.checkpoint.memory import InMemorySaver

from gzu_oj_agent.graph import Workflow
from gzu_oj_agent.models import (
    AnalysisResult,
    ArtifactResult,
    ReviewResult,
    SolutionResult,
    StartRunRequest,
    TestDesignResult as DesignResult,
)


class FakeModel:
    """按预设序列返回 review 结果，并记录 design 提示词。"""

    def __init__(self, reviews: list[ReviewResult]) -> None:
        self.reviews = reviews
        self.design_prompts: list[str] = []
        self.review_calls = 0

    async def generate(self, schema, system, prompt):
        if schema is AnalysisResult:
            return AnalysisResult(summary="s", constraints=[], ambiguities=[])
        if schema is SolutionResult:
            return SolutionResult(summary="s", source_code="int main(){}")
        if schema is DesignResult:
            self.design_prompts.append(prompt)
            return DesignResult(test_plan=["t"])
        if schema is ReviewResult:
            self.review_calls += 1
            return self.reviews[min(self.review_calls - 1, len(self.reviews) - 1)]
        if schema is ArtifactResult:
            return ArtifactResult(
                generator_source="int main(){}",
                validator_source="int main(){}",
                brute_force_source="int main(){}",
                seeds=[11, 12],
            )
        raise AssertionError(f"unexpected schema {schema}")


class FakeKotlin:
    def __init__(self) -> None:
        self.fails: list[tuple] = []

    async def progress(self, event):
        pass

    async def record_step(self, run_id, role, state, response, failure_reason=None, cost_microunits=0):
        pass

    async def fail(self, run_id, reason, resume_target=None):
        self.fails.append((run_id, reason, resume_target))

    async def submit_sandbox(self, run_id, task):
        return uuid4()

    async def cancel(self, run_id):
        pass

    async def close(self):
        pass


def request_dict(run_id) -> dict:
    return StartRunRequest(
        run_id=run_id,
        problem_version_id=uuid4(),
        statement_markdown="题目",
        test_case_count=2,
        time_limit_ms=1000,
        memory_limit_mib=256,
        repair_round=0,
    ).model_dump(mode="json")


async def test_findings_trigger_redesign_then_continue() -> None:
    """首轮 review 返回 findings（无歧义）→ 回退 design 重生成 → 次轮无 findings → 继续到 submit。"""
    model = FakeModel(
        [ReviewResult(findings=["缺少负数用例"], ambiguities=[]), ReviewResult(findings=[], ambiguities=[])]
    )
    graph = Workflow(model, FakeKotlin()).compile(InMemorySaver())
    run_id = uuid4()
    config = {"configurable": {"thread_id": str(run_id)}}

    await graph.ainvoke({"request": request_dict(run_id), "repair_round": 0}, config)
    snap = await graph.aget_state(config)
    assert snap.next == ("submit",)
    assert model.review_calls == 2
    assert len(model.design_prompts) == 2
    # 第二次测试设计必须带上上一轮 findings。
    assert "缺少负数用例" in model.design_prompts[1]


async def test_review_ambiguities_go_to_human_review() -> None:
    """review 的 ambiguities 阻断 → 进入人工接管（NEEDS_REVIEW）。"""
    model = FakeModel([ReviewResult(findings=[], ambiguities=["n 为偶数时中位数定义不明"])])
    kotlin = FakeKotlin()
    graph = Workflow(model, kotlin).compile(InMemorySaver())
    run_id = uuid4()
    config = {"configurable": {"thread_id": str(run_id)}}

    await graph.ainvoke({"request": request_dict(run_id), "repair_round": 0}, config)
    snap = await graph.aget_state(config)
    assert snap.next == ("fail",)
    assert snap.values.get("failure_target") == "REVIEWING"
    assert kotlin.fails[-1][2] == "REVIEWING"
