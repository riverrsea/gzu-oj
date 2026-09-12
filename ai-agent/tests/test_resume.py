"""人工接管恢复与分阶段编译门禁链路测试。"""

import asyncio
from uuid import UUID, uuid4

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from gzu_oj_agent.graph import Workflow
from gzu_oj_agent.models import (
    AnalysisResult,
    SandboxResult,
    SandboxStatus,
    SolutionResult,
    StartRunRequest,
    TestDataResult as DataResult,
)


class FakeModel:
    """默认首次 analyze 回报歧义；ambigous=False 时直接给出无歧义分析。"""

    def __init__(self, ambiguous: bool = True) -> None:
        self.ambiguous = ambiguous
        self.analyze_prompts: list[str] = []
        self.data_prompts: list[str] = []

    async def generate(self, schema, system, prompt):
        if schema is AnalysisResult:
            self.analyze_prompts.append(prompt)
            if "人工澄清内容" in prompt or not self.ambiguous:
                return AnalysisResult(summary="已解决", constraints=["a"], ambiguities=[])
            return AnalysisResult(summary="原始分析", constraints=["c"], ambiguities=["题面歧义"])
        if schema is DataResult:
            self.data_prompts.append(prompt)
            return DataResult(generator_source="int main(){}", validator_source="int main(){}")
        if schema is SolutionResult:
            return SolutionResult(summary="s", source_code="int main(){}")
        raise AssertionError(f"unexpected schema {schema}")


class FakeKotlin:
    """按幂等键缓存任务标识，模拟 Kotlin 侧的唯一键语义。"""

    def __init__(self) -> None:
        self.fails: list[tuple] = []
        self.steps: list[tuple] = []
        self.compile_jobs: list[tuple] = []
        self.job_ids: dict[tuple, UUID] = {}

    async def progress(self, event):
        pass

    async def record_step(self, run_id, role, state, response, failure_reason=None, cost_microunits=0):
        self.steps.append((role, state, failure_reason))

    async def fail(self, run_id, reason, resume_target=None):
        self.fails.append((run_id, reason, resume_target))

    async def submit_sandbox(self, run_id, task):
        return self.job_ids.setdefault(("DIFFERENTIAL", task.repair_round), uuid4())

    async def submit_compile(self, run_id, task, repair_round, attempt):
        key = (task.stage.name, repair_round, attempt)
        self.compile_jobs.append(key)
        return self.job_ids.setdefault(key, uuid4())

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


def initial_state(run_id, analysis=None) -> dict:
    """构造已完成题意分析的图输入，便于直接验证编译门禁链路。"""
    return {
        "request": base_request(run_id).model_dump(mode="json"),
        "repair_round": 0,
        "analysis": analysis or {"summary": "已解决", "constraints": [], "ambiguities": []},
    }


def sandbox_result(run_id, job_id, status=SandboxStatus.PASSED, reason=None) -> dict:
    """构造与当前中断任务匹配的 Worker 结算通知。"""
    return SandboxResult(
        event_id=uuid4(),
        run_id=run_id,
        sandbox_job_id=UUID(str(job_id)),
        repair_round=0,
        status=status,
        failure_reason=reason,
    ).model_dump(mode="json")


async def pending_interrupt(graph, config) -> dict:
    """读取当前暂停节点的 interrupt 载荷。"""
    snapshot = await graph.aget_state(config)
    return snapshot.tasks[0].interrupts[0].value


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
    # 重跑 analyze 应携带人工澄清、清除歧义并继续到第一个编译门禁的瞬断点。
    assert snap2.next == ("compile_test_data",)
    assert "人工澄清内容" in model.analyze_prompts[1]
    # fail 节点恢复时会从开头重跑（幂等重复通知 Kotlin），首次通知必须携带 ANALYZING 目标。
    assert kotlin.fails[0][2] == "ANALYZING"
    analyze_steps = [s for s in kotlin.steps if s[0] == "analyze"]
    assert len(analyze_steps) == 2
    assert analyze_steps[0][2] == "题意存在未解决歧义"
    assert analyze_steps[1][2] is None


