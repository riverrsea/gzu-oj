from uuid import uuid4

import pytest
from pydantic import ValidationError

from gzu_oj_agent.graph import Workflow, sandbox_payload
from gzu_oj_agent.models import ArtifactResult, SandboxResult, SandboxStatus, SolutionResult


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
        "solution_a": {"summary": "A", "source_code": "int main(){}"},
        "solution_b": {"summary": "B", "source_code": "int main(){}"},
        "artifacts": {
            "generator_source": "int main(){}",
            "validator_source": "int main(){}",
            "brute_force_source": "int main(){}",
            "seeds": [11, 12],
        },
        "repair_round": 0,
    }


def test_structured_models_reject_unknown_fields_and_duplicate_seeds() -> None:
    with pytest.raises(ValidationError):
        SolutionResult.model_validate({"summary": "x", "source_code": "x", "raw": "secret"})
    with pytest.raises(ValidationError):
        ArtifactResult.model_validate(
            {"generator_source": "x", "validator_source": "x", "brute_force_source": "x", "seeds": [1, 1]}
        )


def test_sandbox_payload_contains_only_validated_sources() -> None:
    payload = sandbox_payload(base_state())
    assert payload.seeds == [11, 12]
    assert payload.brute_force_case_count == 2
    assert payload.solution_a_source == "int main(){}"


def test_validation_failure_routes_to_at_most_two_repairs() -> None:
    state = base_state()
    state["sandbox_result"] = SandboxResult(
        event_id=uuid4(), run_id=uuid4(), sandbox_job_id=uuid4(), repair_round=0,
        status=SandboxStatus.VALIDATION_FAILED, failure_reason="wrong answer"
    ).model_dump(mode="json")
    assert Workflow.after_sandbox(state) == "repair"
    state["repair_round"] = 2
    assert Workflow.after_sandbox(state) == "fail"
