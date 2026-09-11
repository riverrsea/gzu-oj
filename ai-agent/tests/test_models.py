import json
from uuid import uuid4

import pytest
from pydantic import ValidationError

from gzu_oj_agent.graph import Workflow, sandbox_payload
from gzu_oj_agent.models import SandboxResult, SandboxStatus, SolutionResult, StartRunRequest, TestDataResult as DataResult


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
