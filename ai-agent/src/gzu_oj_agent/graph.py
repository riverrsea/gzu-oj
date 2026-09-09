"""生成测试点的固定 LangGraph 工作流。"""

import asyncio
import json
from typing import Any, Literal, TypedDict
from uuid import NAMESPACE_URL, UUID, uuid5

from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt

from .kotlin import KotlinClient
from .llm import ModelClient
from .models import (
    AnalysisResult,
    ArtifactResult,
    ProgressEvent,
    ReviewResult,
    SandboxResult,
    SandboxStatus,
    SandboxTask,
    SolutionResult,
    StartRunRequest,
    TestDesignResult,
)

SYSTEM_PROMPT = (
    "你是 OJ 题目工程 Agent。所有源码必须是完整 GNU C++17 程序并可复现；"
    "不得猜测标准输出，标准输出只能由 Kotlin 管理的 Worker 沙箱计算。发现题意歧义必须报告。"
)


def validate_checkpoint(model: type[Any], value: Any) -> Any:
    """按 JSON 语义恢复 UUID 和枚举，同时保持字段类型与额外字段严格校验。"""
    if isinstance(value, model):
        return value
    return model.model_validate_json(json.dumps(value, ensure_ascii=False))


class AgentState(TypedDict, total=False):
    """写入 PostgreSQL 检查点的完整运行状态。"""

    request: dict[str, Any]
    analysis: dict[str, Any]
    solution_a: dict[str, Any]
    solution_b: dict[str, Any]
    test_design: dict[str, Any]
    review: dict[str, Any]
    artifacts: dict[str, Any]
    sandbox_job_id: str
    sandbox_result: dict[str, Any]
    repair_round: int
    resume_attempt: int
    failure_reason: str
    failure_target: str
    resume: dict[str, Any]
    canceled: bool


def sandbox_payload(state: AgentState) -> SandboxTask:
    """只从 Pydantic 已校验结果构造 Worker 任务。"""
    request = validate_checkpoint(StartRunRequest, state["request"])
    solution_a = validate_checkpoint(SolutionResult, state["solution_a"])
    solution_b = validate_checkpoint(SolutionResult, state["solution_b"])
    artifacts = validate_checkpoint(ArtifactResult, state["artifacts"])
    if len(artifacts.seeds) != request.test_case_count:
        raise ValueError(f"生成器必须返回 {request.test_case_count} 个固定种子")
    return SandboxTask(
        repair_round=state.get("repair_round", request.repair_round),
        solution_a_source=solution_a.source_code,
        solution_b_source=solution_b.source_code,
        brute_force_source=artifacts.brute_force_source,
        generator_source=artifacts.generator_source,
        validator_source=artifacts.validator_source,
        seeds=artifacts.seeds,
        brute_force_case_count=min(3, len(artifacts.seeds)),
        time_limit_ms=request.time_limit_ms,
        memory_limit_mib=request.memory_limit_mib,
    )


