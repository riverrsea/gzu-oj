import json
from uuid import uuid4

import pytest
from pydantic import ValidationError

from gzu_oj_agent.graph import MAX_COMPILE_ATTEMPTS, Workflow, compile_units, sandbox_payload
from gzu_oj_agent.models import (
    SandboxCompileTask,
    SandboxCompileUnit,
    SandboxFailureStage,
    SandboxResult,
    SandboxStatus,
    SolutionResult,
    StartRunRequest,
    TestDataResult as DataResult,
)


def base_state() -> dict:
    return {
        "request": {
            "run_id": str(uuid4()),
            "problem_version_id": str(uuid4()),
            "statement_markdown": "求两个整数之和",
            "test_case_count": 2,
            "time_limit_ms": 1000,
            "memory_limit_mib": 256,
            "repair_round": 0,
        },
        "test_data": {
            "generator_source": "int main(){}",
            "validator_source": "int main(){}",
        },
        "solution_a": {"summary": "A", "source_code": "int main(){}"},
        "solution_b": {"summary": "B", "source_code": "int main(){}"},
        "brute_force": {"summary": "BF", "source_code": "int main(){}"},
        "repair_round": 0,
    }


def test_structured_models_reject_unknown_fields() -> None:
    with pytest.raises(ValidationError):
        SolutionResult.model_validate({"summary": "x", "source_code": "x", "raw": "secret"})
    with pytest.raises(ValidationError):
        DataResult.model_validate({"generator_source": "x", "validator_source": "x", "seeds": [1]})


def test_sandbox_payload_uses_fixed_seeds() -> None:
    """测试种子固定为 1..N，数据/标程/暴力解源码全部来自已校验状态。"""
    payload = sandbox_payload(base_state())
    assert payload.seeds == [1, 2]
    assert payload.brute_force_case_count == 2
    assert payload.solution_a_source == "int main(){}"
    assert payload.generator_source == "int main(){}"
    assert payload.brute_force_source == "int main(){}"


def test_kotlin_memory_limit_alias_round_trips() -> None:
    state = base_state()
    request = StartRunRequest.model_validate_json(json.dumps(state["request"]))
    wire = request.model_dump(mode="json", by_alias=True)
    assert wire["memoryLimitMiB"] == 256
    assert "memoryLimitMib" not in wire


def test_fastapi_dict_validation_accepts_kotlin_uuid_strings() -> None:
    state = base_state()
    request = StartRunRequest.model_validate(state["request"])
    assert str(request.run_id) == state["request"]["run_id"]


def test_validation_failure_routes_to_at_most_two_repairs() -> None:
    state = base_state()
    state["sandbox_result"] = SandboxResult(
        event_id=uuid4(), run_id=uuid4(), sandbox_job_id=uuid4(), repair_round=0,
        status=SandboxStatus.VALIDATION_FAILED, failure_reason="wrong answer"
    ).model_dump(mode="json")
    assert Workflow.after_sandbox(state) == "repair"
    state["repair_round"] = 2
    assert Workflow.after_sandbox(state) == "fail"


def test_sandbox_result_accepts_kotlin_camel_json_status() -> None:
    """Kotlin 以 camelCase 字符串发送状态；strict 模型必须能解析，避免 422。"""
    payload = {
        "eventId": str(uuid4()),
        "runId": str(uuid4()),
        "sandboxJobId": str(uuid4()),
        "repairRound": 0,
        "status": "PASSED",
        "failureReason": None,
    }
    result = SandboxResult.model_validate(payload)
    assert result.status is SandboxStatus.PASSED
    # 非法状态名仍必须被拒绝。
    with pytest.raises(ValidationError):
        SandboxResult.model_validate({**payload, "status": "NOT_A_STATUS"})


def test_sandbox_result_accepts_kotlin_failure_stage() -> None:
    """Kotlin 用 camelCase 产物名归因；strict 模型必须能解析，且非法归属被拒绝。"""
    payload = {
        "eventId": str(uuid4()),
        "runId": str(uuid4()),
        "sandboxJobId": str(uuid4()),
        "repairRound": 0,
        "status": "VALIDATION_FAILED",
        "failureReason": "标程 B 编译失败",
        "failedStage": "SOLUTIONS",
    }
    result = SandboxResult.model_validate(payload)
    assert result.failed_stage is SandboxFailureStage.SOLUTIONS
    # 归属为空表示不可定向，必须能正常解析。
    assert SandboxResult.model_validate({**payload, "failedStage": None}).failed_stage is None
    with pytest.raises(ValidationError):
        SandboxResult.model_validate({**payload, "failedStage": "REVIEW"})


