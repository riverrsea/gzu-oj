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

列表采集和题面采集都使用项目根目录的 `.env.crawler` 登录配置。该文件已被 `.gitignore` 忽略，不要提交账号、密码或 Cookie。支持的键名为 `login_url`、`logout_url`、`user_name`、`user_password`；当前项目中已有的 `noobdream_account`、`noobdream_pwd` 也兼容。登录成功后，列表和题面请求都会携带 `csrftoken`、`sessionid`，采集结束后使用 POST 请求登出。

只采集全部公开分页的列表元数据：

```bash
gradle :crawler-cli:run --args='noobdream-list https://noobdream.com/DreamJudge/Issue/page/0/ /absolute/noobdream-list.csv'
```

采集登录后题面和公开样例，输出原始详情 CSV；不完整字段保留为空：

```bash
gradle :crawler-cli:run --args='noobdream-problems https://noobdream.com/DreamJudge/Issue/page/0/ /absolute/noobdream-problems.csv'
```

调试时可以在两个命令末尾追加 `--single-page`。详情 CSV 不是管理员批量导入 ZIP；标准导入仍需通过 `problems.csv`、`statements/` 和可选 `tests/` 目录组织 ZIP，并补齐学校、年份和测试点。

本地规范 JSON 转标准导入 ZIP 仍可使用：

```bash
gradle :crawler-cli:run --args='local /absolute/problems.json /absolute/import.zip'
```

已采集的 noobdream 详情 CSV 可转换为不含测试点的标准导入 ZIP。CSV 中的 `school`、`year` 等必填字段必须完整；缺失年份可以用参数统一补充，转换器不会覆盖 CSV 中已有年份：

```bash
gradle :crawler-cli:run --args='noobdream-import /absolute/noobdream-problems.csv /absolute/noobdream-import.zip --default-year 2025'
```

转换后的 ZIP 只包含 `problems.csv` 和 `statements/`，导入后题目为草稿，测试点在管理员编辑页面中继续录入。
