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
	ProgressEvent,
	SandboxCompileTask,
	SandboxCompileUnit,
	SandboxFailureStage,
	SandboxResult,
	SandboxStatus,
	SandboxTask,
	SolutionResult,
	StartRunRequest,
	TestDataResult,
)

# 每个编译门禁允许的编译次数：首次生成后最多再重生成两次。
MAX_COMPILE_ATTEMPTS = 3

# 生成顺序固定；修复时只重做失效的那一批产物，已通过门禁的产物继续复用。
GENERATION_ORDER = (
	SandboxFailureStage.TEST_DATA,
	SandboxFailureStage.SOLUTIONS,
	SandboxFailureStage.BRUTE_FORCE,
)

# 每批产物对应的生成节点名。
COMPILE_GENERATOR = {
	SandboxFailureStage.TEST_DATA: "test_data",
	SandboxFailureStage.SOLUTIONS: "solutions",
	SandboxFailureStage.BRUTE_FORCE: "brute_force",
}

# 编译门禁沿用生成节点的进度阶段名，便于复用同一个状态投影。
COMPILE_PROGRESS_STAGE = {
	SandboxFailureStage.TEST_DATA: "GENERATING_TEST_DATA",
	SandboxFailureStage.SOLUTIONS: "GENERATING_SOLUTIONS",
	SandboxFailureStage.BRUTE_FORCE: "GENERATING_BRUTE_FORCE",
}

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

# 暴力解角色：正确性优先、允许低效，用于小数据差分。
SYSTEM_BRUTE_FORCE = (
	f"你是 OJ 题目的暴力解编写者。{_DETERMINISTIC_SOURCE} "
	"输出一份正确性优先、允许低效的暴力解法，用于在中小规模数据上与标程差分；"
	"它必须独立于标程的算法思路，按题意朴素直接实现。"
)