def test_repair_is_routed_to_the_failing_artifact() -> None:
    """编译/差分失败只重跑出错产物，避免整条链路从测试数据重新生成。"""
    expectations = {
        None: "test_data",
        SandboxFailureStage.TEST_DATA: "test_data",
        SandboxFailureStage.SOLUTIONS: "solutions",
        SandboxFailureStage.BRUTE_FORCE: "brute_force",
    }
    for stage, node in expectations.items():
        state = base_state()
        state["sandbox_result"] = SandboxResult(
            event_id=uuid4(), run_id=uuid4(), sandbox_job_id=uuid4(), repair_round=0,
            status=SandboxStatus.VALIDATION_FAILED, failure_reason="编译失败", failed_stage=stage
        ).model_dump(mode="json")
        repaired = Workflow.prepare_repair(state)
        assert repaired["repair_target"] == node
        assert repaired["repair_round"] == 1
        assert Workflow.after_repair({**state, **repaired}) == node


def test_compile_task_uses_kotlin_camel_aliases_and_bounded_units() -> None:
    """编译门禁请求必须是 Kotlin 能解析的 camelCase 结构，且一次最多两个产物。"""
    task = SandboxCompileTask(
        stage=SandboxFailureStage.SOLUTIONS,
        units=[
            SandboxCompileUnit(label="标程 A", source="```cpp\nint main(){}\n```"),
            SandboxCompileUnit(label="标程 B", source="int main(){}"),
        ],
    )
    wire = task.model_dump(mode="json", by_alias=True)
    assert wire["stage"] == "SOLUTIONS"
    assert wire["units"][0] == {"label": "标程 A", "source": "int main(){}"}
    with pytest.raises(ValidationError):
        SandboxCompileTask(
            stage=SandboxFailureStage.TEST_DATA,
            units=[
                SandboxCompileUnit(label="a", source="x"),
                SandboxCompileUnit(label="b", source="x"),
                SandboxCompileUnit(label="c", source="x"),
            ],
        )
    with pytest.raises(ValidationError):
        SandboxCompileTask(stage=SandboxFailureStage.TEST_DATA, units=[])


def test_compile_units_match_generated_stage_artifacts() -> None:
    """每个门禁只取本阶段刚生成的源码，标签与 Worker 报错一致。"""
    state = base_state()
    state["test_data"] = {"generator_source": "gen", "validator_source": "val"}
    state["solution_a"] = {"summary": "A", "source_code": "a"}
    state["solution_b"] = {"summary": "B", "source_code": "b"}
    state["brute_force"] = {"summary": "BF", "source_code": "bf"}
    assert [(u.label, u.source) for u in compile_units(state, SandboxFailureStage.TEST_DATA)] == [
        ("测试生成器", "gen"),
        ("输入校验器", "val"),
    ]
    assert [(u.label, u.source) for u in compile_units(state, SandboxFailureStage.SOLUTIONS)] == [
        ("标程 A", "a"),
        ("标程 B", "b"),
    ]
    assert [(u.label, u.source) for u in compile_units(state, SandboxFailureStage.BRUTE_FORCE)] == [
        ("暴力解", "bf")
    ]


def test_compile_gate_retries_then_fails() -> None:
    """编译门禁只有通过才继续；未通过时在预算内重试，用尽后交人工接管。"""
    state = base_state()
    state["compile_stage"] = "SOLUTIONS"

    def gate(status: SandboxStatus, attempts: int) -> str:
        state["compile_result"] = SandboxResult(
            event_id=uuid4(), run_id=uuid4(), sandbox_job_id=uuid4(), repair_round=0,
            status=status, failure_reason="标程 A 编译失败"
        ).model_dump(mode="json")
        state["solutions_attempts"] = attempts
        return Workflow.after_compile(state)

    assert gate(SandboxStatus.PASSED, 1) == "continue"
    assert gate(SandboxStatus.VALIDATION_FAILED, 1) == "retry"
    assert gate(SandboxStatus.VALIDATION_FAILED, MAX_COMPILE_ATTEMPTS - 1) == "retry"
    assert gate(SandboxStatus.VALIDATION_FAILED, MAX_COMPILE_ATTEMPTS) == "fail"


def test_source_fields_strip_markdown_fences() -> None:
    """模型带 ``` 围栏的源码必须在进入沙箱前被剥掉，否则 Worker 编译失败。"""
    fenced = "```cpp\n#include <bits/stdc++.h>\nint main() { return 0; }\n```"
    solution = SolutionResult(summary="s", source_code=fenced)
    assert solution.source_code == "#include <bits/stdc++.h>\nint main() { return 0; }"
    # 未加围栏的源码不受影响。
    assert SolutionResult(summary="s", source_code="int main(){}").source_code == "int main(){}"

    data = DataResult(
        generator_source="```cpp\nint g(){}\n```",
        validator_source="```\nint v(){}\n```",
    )
    assert data.generator_source == "int g(){}"
    assert data.validator_source == "int v(){}"

    # sandbox_payload 重新校验状态时也应得到干净源码。
    state = base_state()
    state["solution_a"] = {"summary": "A", "source_code": fenced}
    assert sandbox_payload(state).solution_a_source.startswith("#include")
