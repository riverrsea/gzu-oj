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

Python 版爬虫位于 `crawler/`（包名 `gzu-oj-crawler`），与 Kotlin 版 `crawler-cli` 输出契约完全一致，
迁移期间两者并存。日常采集使用 Python 版：

```bash
cd crawler
uv sync
uv run gzu-oj-crawler --help
```

列表采集和题面采集都使用项目根目录的 `.env.crawler` 登录配置。该文件已被 `.gitignore` 忽略，不要提交账号、密码或 Cookie。支持的键名为 `login_url`、`logout_url`、`user_name`、`user_password`；当前项目中已有的 `noobdream_account`、`noobdream_pwd` 也兼容。登录成功后，列表和题面请求都会携带 `csrftoken`、`sessionid`，采集结束后使用 POST 请求登出。

只采集全部公开分页的列表元数据：

```bash
uv run gzu-oj-crawler noobdream-list https://noobdream.com/DreamJudge/Issue/page/0/ /absolute/noobdream-list.csv
```

采集登录后题面和公开样例，输出原始详情 CSV；不完整字段保留为空：

```bash
uv run gzu-oj-crawler noobdream-problems https://noobdream.com/DreamJudge/Issue/page/0/ /absolute/noobdream-problems.csv
```

调试时可以在两个命令末尾追加 `--single-page`。详情 CSV 不是管理员批量导入 ZIP；标准导入仍需通过 `problems.csv`、`statements/` 和可选 `tests/` 目录组织 ZIP，并补齐学校、年份和测试点。

单题体检：只抓一道题并写出题面 Markdown，用于确认登录、解析和公式都没问题。参数支持题号或详情页地址，命令也会回报公式定界符数量。注意题库第一页几乎不含公式，验证公式请用 `5382`、`10102`、`10298` 这类题号：

```bash
uv run gzu-oj-crawler noobdream-problem 5382 /absolute/p5382.md
```

本地规范 JSON 转标准导入 ZIP：

```bash
uv run gzu-oj-crawler local /absolute/problems.json /absolute/import.zip
```

已采集的 noobdream 详情 CSV 可转换为不含测试点的标准导入 ZIP。CSV 中缺失的学校会转换为占位值 `未注明`，缺失年份默认转换为 `1900`；转换器不会覆盖 CSV 中已有年份。需要其他占位年份时可显式传入 `--default-year`：

```bash
uv run gzu-oj-crawler noobdream-import /absolute/noobdream-problems.csv /absolute/noobdream-import.zip
uv run gzu-oj-crawler noobdream-import /absolute/noobdream-problems.csv /absolute/noobdream-import.zip --default-year 2025
```

转换后的 ZIP 只包含 `problems.csv` 和 `statements/`，导入后题目为草稿，测试点在管理员编辑页面中继续录入。
源站的 `简单/中等/困难`（包括 `+/-` 后缀）会映射为项目难度。旧题中超出 MiB 范围的 KiB 数值会按 1024 换算，页面拼接值会保留合法的 MiB 前缀，低于系统下限的正数会提升到 16 MiB。

### 题面公式的处理

源站用 MathJax 3 在浏览器端渲染公式，服务器返回的 HTML 里保存的是**原始 TeX 源码**，因此采集不会丢公式。
真正需要保证的是提取链路不破坏它：

- 站点的公式定界符有 `$...$`、`\(...\)`、`$$...$$`、`\[...\]` 四种，Python 版统一归一化为 `$...$` 与 `$$...$$`；
- 提取时把公式替换成占位符，避免 Markdown 转换器转义 `_`、`*` 而破坏 `a_1`、`\le` 之类的写法；
- `HTML→Markdown` 转换会保留 `<br>` 换行、列表、链接和图片（图片补成绝对地址），并剔除站点在每个公式容器前留下的注释副本；
- 正文里会被误当成 HTML 标签的 `<` 会被转义成 `&lt;`，公式内部的 `<` 保持原样交给 KaTeX。

前端 `web/` 统一使用 `marked` + `marked-katex-extension` + KaTeX 渲染，并放开 DOMPurify 的
`mathMl` 标签集与 `<semantics>`/`<annotation>`，否则入库的 LaTeX 只会原样显示源码。

Kotlin 版 `crawler-cli` 保留用于回归对照，命令形式与上面对应（把 `uv run gzu-oj-crawler` 换成
`gradle :crawler-cli:run --args='...'`）。注意它使用 jsoup `wholeText()` 取纯文本，会丢失换行与
列表结构，采集题面请优先使用 Python 版。
