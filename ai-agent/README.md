# GZU OJ AI Agent

该服务只负责模型调用和 LangGraph 编排，不读取 OJ 业务数据库。Kotlin API 负责管理员鉴权、沙箱队列和最终落库。

```bash
cp .env.example .env
uv sync
uv run uvicorn gzu_oj_agent.app:app --host 127.0.0.1 --port 8090
```

检查点数据库需要预先创建，Agent 启动时只创建 LangGraph 自身表。Ollama 必须已经存在配置的模型，启动检查不会自动拉取模型。
