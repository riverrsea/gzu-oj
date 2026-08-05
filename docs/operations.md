# 运维说明

## 日常检查

- API 健康：`GET /actuator/health` 返回 `UP`。
- Worker：`worker_node.last_heartbeat_at` 持续更新，能力包含 CPU、memory、pids 和 no-fallback。
- 队列：监控 `judge_job` 各状态数量、最老排队时间、基础设施重试次数和过期租约。
- 性能：非判题 API p95 目标小于 500 ms；基线为同一公开赛 5 人、瞬时 10 份提交、每份 20 个测试点。
- 存储：同时监控 PostgreSQL、ArtifactStore、Docker `/dev/shm` 和备份容量。

## 故障处理

停止 Worker 不会丢任务。基础设施失败最多重试三次，CE、WA、TLE、MLE、RE 和 OLE 不重试。Worker 恢复后会继续领取排队或租约过期任务。迟到完成请求会因租约不匹配被拒绝。

判题异常时先停止 Worker，再检查 go-judge 日志和 cgroup 控制器。不要通过关闭 `-no-fallback` 绕过预检。发生数据不一致时停止写入，保留数据库和 ArtifactStore 现场，再从同一备份点恢复。

## 升级

升级前完成并验证一份配套备份。依次在开发分支运行 Kotlin 测试、前端构建、数据库迁移和端到端测试；再构建固定版本镜像。生产升级先控制端后 Worker，观察队列租约和旧 Worker 的协议兼容性。

## 爬虫 CLI

首版仅支持本地规范 JSON，不主动抓取网站：

```bash
gradle :crawler-cli:run --args='local /absolute/problems.json /absolute/import.zip'
```

具体网站适配器必须在目标 URL、访问许可和页面结构明确后新增。
