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

# 通用守则：绝不猜测标准输出、输入与源码必须确定性可复现。
_NEVER_GUESS_OUTPUT = "不得猜测标准输出：标准输出只能由后续 Worker 沙箱计算。"
_DETERMINISTIC_SOURCE = "所有源码必须是完整、可编译且确定性可复现的 GNU C++17 程序。"

# 题意分析角色：拆解题面为"约束"与"歧义"两类，并用 one-shot 示例校准判别。
SYSTEM_ANALYZE = """你是 OJ 题目的题意分析者。把题面拆解为 约束（constraints）与 歧义（ambiguities）两类，供后续标程与测试设计使用。

判别规则：
- 约束：题面明确写出的、可直接用于实现的事实，例如 数据规模、取值类型与范围、输入/输出格式、题目保证的性质。
- 歧义：题面未明确、但会直接影响解法或输出、且不同理解会导致不同结果的点。拿不准就列为歧义，不要自行假设。

常见错误（务必避免）：
- 把题面已写明的点误判为歧义（例如题面写了"输出和"，就不要把"是否输出和"当成歧义）。
- 把你自己引入的策略或实现细节（如"要开 long long""按字典序排序"）当成约束。
- 把不影响解法的排版权宜（如空行、换行）当成歧义。

示例：
题目：输入一个非空字符串 s（1≤|s|≤10^5），由小写字母组成。输出把每个字母替换为它在字母表中的序号（如 a→1）后得到的数字串。
正确输出：
summary：把每个小写字母替换为 1-indexed 的字母序号（a=1,…,z=26）并拼接成数字串。
constraints：["s 非空，长度 1 到 10^5", "s 仅含小写字母 a-z"]
ambiguities：["字母序号是 1-indexed（a=1）还是 0-indexed（a=0）？示例 a→1 暗示前者，但题面未明说", "两位数序号（如 z→26）是否需要分隔符或固定宽度？题面称“数字串”但未给边界示例，26 可能被读成 2 和 6"]

输出：严格按 AnalysisResult 的 summary/constraints/ambiguities 返回。summary 用一句话概括题意；constraints 只列题面明确事实；ambiguities 只列影响解法、需要人工定夺的点，若没有歧义，ambiguities应该置空"""

# 标程生成角色：输出一份正确、高效、可读的标准解法。
SYSTEM_SOLUTIONS = (
	f"你是 OJ 题目的标程编写者。{_DETERMINISTIC_SOURCE} 输出一份正确、高效、可读且不依赖未定义行为的标准解法。"
)

# 测试设计角色：围绕边界、极端与对抗反例设计测试计划。
SYSTEM_DESIGN = (
	f"你是 OJ 题目的测试设计者。围绕边界值、极端规模与对抗反例设计测试计划，{_NEVER_GUESS_OUTPUT}"
)

# 对抗审查角色：交叉核对分析、标程与测试计划。
SYSTEM_REVIEW = """你是 OJ 题目的对抗审查者。交叉核对题意分析、两份标程与测试计划，按下面两类分别返回：

- findings（记录性，不阻断）：可通过重新设计测试计划或生成数据规避的问题。例如：两份标程之间、或标程与题意分析/测试计划相矛盾；某份标程边界处理不一致；测试计划遗漏某类极端数据；最大规模下可能超时。
- ambiguities（阻断性）：题面确实缺失、必须由人工定夺、否则会导致测试数据错误的点。只要无法确定“标准答案到底是什么”，就放进这里；非空即进入人工接管。

判别要点：能靠“重新设计测试计划或生成数据”解决的，算 findings；需要改题意或由人拍板的，算 ambiguities。同一条问题只写一次，不要两类都写；不要因为实现风格把非阻断问题塞进 ambiguities。

示例（one-shot）：
题目：输入 n 个整数，输出其中位数（1≤n≤10^5）。
分析/两份标程/测试计划：两份标程都排序后取中间元素，但都未明确 n 为偶数时的取法；测试计划只覆盖奇数 n。
应输出：
findings：["测试计划只覆盖奇数 n，缺少偶数 n 的用例", "两份标程都未明确 n 为偶数时取哪个中间元素"]
ambiguities：["n 为偶数时中位数取哪个值（较小中间值 / 较大中间值 / 两者平均）题面未说明，不同取法结果不同"]

输出：严格按 ReviewResult 的 findings/ambiguities 返回；没有就返回空列表。"""

