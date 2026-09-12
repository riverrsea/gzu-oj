# GZU_OJ

面向学校复试机试真题和个人练习的小型在线判题系统。项目采用 Kotlin/JVM 21、Spring Boot、Vue 3、PostgreSQL、Caddy、go-judge 和独立 Python AI Agent，按控制端与判题端解耦部署。

## 模块

- `api`：认证、题库、不可变题目版本、提交、比赛、导入、持久化队列和 AI 运行管理。
- `ai-agent`：Python FastAPI、LangGraph 和 OpenAI 兼容模型适配；不读取 OJ 业务表。
- `worker`：异地判题节点，领取租约并调用同机 go-judge。
- `shared`：判题、计分、排名和 AI 状态机公共契约。
- `web`：Vue 3 用户端与管理员端，题面用 marked + KaTeX 渲染 Markdown 与公式。
- `crawler`：Python 采集 CLI（`SourceAdapter -> CanonicalProblem -> 标准 ZIP`），输出契约与 `crawler-cli` 一致。
- `crawler-cli`：迁移前的 Kotlin 采集实现，保留用于回归对照。
- `infra`：控制端、判题端、一体化 Compose，以及 Caddy、备份和预检。

## 本地开发

本机 Gradle 可以直接使用，不要求每次都通过 Wrapper。当前项目使用 Gradle 9.6.1：

```bash
GRADLE_USER_HOME=$PWD/.gradle-user-home gradle test
```

`gradlew` 仍保留用于 CI 和未安装同版本 Gradle 的机器，以保证构建版本可复现。前端使用 lockfile：

```bash
cd web
npm ci
npm run build
```

本地 PostgreSQL 默认连接 `jdbc:postgresql://127.0.0.1:5432/gzu_oj`，也可以通过 `GZU_OJ_DB_*` 覆盖。启动 API 和前端：

```bash
gradle :api:bootRun
cd web && npm run dev
```

## 启动 AI 测试点生成

AI 流程由独立 Agent 驱动，API 只负责鉴权、队列和落库。先准备独立检查点数据库、OpenAI 兼容模型和相同的内部 Token：

```bash
createdb -U riversea gzu_oj_agent
cp ai-agent/.env.example ai-agent/.env
# 修改 ai-agent/.env 的 AGENT_INTERNAL_TOKEN，且与 API 的 GZU_OJ_AGENT_INTERNAL_TOKEN 相同
cd ai-agent
uv sync
uv run uvicorn gzu_oj_agent.app:app --host 127.0.0.1 --port 8090
```

Ollama 示例：先执行 `ollama serve`，确认 `ollama list` 中存在 `qwen2.5`；缺少模型时手动执行 `ollama pull qwen2.5`。再在另一个终端设置 `GZU_OJ_AGENT_BASE_URL` 和 `GZU_OJ_AGENT_INTERNAL_TOKEN` 启动 API。管理员保存题目草稿后，在题目编辑页点击“启动 AI”，Worker 的 AI 槽位会领取并执行沙箱任务。

完整的本地启动顺序、curl 接口和容器环境变量见 [`ai-agent/README.md`](ai-agent/README.md)。

## 部署入口

复制 `.env.example` 为 `.env` 并替换所有 Token 和密码，然后选择拓扑：

```bash
docker compose -f infra/compose/compose.control.yml up -d --build
docker compose -f infra/compose/compose.judge.yml up -d --build
docker compose -f infra/compose/compose.all-in-one.yml up -d --build
```

详细说明见 [架构](docs/architecture.md)、[部署](docs/deployment.md)、[运维](docs/operations.md) 和 [备份恢复](docs/backup-restore.md)。
