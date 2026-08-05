# 研试 OJ

面向学校复试机试真题和个人练习的小型在线判题系统。项目采用 Kotlin/JVM 21、Spring Boot、Spring AI、Vue 3、PostgreSQL、Caddy 和 go-judge，按控制端与判题端解耦部署。

## 模块

- `api`：认证、题库、不可变题目版本、提交、比赛、导入、持久化队列和 AI 编排。
- `worker`：异地判题节点，领取租约并调用同机 go-judge。
- `shared`：判题、计分、排名和 AI 状态机公共契约。
- `web`：Vue 3 用户端与管理员端。
- `crawler-cli`：`SourceAdapter -> CanonicalProblem -> 标准 ZIP` 采集框架。
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

## Spring AI

可以使用 Spring AI。项目锁定 Spring AI 2.0.0，并通过 OpenAI 兼容 Provider 接入。默认完全关闭模型调用；启用时设置：

```bash
GZU_OJ_AI_ENABLED=true
GZU_OJ_AI_CHAT_PROVIDER=openai
GZU_OJ_AI_BASE_URL=https://api.openai.com/v1
GZU_OJ_AI_API_KEY=...
GZU_OJ_AI_MODEL=gpt-4.1-mini
```

模型仅生成候选分析、标程、生成器和测试计划。标准输出必须由已通过差分校验的标程在 go-judge 中计算，发布仍受固定种子、双标程/暴力差分、资源余量和制品哈希门禁约束。

## 部署入口

复制 `.env.example` 为 `.env` 并替换所有 Token 和密码，然后选择拓扑：

```bash
docker compose -f infra/compose/compose.control.yml up -d --build
docker compose -f infra/compose/compose.judge.yml up -d --build
docker compose -f infra/compose/compose.all-in-one.yml up -d --build
```

详细说明见 [架构](docs/architecture.md)、[部署](docs/deployment.md)、[运维](docs/operations.md) 和 [备份恢复](docs/backup-restore.md)。
