"""Python Agent 到 Kotlin API 的内部 HTTP 边界。"""

import sys
from typing import Any
from uuid import UUID

import httpx

from .config import Settings
from .models import ProgressEvent, SandboxTask, StepRecord


class KotlinClient:
    """所有请求复用同一内部 Bearer Token，并由事件标识支持幂等。"""

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self._owned = client is None
        self.client = client or httpx.AsyncClient(
            base_url=settings.kotlin_api_base_url,
            headers={"Authorization": f"Bearer {settings.agent_internal_token}"},
            timeout=settings.llm_timeout_seconds,
        )

    async def progress(self, event: ProgressEvent) -> None:
        """追加一条模型无关进度事件。"""
        response = await self.client.post(
            "/internal/agent/v1/events", json=event.model_dump(mode="json", by_alias=True)
        )
        response.raise_for_status()

    async def submit_sandbox(self, run_id: UUID, task: SandboxTask) -> UUID:
        """按运行和修复轮次幂等创建沙箱任务。"""
        response = await self.client.post(
            f"/internal/agent/v1/runs/{run_id}/sandbox-jobs",
            json={"repairRound": task.repair_round, "task": task.model_dump(mode="json", by_alias=True)},
        )
        response.raise_for_status()
        return UUID(response.json()["sandboxJobId"])

    async def fail(self, run_id: UUID, reason: str, resume_target: str | None = None) -> None:
        """通知 Kotlin 进入人工接管；可携带可恢复的失败阶段目标。"""
        payload: dict[str, Any] = {"reason": reason[:2_000]}
        if resume_target:
            payload["resumeTarget"] = resume_target
        response = await self.client.post(
            f"/internal/agent/v1/runs/{run_id}/fail", json=payload
        )
        response.raise_for_status()

    async def record_step(
        self,
        run_id: UUID,
        role: str,
        state: str,
        response: dict[str, Any],
        failure_reason: str | None = None,
        cost_microunits: int = 0,
    ) -> None:
        """回传单个角色的结构化响应供 Kotlin 审计；失败不应中断主流程。"""
        try:
            resp = await self.client.post(
                f"/internal/agent/v1/runs/{run_id}/steps",
                json=StepRecord(
                    role=role,
                    state=state,
                    response=response,
                    failure_reason=failure_reason,
                    cost_microunits=cost_microunits,
                ).model_dump(mode="json", by_alias=True),
            )
            resp.raise_for_status()
        except Exception as error:  # 审计步骤属于便利项，失败不阻塞图运行。
            print(f"record_step failed for run {run_id}: {error}", file=sys.stderr)

    async def cancel(self, run_id: UUID) -> None:
        """确认取消通知已被 Agent 接收。"""
        response = await self.client.post(f"/internal/agent/v1/runs/{run_id}/cancel-ack")
        response.raise_for_status()

    async def close(self) -> None:
        if self._owned:
            await self.client.aclose()
