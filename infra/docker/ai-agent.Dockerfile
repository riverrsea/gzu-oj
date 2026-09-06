# Python Agent 使用 uv 锁定依赖，运行镜像不包含构建缓存和源码以外的工具。
FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim AS build
WORKDIR /app
COPY ai-agent/pyproject.toml ai-agent/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY ai-agent/src ./src
RUN uv sync --frozen --no-dev

FROM python:3.12-slim
RUN useradd --create-home --uid 10001 gzuoj
WORKDIR /app
COPY --from=build /app /app
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
USER gzuoj
EXPOSE 8090
ENTRYPOINT ["uvicorn", "gzu_oj_agent.app:app", "--host", "0.0.0.0", "--port", "8090"]