# 测试数据生成角色：可复现生成器、校验器与暴力解。
SYSTEM_ARTIFACTS = (
	f"你是 OJ 题目的测试数据生成者。{_DETERMINISTIC_SOURCE} 生成确定性的输入生成器、输入校验器与小数据暴力解，"
	"并给出互异固定种子，种子数量必须与题面要求一致。"
)


def validate_checkpoint(model: type[Any], value: Any) -> Any:
	"""按 JSON 语义恢复 UUID 和枚举，同时保持字段类型与额外字段严格校验。"""
	if isinstance(value, model):
		return value
	return model.model_validate_json(json.dumps(value, ensure_ascii=False))


# 对抗审查发现非阻断问题时，最多回退重新设计测试计划的次数。
REVIEW_REDESIGN_LIMIT = 2


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
	design_round: int
	redesign_requested: bool
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

	async def _progress(
		self,
		state: AgentState,
		stage: str,
		status: str,
		message: str
	) -> None:
		request = validate_checkpoint(StartRunRequest, state["request"])
		repair_round = state.get("repair_round", request.repair_round)
		attempt = state.get("resume_attempt", 0)
		await self.kotlin.progress(
			ProgressEvent(
				event_id=uuid5(
					NAMESPACE_URL,
					f"{request.run_id}:{repair_round}:{attempt}:{stage}:{status}"
				),
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
		await self._progress(
			state,
			"ANALYZING",
			"RUNNING",
			"正在分析题意和约束"
		)
		request = validate_checkpoint(StartRunRequest, state["request"])
		prompt = request.statement_markdown
		resume = state.get("resume", {})
		if resume.get("action") == "reanalyze":
			prompt += "\n\n人工澄清内容：" + json.dumps(
				resume.get("correction"),
				ensure_ascii=False
			)
		result = await self.model.generate(
			AnalysisResult,
			SYSTEM_ANALYZE,
			prompt
		)
		response = result.model_dump()
		failure = result.ambiguities and "题意存在未解决歧义" or None
		await self._step(
			state,
			"analyze",
			"ANALYZING",
			response,
			failure_reason=failure
		)
		if result.ambiguities:
			return {
				"analysis": response,
				"failure_reason": failure,
				"failure_target": "ANALYZING"
			}
		return {
			"analysis": response,
			"failure_reason": "",
			"failure_target": ""
		}

	async def solutions(self, state: AgentState) -> AgentState:
		await self._progress(
			state,
			"GENERATING_SOLUTIONS",
			"RUNNING",
			"正在独立生成两份标程"
		)
		request = validate_checkpoint(StartRunRequest, state["request"])
		context = str(state["analysis"])
		a, b = await asyncio.gather(
			self.model.generate(
				SolutionResult,
				SYSTEM_SOLUTIONS,
				request.statement_markdown + "\n分析：" + context
			),
			self.model.generate(
				SolutionResult,
				SYSTEM_SOLUTIONS,
				request.statement_markdown + "\n请独立求解，不参考另一候选。\n分析：" + context
			),
		)
		a_dump, b_dump = a.model_dump(), b.model_dump()
		await self._step(
			state,
			"solutions",
			"GENERATING_SOLUTIONS",
			{"solution_a": a_dump, "solution_b": b_dump}
		)
		return {"solution_a": a_dump, "solution_b": b_dump}

	async def design(self, state: AgentState) -> AgentState:
		await self._progress(
			state,
			"TEST_DESIGN",
			"RUNNING",
			"正在设计边界和对抗测试"
		)
		request = validate_checkpoint(StartRunRequest, state["request"])
		prompt = request.statement_markdown + str(state["analysis"])
		if state.get("redesign_requested"):
			prompt += "\n\n上一轮对抗审查发现（请在新测试计划中修正）：" + json.dumps(
				state.get("review", {}).get("findings", []),
				ensure_ascii=False,
			)
		result = await self.model.generate(TestDesignResult, SYSTEM_DESIGN, prompt)
		response = result.model_dump()
		await self._step(state, "design", "REVIEWING", response)
		return {"test_design": response}

	async def review(self, state: AgentState) -> AgentState:
		await self._progress(
			state,
			"ADVERSARIAL_REVIEW",
			"RUNNING",
			"正在审查两份标程和测试计划"
		)
		prompt = str(
			{key: state[key] for key in
			 ("analysis", "solution_a", "solution_b", "test_design")}
		)
		resume = state.get("resume", {})
		if resume.get("action") == "rereview":
			prompt += "\n\n人工澄清内容：" + json.dumps(
				resume.get("correction"),
				ensure_ascii=False
			)
		result = await self.model.generate(ReviewResult, SYSTEM_REVIEW, prompt)
		response = result.model_dump()
		failure = result.ambiguities and "对抗审查发现未解决歧义" or None
		await self._step(
			state,
			"review",
			"REVIEWING",
			response,
			failure_reason=failure
		)
		if result.ambiguities:
			# 阻断性歧义：交给人工定夺。
			return {
				"review": response,
				"failure_reason": failure,
				"failure_target": "REVIEWING",
				"redesign_requested": False,
			}
		round_ = state.get("design_round", 0)
		if result.findings and round_ < REVIEW_REDESIGN_LIMIT:
			# 非阻断发现：回到测试设计，带 findings 重新生成测试计划。
			return {
				"review": response,
				"failure_reason": "",
				"failure_target": "",
				"design_round": round_ + 1,
				"redesign_requested": True,
			}
		return {
			"review": response,
			"failure_reason": "",
			"failure_target": "",
			"redesign_requested": False,
		}

	async def artifacts(self, state: AgentState) -> AgentState:
		await self._progress(
			state,
			"GENERATING_ARTIFACTS",
			"RUNNING",
			"正在生成确定性生成器、校验器和暴力解"
		)
		request = validate_checkpoint(StartRunRequest, state["request"])
		repair = state.get("repair_round", request.repair_round)
		failure = state.get("failure_reason", "")
		prompt = (
				f"生成 {request.test_case_count} 个互异固定种子；前 3 个为适合暴力解的小数据。"
				f"当前修复轮次 {repair}，上轮沙箱失败原因：{failure}\n"
				+ str(
			{key: state[key] for key in ("analysis", "test_design", "review")}
		)
		)
		result = await self.model.generate(
			ArtifactResult,
			SYSTEM_ARTIFACTS,
			prompt
		)
		response = result.model_dump()
		await self._step(state, "artifacts", "GENERATING_TESTS", response)
		return {"artifacts": response, "failure_reason": ""}

	async def submit(self, state: AgentState) -> AgentState:
		request = validate_checkpoint(StartRunRequest, state["request"])
		job_id = await self.kotlin.submit_sandbox(
			request.run_id,
			sandbox_payload(state)
		)
		await self._progress(
			state,
			"SANDBOX",
			"WAITING",
			"沙箱任务已提交，等待 Worker 结果"
		)
		resumed = interrupt(
			{
				"sandboxJobId": str(job_id),
				"repairRound": state.get("repair_round", 0)
			}
		)
		result = validate_checkpoint(SandboxResult, resumed)
		return {
			"sandbox_job_id": str(job_id),
			"sandbox_result": result.model_dump(mode="json")
		}

	async def finish(self, state: AgentState) -> AgentState:
		await self._progress(
			state,
			"COMPLETED",
			"SUCCEEDED",
			"测试点和标准答案已通过沙箱验证"
		)
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
		return {
			"resume": decision,
			"resume_attempt": state.get("resume_attempt", 0) + 1
		}

	@staticmethod
	def after_analysis(state: AgentState) -> Literal["solutions", "fail"]:
		return "fail" if state.get("failure_reason") else "solutions"

	@staticmethod
	def after_review(state: AgentState) -> Literal["artifacts", "design", "fail"]:
		"""歧义交人工接管；非阻断发现回退测试设计；否则继续生成测试数据。"""
		if state.get("failure_reason"):
			return "fail"
		return "design" if state.get("redesign_requested") else "artifacts"

	@staticmethod
	def after_sandbox(state: AgentState) -> Literal["finish", "repair", "fail"]:
		result = validate_checkpoint(SandboxResult, state["sandbox_result"])
		if result.status is SandboxStatus.PASSED:
			return "finish"
		if result.status is SandboxStatus.VALIDATION_FAILED and state.get(
				"repair_round",
				0
		) < 2:
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
		graph.add_conditional_edges(
			"review",
			self.after_review,
			{"fail": "fail", "design": "design", "artifacts": "artifacts"},
		)
		graph.add_edge("artifacts", "submit")
		graph.add_conditional_edges("submit", self.after_sandbox)
		graph.add_edge("repair", "artifacts")
		graph.add_conditional_edges(
			"fail",
			self.after_fail,
			{"analyze": "analyze", "review": "review", "finish": END}
		)
		graph.add_edge("finish", END)
		return graph.compile(checkpointer=checkpointer)
