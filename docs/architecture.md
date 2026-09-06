# 架构说明

## 运行边界

```text
浏览器 -> Caddy/HTTPS -> Vue + API -> PostgreSQL
                                   -> LocalArtifactStore
                                   -> Python AI Agent -> Agent PostgreSQL

独立 WSL Worker --出站 HTTPS/任务租约--> API
独立 WSL Worker -> 内部 REST -> go-judge -> cgroup v2 沙箱
```

API 是模块化单体，不执行用户代码，也不访问判题主机。PostgreSQL 同时保存业务数据、Spring Session 和 `judge_job` 持久化队列。隐藏测试数据只写入 `LocalArtifactStore`，数据库保存存储键、SHA-256、大小和引用关系。

Worker 不连接数据库。它使用可撤销 Bearer Token 通过 15 秒长轮询领取任务，每 20 秒续租；租约默认 60 秒。领取使用短事务和 `FOR UPDATE SKIP LOCKED`，判题期间不持有行锁。完成结算由 `attemptId + leaseToken` 幂等保护，旧租约不能覆盖新结果。

## 题目与产品语义

题目内容不可变。题面、资源限制、测试数据、标程或分值变化都创建新版本；比赛和个人计时套卷锁定具体版本。未满分提交进入错题本，满分后标记已解决，但历史提交、收藏和旧记录保留。

公开比赛采用 OI 计分：每题取比赛期间最高分，总分降序；同分按达到最终总分的比赛用时升序。进行中只返回本人得分，结束后返回用户名、总分和用时，不返回源码。

## 判题语义

首版支持 GNU C17、GNU C++17、OpenJDK 21 和 CPython 3。每份提交只编译一次，测试点顺序执行。文本比较统一换行符、删除行尾空白和文件末尾空行，保留行内空格与大小写。

Worker 启动前真实探测 cgroup v2 的 CPU、memory 和 pids 控制器，并要求 go-judge `-no-fallback`。源码上限为 128 KiB，单测试点输出上限为 16 MiB；用户代码无网络，进程数、打开文件、临时文件系统、CPU、墙钟和内存均受限。

## AI 边界

Python Agent 负责模型调用、Prompt、结构化输出和 LangGraph 检查点；Kotlin API 不引入模型 SDK，也不读取 Agent 数据库。API 只通过带 Bearer Token 的内部接口传递题面快照、进度事件和沙箱结果。每步保存模型、提示词版本、结构化输出、源码、种子、哈希、费用和错误。

状态从 `DRAFT` 依次进入分析、双标程、审查、测试生成、差分和验证；任何一步可进入人工处理、失败或取消。AI 数据始终标记为“练习数据，非官方原始数据”。