# 测试数据设计与生成角色：产出确定性输入生成器与输入校验器；覆盖清单直接写进提示词。
SYSTEM_TEST_DATA = (
	f"你是 OJ 题目的测试数据设计与生成者。{_DETERMINISTIC_SOURCE}\n"
	"生成两个程序：①输入生成器——以十进制命令行参数 seed 为输入，向 stdout 输出一份完整测试输入；"
	"②输入校验器——从 stdin 读取一份输入，用退出码表示是否合法（0=合法，非 0=非法）。\n"
	"生成器必须确定性：同一 seed 两次运行输出完全一致，不同 seed 输出不同；"
	"校验器必须严格按题面约束检查输入规模、值域与格式。\n"
	"种子固定为 1..N（N 为本题要求的测试点数量，见用户消息）。生成器必须按 seed 依次覆盖下列类别："
	"①最小规模（如 n=1）②最大规模（贴合题面上限）③退化/全相等 ④负数或极端值（题面允许时）⑤随机中规模 ⑥对抗反例（专卡常见错误解法）；"
	"其中 seed 1..3 必须是小规模数据，便于暴力解验证。"
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
	test_data: dict[str, Any]
	solution_a: dict[str, Any]
	solution_b: dict[str, Any]
	brute_force: dict[str, Any]
	compile_result: dict[str, Any]
	compiled_stages: list[str]
	compile_stage: str
	test_data_attempts: int
	solutions_attempts: int
	brute_force_attempts: int
	sandbox_job_id: str
	sandbox_result: dict[str, Any]
	repair_round: int
	resume_attempt: int
	failure_reason: str
	failure_target: str
	repair_target: str
	resume: dict[str, Any]
	canceled: bool


def sandbox_payload(state: AgentState) -> SandboxTask:
	"""只从 Pydantic 已校验结果构造 Worker 任务；测试种子固定为 1..N。"""
	request = validate_checkpoint(StartRunRequest, state["request"])
	solution_a = validate_checkpoint(SolutionResult, state["solution_a"])
	solution_b = validate_checkpoint(SolutionResult, state["solution_b"])
	brute_force = validate_checkpoint(SolutionResult, state["brute_force"])
	test_data = validate_checkpoint(TestDataResult, state["test_data"])
	seeds = list(range(1, request.test_case_count + 1))
	return SandboxTask(
		repair_round=state.get("repair_round", request.repair_round),
		solution_a_source=solution_a.source_code,
		solution_b_source=solution_b.source_code,
		brute_force_source=brute_force.source_code,
		generator_source=test_data.generator_source,
		validator_source=test_data.validator_source,
		seeds=seeds,
		brute_force_case_count=min(3, len(seeds)),
		time_limit_ms=request.time_limit_ms,
		memory_limit_mib=request.memory_limit_mib,
	)


def compile_units(state: AgentState, stage: SandboxFailureStage) -> list[SandboxCompileUnit]:
	"""取出该阶段刚生成的源码；源码缺失说明图被写坏，直接抛错而不是送空编译。"""
	if stage is SandboxFailureStage.SOLUTIONS:
		return [
			SandboxCompileUnit(label="标程 A", source=validate_checkpoint(SolutionResult, state["solution_a"]).source_code),
			SandboxCompileUnit(label="标程 B", source=validate_checkpoint(SolutionResult, state["solution_b"]).source_code),
		]
	if stage is SandboxFailureStage.BRUTE_FORCE:
		return [SandboxCompileUnit(label="暴力解", source=validate_checkpoint(SolutionResult, state["brute_force"]).source_code)]
	test_data = validate_checkpoint(TestDataResult, state["test_data"])
	return [
		SandboxCompileUnit(label="测试生成器", source=test_data.generator_source),
		SandboxCompileUnit(label="输入校验器", source=test_data.validator_source),
	]


def reusable_stages(state: AgentState, stage: SandboxFailureStage) -> list[str]:
	"""返回重新生成某批产物后仍然有效的其他产物；重做的那一批必须重新过门禁。"""
	return [name for name in state.get("compiled_stages", []) if name != stage.name]


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
		message: str,
		salt: int | str | None = None,
	) -> None:
		request = validate_checkpoint(StartRunRequest, state["request"])
		repair_round = state.get("repair_round", request.repair_round)
		# 编译重试发生在同一轮内，必须用 salt 区分同一阶段的多条进度事件。
		attempt = state.get("resume_attempt", 0) if salt is None else salt
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
			"failure_target": "",
			# 题意分析变化后标程与暴力解都可能失效，必须全部重新生成。
			"compiled_stages": []
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
		# 定向修复标程时必须带上上轮沙箱失败原因，否则会重新生成同样的错误实现。
		failure = state.get("failure_reason", "")
		repair_note = f"\n上轮沙箱失败原因：{failure}" if failure else ""
		a, b = await asyncio.gather(
			self.model.generate(
				SolutionResult,
				SYSTEM_SOLUTIONS,
				request.statement_markdown + "\n分析：" + context + repair_note
			),
			self.model.generate(
				SolutionResult,
				SYSTEM_SOLUTIONS,
				request.statement_markdown + "\n请独立求解，不参考另一候选。\n分析：" + context + repair_note
			),
		)
		a_dump, b_dump = a.model_dump(), b.model_dump()
		await self._step(
			state,
			"solutions",
			"GENERATING_SOLUTIONS",
			{"solution_a": a_dump, "solution_b": b_dump}
		)
		return {
			"solution_a": a_dump,
			"solution_b": b_dump,
			"compiled_stages": reusable_stages(state, SandboxFailureStage.SOLUTIONS)
		}

	async def test_data(self, state: AgentState) -> AgentState:
		await self._progress(
			state,
			"GENERATING_TEST_DATA",
			"RUNNING",
			"正在生成测试数据生成器与输入校验器"
		)
		request = validate_checkpoint(StartRunRequest, state["request"])
		repair = state.get("repair_round", request.repair_round)
		failure = state.get("failure_reason", "")
		prompt = (
			f"本题需要 {request.test_case_count} 个测试点，种子固定为 1..{request.test_case_count}。"
			f"当前修复轮次 {repair}，上轮失败原因：{failure}\n"
			+ str({"analysis": state.get("analysis", {})})
		)
		result = await self.model.generate(TestDataResult, SYSTEM_TEST_DATA, prompt)
		response = result.model_dump()
		await self._step(state, "test_data", "GENERATING_TESTS", response)
		return {
			"test_data": response,
			"failure_reason": "",
			"compiled_stages": reusable_stages(state, SandboxFailureStage.TEST_DATA)
		}

	async def brute_force(self, state: AgentState) -> AgentState:
		await self._progress(
			state,
			"GENERATING_BRUTE_FORCE",
			"RUNNING",
			"正在生成小数据暴力解"
		)
		request = validate_checkpoint(StartRunRequest, state["request"])
		failure = state.get("failure_reason", "")
		prompt = (
			f"当前修复轮次 {state.get('repair_round', request.repair_round)}，上轮失败原因：{failure}\n"
			+ str({"analysis": state.get("analysis", {})})
		)
		result = await self.model.generate(SolutionResult, SYSTEM_BRUTE_FORCE, prompt)
		response = result.model_dump()
		await self._step(state, "brute_force", "GENERATING_SOLUTIONS", response)
		return {
			"brute_force": response,
			"compiled_stages": reusable_stages(state, SandboxFailureStage.BRUTE_FORCE)
		}

	async def compile_test_data(self, state: AgentState) -> AgentState:
		"""编译生成器与输入校验器。"""
		return await self._compile_gate(state, SandboxFailureStage.TEST_DATA)

	async def compile_solutions(self, state: AgentState) -> AgentState:
		"""编译两份标程。"""
		return await self._compile_gate(state, SandboxFailureStage.SOLUTIONS)

	async def compile_brute_force(self, state: AgentState) -> AgentState:
		"""编译小数据暴力解。"""
		return await self._compile_gate(state, SandboxFailureStage.BRUTE_FORCE)

	async def _compile_gate(self, state: AgentState, stage: SandboxFailureStage) -> AgentState:
		"""提交单阶段编译门禁并等待 Worker 结果。

		编译节点自身不生成任何源码：LangGraph 恢复 interrupt 时会从头重跑节点，
		生成动作留在上游节点才能保证重放时拿到同一个幂等任务键。
		"""
		request = validate_checkpoint(StartRunRequest, state["request"])
		repair_round = state.get("repair_round", request.repair_round)
		counter = f"{stage.name.lower()}_attempts"
		attempt = state.get(counter, 0)
		task = SandboxCompileTask(stage=stage, units=compile_units(state, stage))
		job_id = await self.kotlin.submit_compile(request.run_id, task, repair_round, attempt)
		await self._progress(
			state,
			COMPILE_PROGRESS_STAGE[stage],
			"RUNNING",
			f"正在编译{'、'.join(unit.label for unit in task.units)}",
			salt=f"compile{attempt}",
		)
		resumed = interrupt(
			{
				"sandboxJobId": str(job_id),
				"repairRound": repair_round,
				"compileAttempt": attempt,
			}
		)
		result = validate_checkpoint(SandboxResult, resumed)
		if str(result.sandbox_job_id) != str(job_id):
			raise RuntimeError("编译门禁恢复结果与当前任务不一致")
		passed = result.status is SandboxStatus.PASSED
		failure = "" if passed else (result.failure_reason or "编译未通过")
		compiled = set(reusable_stages(state, stage))
		if passed:
			compiled.add(stage.name)
		# 统一按固定生成顺序落库，便于直接阅读检查点里的产物状态。
		compiled_stages = [candidate.name for candidate in GENERATION_ORDER if candidate.name in compiled]
		return {
			"compile_result": result.model_dump(mode="json"),
			"compile_stage": stage.name,
			counter: attempt + 1,
			"compiled_stages": compiled_stages,
			"failure_reason": failure,
			# 编译连续失败不可由"重新分析"自动恢复，交管理员处理。
			"failure_target": "" if failure else state.get("failure_target", ""),
		}

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
	def after_analysis(state: AgentState) -> Literal["test_data", "fail"]:
		return "fail" if state.get("failure_reason") else "test_data"

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
	def after_fail(state: AgentState) -> Literal["analyze", "finish"]:
		"""根据人工恢复指令重跑题意分析；无恢复指令时结束。"""
		action = state.get("resume", {}).get("action")
		if action == "reanalyze":
			return "analyze"
		return "finish"

	@staticmethod
	def after_repair(state: AgentState) -> Literal["test_data", "solutions", "brute_force"]:
		"""按失败归属把修复定向到出错节点，避免整条链路从头重跑。"""
		target = state.get("repair_target")
		if target in ("solutions", "brute_force"):
			return target
		return "test_data"

	@staticmethod
	def prepare_repair(state: AgentState) -> AgentState:
		result = validate_checkpoint(SandboxResult, state["sandbox_result"])
		# 归属为空说明 Kotlin 无法定向，只能按最保守的测试数据重做。
		stage = result.failed_stage
		if stage is SandboxFailureStage.SOLUTIONS:
			target = "solutions"
		elif stage is SandboxFailureStage.BRUTE_FORCE:
			target = "brute_force"
		else:
			target = "test_data"
		return {
			"repair_round": state.get("repair_round", 0) + 1,
			"failure_reason": result.failure_reason or "沙箱差分失败",
			"repair_target": target,
		}

	@staticmethod
	def after_compile(state: AgentState) -> str:
		"""编译通过就跳到下一批未就绪的产物；未通过则在重试预算内重做本批产物。

		标程与暴力解互不依赖：差分把失败归到哪一批，就只重做哪一批，
		不会因为重写标程而连带浪费一次暴力解生成。
		"""
		result = validate_checkpoint(SandboxResult, state["compile_result"])
		stage = SandboxFailureStage(state["compile_stage"])
		if result.status is not SandboxStatus.PASSED:
			if state.get(f"{stage.name.lower()}_attempts", 0) < MAX_COMPILE_ATTEMPTS:
				return COMPILE_GENERATOR[stage]
			return "fail"
		compiled = set(state.get("compiled_stages", []))
		for candidate in GENERATION_ORDER:
			if candidate.name not in compiled:
				return COMPILE_GENERATOR[candidate]
		return "submit"

	def compile(self, checkpointer: Any):
		"""构造固定拓扑；沙箱节点通过 interrupt 持久化暂停。"""
		graph = StateGraph(AgentState)
		graph.add_node("analyze", self.analyze)
		graph.add_node("test_data", self.test_data)
		graph.add_node("compile_test_data", self.compile_test_data)
		graph.add_node("solutions", self.solutions)
		graph.add_node("compile_solutions", self.compile_solutions)
		graph.add_node("brute_force", self.brute_force)
		graph.add_node("compile_brute_force", self.compile_brute_force)
		graph.add_node("submit", self.submit)
		graph.add_node("repair", self.prepare_repair)
		graph.add_node("finish", self.finish)
		graph.add_node("fail", self.fail)
		graph.add_edge(START, "analyze")
		graph.add_conditional_edges("analyze", self.after_analysis)
		# 每个生成节点后面紧跟该产物的编译门禁，编译不通过就回到生成节点重做。
		graph.add_edge("test_data", "compile_test_data")
		graph.add_edge("solutions", "compile_solutions")
		graph.add_edge("brute_force", "compile_brute_force")
		graph.add_conditional_edges("compile_test_data", self.after_compile)
		graph.add_conditional_edges("compile_solutions", self.after_compile)
		graph.add_conditional_edges("compile_brute_force", self.after_compile)
		graph.add_conditional_edges("submit", self.after_sandbox)
		graph.add_conditional_edges(
			"repair",
			self.after_repair,
			{"test_data": "test_data", "solutions": "solutions", "brute_force": "brute_force"},
		)
		graph.add_conditional_edges("fail", self.after_fail, {"analyze": "analyze", "finish": END})
		graph.add_edge("finish", END)
		return graph.compile(checkpointer=checkpointer)
