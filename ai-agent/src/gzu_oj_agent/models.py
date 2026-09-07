"""Agent 与 Kotlin API 共用的严格数据模型。"""

from enum import StrEnum
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


def to_camel(value: str) -> str:
	"""将 Python 字段名映射为 Kotlin/Jackson 默认的 camelCase。"""
	head, *tail = value.split("_")
	return head + "".join(part.capitalize() for part in tail)


class StrictModel(BaseModel):
	"""拒绝模型或接口悄悄增加未知字段。"""

	model_config = ConfigDict(
		extra="forbid",
		strict=True,
		populate_by_name=True,
		alias_generator=to_camel
	)


Source = Annotated[str, Field(min_length=1, max_length=131_072)]


class AnalysisResult(StrictModel):
	"""题意分析的结构化结果。"""

	summary: str = Field(min_length=1, max_length=20_000)
	constraints: list[str] = Field(default_factory=list, max_length=200)
	ambiguities: list[str] = Field(default_factory=list, max_length=100)


class SolutionResult(StrictModel):
	"""独立标程候选。"""

	summary: str = Field(min_length=1, max_length=20_000)
	source_code: Source

	@field_validator("source_code")
	@classmethod
	def check_source_bytes(cls, value: str) -> str:
		"""数据库限制按 UTF-8 字节数而非字符数计算。"""
		if len(value.encode()) > 131_072:
			raise ValueError("源码超过 128 KiB")
		return value


class TestDesignResult(StrictModel):
	"""测试类别与边界覆盖计划。"""

	test_plan: list[str] = Field(min_length=1, max_length=200)


class ReviewResult(StrictModel):
	"""对抗审查结果。"""

	findings: list[str] = Field(default_factory=list, max_length=200)
	ambiguities: list[str] = Field(default_factory=list, max_length=100)


class ArtifactResult(StrictModel):
	"""可由 Worker 编译执行的生成器、校验器和暴力解。"""

	generator_source: Source
	validator_source: Source
	brute_force_source: Source
	seeds: list[int] = Field(min_length=1, max_length=200)

	@field_validator("seeds")
	@classmethod
	def unique_seeds(cls, value: list[int]) -> list[int]:
		"""固定种子必须互异，才能形成可审计测试点。"""
		if len(value) != len(set(value)):
			raise ValueError("固定种子不能重复")
		return value


class StartRunRequest(StrictModel):
	"""Kotlin 幂等启动一次图运行时锁定的题目快照。"""

	run_id: UUID
	problem_version_id: UUID
	statement_markdown: str = Field(min_length=1, max_length=200_000)
	test_case_count: int = Field(ge=1, le=200)
	time_limit_ms: int = Field(ge=100, le=60_000)
	# MiB 的缩写大小写不能由通用 camelCase 转换规则推断，必须与 Kotlin 协议显式一致。
	memory_limit_mib: int = Field(ge=16, le=2_048, alias="memoryLimitMiB")
	repair_round: int = Field(default=0, ge=0, le=2)


class SandboxTask(StrictModel):
	"""Python 提交给 Kotlin 的确定性沙箱任务。"""

	repair_round: int = Field(ge=0, le=2)
	solution_a_source: Source
	solution_b_source: Source
	brute_force_source: Source
	generator_source: Source
	validator_source: Source
	seeds: list[int] = Field(min_length=1, max_length=200)
	brute_force_case_count: int = Field(ge=1, le=3)
	time_limit_ms: int = Field(ge=100, le=60_000)
	# 与 Kotlin AiSandboxTaskPayload 的 memoryLimitMiB 属性保持一致。
	memory_limit_mib: int = Field(ge=16, le=2_048, alias="memoryLimitMiB")


class SandboxStatus(StrEnum):
	"""Kotlin 回传的沙箱结算类型。"""

	PASSED = "PASSED"
	VALIDATION_FAILED = "VALIDATION_FAILED"
	SYSTEM_ERROR = "SYSTEM_ERROR"


class SandboxResult(StrictModel):
	"""恢复 LangGraph 的沙箱结果通知。"""

	event_id: UUID
	run_id: UUID
	sandbox_job_id: UUID
	repair_round: int = Field(ge=0, le=2)
	status: SandboxStatus
	failure_reason: str | None = Field(default=None, max_length=2_000)


class ProgressEvent(StrictModel):
	"""不包含模型原文的通用进度事件。"""

	event_id: UUID
	run_id: UUID
	stage: str = Field(min_length=1, max_length=64)
	status: str = Field(min_length=1, max_length=32)
	message: str = Field(min_length=1, max_length=2_000)
	repair_round: int = Field(ge=0, le=2)
