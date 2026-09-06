# GZU OJ AI Agent

该服务只负责模型调用和 LangGraph 编排，不读取 OJ 业务数据库。Kotlin API 负责管理员鉴权、沙箱队列和最终落库。

## 本地启动

Agent 使用独立 PostgreSQL 数据库保存 LangGraph 检查点。数据库用户需要能创建表，不能使用 OJ 业务库：

```bash
createdb -U riversea gzu_oj_agent
cp .env.example .env
# 令牌应与 API 的 GZU_OJ_AGENT_INTERNAL_TOKEN 完全一致
uv sync
uv run uvicorn gzu_oj_agent.app:app --host 127.0.0.1 --port 8090
```

默认配置连接本机 Ollama 的 OpenAI 兼容接口。启动前确认模型已经存在；服务只做健康检查，不会自动下载：

```bash
ollama serve
ollama list
# 缺少模型时手动下载：ollama pull qwen2.5
```

另一个终端启动 Kotlin API，并设置同一令牌：

```bash
export GZU_OJ_AGENT_BASE_URL=http://127.0.0.1:8090
export GZU_OJ_AGENT_INTERNAL_TOKEN='replace-with-the-same-token'
/home/riversea/.sdkman/candidates/gradle/9.6.1/bin/gradle :api:bootRun
```

还需要启动至少一个带 AI 槽位的 Worker 和 go-judge。管理员登录后先保存题目草稿，再在题目编辑页点击“启动 AI”；也可以直接调用管理员接口（浏览器会话仍需有效的管理员 Cookie 和 CSRF）：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/admin/test-generation-runs \
  -H 'Content-Type: application/json' \
  -d '{"problemVersionId":"<draft-version-uuid>","testCaseCount":10,"autoPublish":false,"sampleCount":0}'
```

运行状态通过 `GET /api/v1/admin/test-generation-runs/{runId}` 查询。只有 Worker 返回确定性复现、双标程一致、暴力差分和资源门禁全部通过后，测试点与标准答案源码才会在同一事务中写入数据库。

## 容器启动

控制端 Compose 会同时启动 `agent-database` 和 `ai-agent`。复制根目录 `.env.example` 为 `.env`，至少修改业务库密码、Agent 数据库密码、Agent Token，并确保 `GZU_OJ_AGENT_LLM_*` 指向可访问的模型服务：

```bash
docker compose -f infra/compose/compose.control.yml up -d --build
docker compose -f infra/compose/compose.judge.yml up -d --build
```

检查点数据库需要预先创建（本地模式）；容器模式由 Compose 初始化。Ollama 必须已经存在配置的模型，启动检查不会自动拉取模型。