class Workflow:
    """封装节点依赖，便于使用内存或 PostgreSQL checkpointer 测试。"""

    def __init__(self, model: ModelClient, kotlin: KotlinClient) -> None:
        self.model = model
        self.kotlin = kotlin

    async def _progress(self, state: AgentState, stage: str, status: str, message: str) -> None:
        request = validate_checkpoint(StartRunRequest, state["request"])
        repair_round = state.get("repair_round", request.repair_round)
        attempt = state.get("resume_attempt", 0)
        await self.kotlin.progress(
            ProgressEvent(
                event_id=uuid5(NAMESPACE_URL, f"{request.run_id}:{repair_round}:{attempt}:{stage}:{status}"),
                run_id=request.run_id,
                stage=stage,
                status=status,
                message=message,
                repair_round=repair_round,
            )
        )

    async def _step(
        self,
        state: AgentState,
        role: str,
        step_state: str,
        response: dict[str, Any],
        failure_reason: str | None = None,
    ) -> None:
        """回传单个角色的结构化响应供 Kotlin 审计与人工接管。"""
        request = validate_checkpoint(StartRunRequest, state["request"])
        await self.kotlin.record_step(
            request.run_id,
            role,
            step_state,
            response,
            failure_reason=failure_reason,
        )

    async def analyze(self, state: AgentState) -> AgentState:
        await self._progress(state, "ANALYZING", "RUNNING", "正在分析题意和约束")
        request = validate_checkpoint(StartRunRequest, state["request"])
        prompt = request.statement_markdown
        resume = state.get("resume", {})
        if resume.get("action") == "reanalyze":
            prompt += "\n\n人工澄清内容：" + json.dumps(resume.get("correction"), ensure_ascii=False)
        result = await self.model.generate(AnalysisResult, SYSTEM_PROMPT, prompt)
        response = result.model_dump()
        failure = result.ambiguities and "题意存在未解决歧义" or None
        await self._step(state, "analyze", "ANALYZING", response, failure_reason=failure)
        if result.ambiguities:
            return {"analysis": response, "failure_reason": failure, "failure_target": "ANALYZING"}
        return {"analysis": response, "failure_reason": "", "failure_target": ""}

    async def solutions(self, state: AgentState) -> AgentState:
        await self._progress(state, "GENERATING_SOLUTIONS", "RUNNING", "正在独立生成两份标程")
        request = validate_checkpoint(StartRunRequest, state["request"])
        context = str(state["analysis"])
        a, b = await asyncio.gather(
            self.model.generate(SolutionResult, SYSTEM_PROMPT, request.statement_markdown + "\n分析：" + context),
            self.model.generate(SolutionResult, SYSTEM_PROMPT, request.statement_markdown + "\n请独立求解，不参考另一候选。\n分析：" + context),
        )
        a_dump, b_dump = a.model_dump(), b.model_dump()
        await self._step(state, "solutions", "GENERATING_SOLUTIONS", {"solution_a": a_dump, "solution_b": b_dump})
        return {"solution_a": a_dump, "solution_b": b_dump}

    async def design(self, state: AgentState) -> AgentState:
        await self._progress(state, "TEST_DESIGN", "RUNNING", "正在设计边界和对抗测试")
        request = validate_checkpoint(StartRunRequest, state["request"])
        result = await self.model.generate(TestDesignResult, SYSTEM_PROMPT, request.statement_markdown + str(state["analysis"]))
        response = result.model_dump()
        await self._step(state, "design", "REVIEWING", response)
        return {"test_design": response}

    async def review(self, state: AgentState) -> AgentState:
        await self._progress(state, "ADVERSARIAL_REVIEW", "RUNNING", "正在审查两份标程和测试计划")
        prompt = str({key: state[key] for key in ("analysis", "solution_a", "solution_b", "test_design")})
        resume = state.get("resume", {})
        if resume.get("action") == "rereview":
            prompt += "\n\n人工澄清内容：" + json.dumps(resume.get("correction"), ensure_ascii=False)
        result = await self.model.generate(ReviewResult, SYSTEM_PROMPT, prompt)
        response = result.model_dump()
        failure = result.ambiguities and "对抗审查发现未解决歧义" or None
        await self._step(state, "review", "REVIEWING", response, failure_reason=failure)
        if result.ambiguities:
            return {"review": response, "failure_reason": failure, "failure_target": "REVIEWING"}
        return {"review": response, "failure_reason": "", "failure_target": ""}

    async def artifacts(self, state: AgentState) -> AgentState:
        await self._progress(state, "GENERATING_ARTIFACTS", "RUNNING", "正在生成确定性生成器、校验器和暴力解")
        request = validate_checkpoint(StartRunRequest, state["request"])
        repair = state.get("repair_round", request.repair_round)
        failure = state.get("failure_reason", "")
        prompt = (
            f"生成 {request.test_case_count} 个互异固定种子；前 3 个为适合暴力解的小数据。"
            f"当前修复轮次 {repair}，上轮沙箱失败原因：{failure}\n"
            + str({key: state[key] for key in ("analysis", "test_design", "review")})
        )
        result = await self.model.generate(ArtifactResult, SYSTEM_PROMPT, prompt)
        response = result.model_dump()
        await self._step(state, "artifacts", "GENERATING_TESTS", response)
        return {"artifacts": response, "failure_reason": ""}

    async def submit(self, state: AgentState) -> AgentState:
        request = validate_checkpoint(StartRunRequest, state["request"])
        job_id = await self.kotlin.submit_sandbox(request.run_id, sandbox_payload(state))
        await self._progress(state, "SANDBOX", "WAITING", "沙箱任务已提交，等待 Worker 结果")
        resumed = interrupt({"sandboxJobId": str(job_id), "repairRound": state.get("repair_round", 0)})
        result = validate_checkpoint(SandboxResult, resumed)
        return {"sandbox_job_id": str(job_id), "sandbox_result": result.model_dump(mode="json")}

    async def finish(self, state: AgentState) -> AgentState:
        await self._progress(state, "COMPLETED", "SUCCEEDED", "测试点和标准答案已通过沙箱验证")
        return {}

    async def fail(self, state: AgentState) -> AgentState:
        request = validate_checkpoint(StartRunRequest, state["request"])
        reason = state.get("failure_reason", "模型流程需要人工接管")
        target = state.get("failure_target")
        await self.kotlin.fail(request.run_id, reason, resume_target=target)
        await self._progress(state, "FAILED", "NEEDS_REVIEW", reason)
        # 人工接管：暂停并写检查点，等待管理员修复后通过 RESUME_RUN 恢复。
        # 注意：LangGraph 恢复中断时会从节点开头重跑，因此节点开头的 kotlin.fail 与进度
        # 事件对 Kotlin 是幂等的（重复写入相同值，进度事件按相同 event_id 被去重）。
        decision = interrupt({"reason": reason, "resumeTarget": target})
        # 只有实际恢复才会执行到这里；递增尝试序号使重跑进度事件避免与首轮去重冲突。
        return {"resume": decision, "resume_attempt": state.get("resume_attempt", 0) + 1}

    @staticmethod
    def after_analysis(state: AgentState) -> Literal["solutions", "fail"]:
        return "fail" if state.get("failure_reason") else "solutions"

    @staticmethod
    def after_review(state: AgentState) -> Literal["artifacts", "fail"]:
        return "fail" if state.get("failure_reason") else "artifacts"

    @staticmethod
    def after_sandbox(state: AgentState) -> Literal["finish", "repair", "fail"]:
        result = validate_checkpoint(SandboxResult, state["sandbox_result"])
        if result.status is SandboxStatus.PASSED:
            return "finish"
        if result.status is SandboxStatus.VALIDATION_FAILED and state.get("repair_round", 0) < 2:
            return "repair"
        return "fail"

    @staticmethod
    def after_fail(state: AgentState) -> Literal["analyze", "review", "finish"]:
        """根据人工恢复指令决定重新执行的节点；无恢复指令时结束。"""
        action = state.get("resume", {}).get("action")
        if action == "reanalyze":
            return "analyze"
        if action == "rereview":
            return "review"
        return "finish"

    @staticmethod
    def prepare_repair(state: AgentState) -> AgentState:
        result = validate_checkpoint(SandboxResult, state["sandbox_result"])
        return {
            "repair_round": state.get("repair_round", 0) + 1,
            "failure_reason": result.failure_reason or "沙箱差分失败",
        }

    def compile(self, checkpointer: Any):
        """构造固定拓扑；沙箱节点通过 interrupt 持久化暂停。"""
        graph = StateGraph(AgentState)
        graph.add_node("analyze", self.analyze)
        graph.add_node("solutions", self.solutions)
        graph.add_node("design", self.design)
        graph.add_node("review", self.review)
        graph.add_node("artifacts", self.artifacts)
        graph.add_node("submit", self.submit)
        graph.add_node("repair", self.prepare_repair)
        graph.add_node("finish", self.finish)
        graph.add_node("fail", self.fail)
        graph.add_edge(START, "analyze")
        graph.add_conditional_edges("analyze", self.after_analysis)
        graph.add_edge("solutions", "design")
        graph.add_edge("design", "review")
        graph.add_conditional_edges("review", self.after_review)
        graph.add_edge("artifacts", "submit")
        graph.add_conditional_edges("submit", self.after_sandbox)
        graph.add_edge("repair", "artifacts")
        graph.add_conditional_edges("fail", self.after_fail, {"analyze": "analyze", "review": "review", "finish": END})
        graph.add_edge("finish", END)
        return graph.compile(checkpointer=checkpointer)
