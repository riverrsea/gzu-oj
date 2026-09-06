"""Python Agent 到 Kotlin API 的内部 HTTP 边界。"""

from uuid import UUID

import httpx

from .config import Settings
from .models import ProgressEvent, SandboxTask


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

    async def fail(self, run_id: UUID, reason: str) -> None:
        """通知 Kotlin 进入人工接管。"""
        response = await self.client.post(
            f"/internal/agent/v1/runs/{run_id}/fail", json={"reason": reason[:2_000]}
        )
        response.raise_for_status()

    async def cancel(self, run_id: UUID) -> None:
        """确认取消通知已被 Agent 接收。"""
        response = await self.client.post(f"/internal/agent/v1/runs/{run_id}/cancel-ack")
        response.raise_for_status()

    async def close(self) -> None:
        if self._owned:
            await self.client.aclose()
