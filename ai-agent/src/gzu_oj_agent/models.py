"""Agent 与 Kotlin API 共用的严格数据模型。"""

import re
from enum import StrEnum
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import BeforeValidator, BaseModel, ConfigDict, Field, field_validator


def to_camel(value: str) -> str:
    """将 Python 字段名映射为 Kotlin/Jackson 默认的 camelCase。"""
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


def parse_uuid(value: UUID | str) -> UUID:
    """允许 FastAPI 的 dict 校验路径解析 Kotlin 发来的 UUID 字符串。"""
    if isinstance(value, UUID):
        return value
    if isinstance(value, str):
        return UUID(value)
    raise TypeError("UUID 必须是字符串或 UUID 实例")


WireUUID = Annotated[UUID, BeforeValidator(parse_uuid)]


class StrictModel(BaseModel):
    """拒绝模型或接口悄悄增加未知字段。"""

    model_config = ConfigDict(extra="forbid", strict=True, populate_by_name=True, alias_generator=to_camel)


# 匹配整段被 Markdown 代码围栏包裹的源码，例如 ```cpp\n...\n```。
_CODE_FENCE_RE = re.compile(r"^\s*`{3,}[^\n]*\n(.*?)\n?\s*`{3,}\s*$", re.DOTALL)


def strip_markdown_fence(value: object) -> object:
    """去掉模型返回源码可能附带的 Markdown 代码围栏。

    模型常把 C++ 源码包在 ```cpp 围栏里；若原样送入 Worker 会因围栏导致编译失败，
    因此在校验阶段就把围栏剥掉，同时兼容只有起始或结束围栏的畸形输出。
    """
    if not isinstance(value, str):
        return value
    text = value.strip()
    fenced = _CODE_FENCE_RE.match(text)
    if fenced:
        return fenced.group(1).strip("\n")
    lines = text.splitlines()
    if lines and lines[0].lstrip().startswith("```"):
        lines = lines[1:]
    if lines and lines[-1].strip().startswith("```"):
        lines = lines[:-1]
    return "\n".join(lines).strip("\n")


Source = Annotated[str, BeforeValidator(strip_markdown_fence), Field(min_length=1, max_length=131_072)]



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

    run_id: WireUUID
    problem_version_id: WireUUID
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

    event_id: WireUUID
    run_id: WireUUID
    sandbox_job_id: WireUUID
    repair_round: int = Field(ge=0, le=2)
    status: SandboxStatus
    failure_reason: str | None = Field(default=None, max_length=2_000)

    @field_validator("status", mode="before")
    @classmethod
    def accept_status_name(cls, value: object) -> object:
        """strict 模式下枚举字段只接受枚举实例，需先把 JSON 状态字符串转成枚举。"""
        if isinstance(value, str):
            return SandboxStatus(value)
        return value


class ProgressEvent(StrictModel):
    """不包含模型原文的通用进度事件。"""

    event_id: WireUUID
    run_id: WireUUID
    stage: str = Field(min_length=1, max_length=64)
    status: str = Field(min_length=1, max_length=32)
    message: str = Field(min_length=1, max_length=2_000)
    repair_round: int = Field(ge=0, le=2)


class StepRecord(StrictModel):
    """回传给 Kotlin 审计的角色结构化响应。"""

    role: str = Field(min_length=1, max_length=64)
    state: str = Field(min_length=1, max_length=32)
    response: dict[str, Any]
    failure_reason: str | None = Field(default=None, max_length=2_000)
    cost_microunits: int = Field(default=0, ge=0)


class HumanResume(StrictModel):
    """人工接管后回传给 Agent 的恢复指令。"""

    action: Literal["reanalyze", "rereview"]
    correction: dict[str, Any] | None = None
