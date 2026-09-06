"""FastAPI 入口和 LangGraph 恢复接口。"""

import asyncio
import hmac
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, Response, status
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.types import Command

from .config import Settings
from .graph import Workflow
from .kotlin import KotlinClient
from .llm import ModelClient
from .models import SandboxResult, StartRunRequest


class AgentRuntime:
    """保存应用生命周期内的依赖和可取消后台任务。"""

    def __init__(self, settings: Settings, graph: Any, kotlin: KotlinClient) -> None:
        self.settings = settings
        self.graph = graph
        self.kotlin = kotlin
        self.tasks: dict[UUID, asyncio.Task[Any]] = {}

    @staticmethod
    def config(run_id: UUID) -> dict[str, Any]:
        """runId 同时作为 LangGraph thread_id，保证启动和恢复幂等。"""
        return {"configurable": {"thread_id": str(run_id)}}

    def run_in_background(self, run_id: UUID, value: Any) -> None:
        """同一进程只保留一个运行任务；数据库检查点负责跨重启恢复。"""
        existing = self.tasks.get(run_id)
        if existing is not None and not existing.done():
            return
        task = asyncio.create_task(self.graph.ainvoke(value, self.config(run_id)))
        self.tasks[run_id] = task
        task.add_done_callback(lambda _task: self.tasks.pop(run_id, None))


def create_app(settings: Settings | None = None, runtime: AgentRuntime | None = None) -> FastAPI:
    """创建应用；测试可注入内存 checkpointer 和伪模型。"""
    effective = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if runtime is not None:
            app.state.runtime = runtime
            yield
            return
        model = ModelClient(effective)
        if effective.startup_model_check:
            await model.healthcheck()
        kotlin = KotlinClient(effective)
        async with AsyncPostgresSaver.from_conn_string(effective.agent_database_url) as saver:
            await saver.setup()
            app.state.runtime = AgentRuntime(effective, Workflow(model, kotlin).compile(saver), kotlin)
            try:
                yield
            finally:
                for task in app.state.runtime.tasks.values():
                    task.cancel()
                await kotlin.close()

    app = FastAPI(title="GZU OJ AI Agent", version="0.1.0", lifespan=lifespan)

    async def authorize(authorization: str | None = Header(default=None)) -> None:
        expected = f"Bearer {effective.agent_internal_token}"
        if authorization is None or not hmac.compare_digest(authorization, expected):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid internal token")

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "UP"}

    @app.post("/internal/v1/runs", status_code=status.HTTP_202_ACCEPTED, dependencies=[Depends(authorize)])
    async def start_run(body: StartRunRequest) -> Response:
        current: AgentRuntime = app.state.runtime
        snapshot = await current.graph.aget_state(current.config(body.run_id))
        if not snapshot.values:
            current.run_in_background(
                body.run_id,
                {"request": body.model_dump(mode="json"), "repair_round": body.repair_round},
            )
        return Response(status_code=status.HTTP_202_ACCEPTED)

    @app.post(
        "/internal/v1/runs/{run_id}/sandbox-results",
        status_code=status.HTTP_202_ACCEPTED,
        dependencies=[Depends(authorize)],
    )
    async def sandbox_result(run_id: UUID, body: SandboxResult) -> Response:
        if body.run_id != run_id:
            raise HTTPException(status_code=409, detail="runId does not match path")
        current: AgentRuntime = app.state.runtime
        snapshot = await current.graph.aget_state(current.config(run_id))
        if not snapshot.values:
            raise HTTPException(status_code=404, detail="run checkpoint not found")
        expected_round = int(snapshot.values.get("repair_round", 0))
        if body.repair_round != expected_round:
            raise HTTPException(status_code=409, detail="stale repair round")
        if snapshot.next:
            current.run_in_background(
                run_id,
                Command(resume=body.model_dump(mode="json")),
            )
        return Response(status_code=status.HTTP_202_ACCEPTED)

    @app.post(
        "/internal/v1/runs/{run_id}/cancel",
        status_code=status.HTTP_202_ACCEPTED,
        dependencies=[Depends(authorize)],
    )
    async def cancel_run(run_id: UUID) -> Response:
        current: AgentRuntime = app.state.runtime
        task = current.tasks.get(run_id)
        if task is not None:
            task.cancel()
        snapshot = await current.graph.aget_state(current.config(run_id))
        if snapshot.values:
            await current.graph.aupdate_state(current.config(run_id), {"canceled": True})
        await current.kotlin.cancel(run_id)
        return Response(status_code=status.HTTP_202_ACCEPTED)

    return app


app = create_app()