async def test_success_path_passes_three_compile_gates() -> None:
    """正常路径必须依次经过三个编译门禁，最后才提交全量差分任务。"""
    run_id = uuid4()
    kotlin = FakeKotlin()
    graph = Workflow(FakeModel(ambiguous=False), kotlin).compile(InMemorySaver())
    config = {"configurable": {"thread_id": str(run_id)}}

    await graph.ainvoke(initial_state(run_id), config)
    for expected in ("compile_test_data", "compile_solutions", "compile_brute_force"):
        snap = await graph.aget_state(config)
        assert snap.next == (expected,)
        payload = await pending_interrupt(graph, config)
        await graph.ainvoke(Command(resume=sandbox_result(run_id, payload["sandboxJobId"])), config)

    snap = await graph.aget_state(config)
    assert snap.next == ("submit",)
    # 三个门禁各一个任务键，且尝试序号都从 0 开始。
    # 节点恢复时会重跑一次并重复请求同一键，Kotlin 侧必须返回同一个任务标识。
    assert sorted(set(kotlin.compile_jobs)) == [
        ("BRUTE_FORCE", 0, 0),
        ("SOLUTIONS", 0, 0),
        ("TEST_DATA", 0, 0),
    ]
    assert len([key for key in kotlin.job_ids if key[0] != "DIFFERENTIAL"]) == 3

    payload = await pending_interrupt(graph, config)
    await graph.ainvoke(Command(resume=sandbox_result(run_id, payload["sandboxJobId"])), config)
    snap = await graph.aget_state(config)
    assert snap.next == ()
    assert snap.values.get("compile_stage") == "BRUTE_FORCE"


async def test_compile_retry_succeeds_on_second_attempt() -> None:
    """第一次编译失败后重新生成的产物编译通过，流程继续到下一个门禁。"""
    run_id = uuid4()
    model = FakeModel(ambiguous=False)
    kotlin = FakeKotlin()
    graph = Workflow(model, kotlin).compile(InMemorySaver())
    config = {"configurable": {"thread_id": str(run_id)}}

    await graph.ainvoke(initial_state(run_id), config)
    payload = await pending_interrupt(graph, config)
    await graph.ainvoke(
        Command(
            resume=sandbox_result(
                run_id,
                payload["sandboxJobId"],
                status=SandboxStatus.VALIDATION_FAILED,
                reason="输入校验器 编译失败：error",
            )
        ),
        config,
    )
    snap = await graph.aget_state(config)
    # 失败后回到 test_data 重新生成，而不是直接跳到下游；失败原因进入下一次生成提示词。
    assert snap.next == ("compile_test_data",)
    assert snap.values.get("test_data_attempts") == 1
    assert "输入校验器 编译失败" in model.data_prompts[-1]
    payload = await pending_interrupt(graph, config)
    assert payload["compileAttempt"] == 1
    await graph.ainvoke(Command(resume=sandbox_result(run_id, payload["sandboxJobId"])), config)
    snap = await graph.aget_state(config)
    assert snap.next == ("compile_solutions",)


async def test_compile_exhaustion_goes_to_manual_review() -> None:
    """编译门禁连续三次失败后进入人工接管，且不可由"重新分析"自动恢复。"""
    run_id = uuid4()
    kotlin = FakeKotlin()
    graph = Workflow(FakeModel(ambiguous=False), kotlin).compile(InMemorySaver())
    config = {"configurable": {"thread_id": str(run_id)}}

    await graph.ainvoke(initial_state(run_id), config)
    for expected_attempt in (0, 1, 2):
        snap = await graph.aget_state(config)
        assert snap.next == ("compile_test_data",)
        payload = await pending_interrupt(graph, config)
        assert payload["compileAttempt"] == expected_attempt
        await graph.ainvoke(
            Command(
                resume=sandbox_result(
                    run_id,
                    payload["sandboxJobId"],
                    status=SandboxStatus.VALIDATION_FAILED,
                    reason="测试生成器 编译失败：error: expected ';'",
                )
            ),
            config,
        )
    snap = await graph.aget_state(config)
    assert snap.next == ("fail",)
    assert snap.values.get("failure_target") == ""
    assert kotlin.fails[-1][1].startswith("测试生成器 编译失败")
    assert kotlin.fails[-1][2] == ""
    # 三次尝试使用三个不同的任务键，避免复用已结算的旧任务。
    assert sorted(set(kotlin.compile_jobs)) == [
        ("TEST_DATA", 0, 0),
        ("TEST_DATA", 0, 1),
        ("TEST_DATA", 0, 2),
    ]
    assert len(kotlin.job_ids) == 3


def test_compile_gate_node_is_coroutine() -> None:
    """编译门禁走 interrupt，必须是协程节点，否则 LangGraph 会同步阻塞线程。"""
    workflow = Workflow(FakeModel(), FakeKotlin())
    assert asyncio.iscoroutinefunction(workflow._compile_gate)
