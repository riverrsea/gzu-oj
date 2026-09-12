# gzu-oj-crawler

GZU OJ 的题库采集与标准导入包生成 CLI，是 Kotlin 版 `crawler-cli` 的 Python 迁移实现。

## 能力

| 子命令 | 作用 |
| --- | --- |
| `local <canonical-problems.json> <output.zip>` | 把本地规范 JSON 转成标准导入包 |
| `noobdream-import <details.csv> <output.zip> [--default-year YYYY] [--no-sample-test]` | 把详情 CSV 转成标准导入包（默认把公开样例写成唯一测试点） |
| `noobdream-list <list-url> <output.csv> [--single-page] [--school 学校名]` | **只**抓列表页：题号、标题、难度、题型、学校 + `detailUrl`，**不含题面** |
| `noobdream-problems <list-url> <output.csv> [--single-page] [--school 学校名]` | 列表 + 逐题详情，CSV 带完整题面（会逐题发请求，慢） |
| `noobdream-problem <题号或地址> <output.md>` | 单题体检：只写题面 Markdown，便于核对公式 |

输出契约与 Kotlin 版完全一致：CSV 表头、ZIP 结构（`problems.csv` + `statements/` + 可选 `tests/`）
都保持原样，可以直接喂给 `POST /api/admin/imports` 的暂存导入流程。

## 公式处理

目标站点用 **MathJax 3 在浏览器端渲染公式**，服务器返回的 HTML 中是原始 TeX 源码，
所以采集不会丢公式。真正的风险在于提取方式：

* Kotlin 版用 jsoup `wholeText()` 取纯文本，会丢掉 `<br>` 换行、段落、列表和图片；
* 站点 MathJax 支持 `$...$`、`\(...\)`、`$$...$$`、`\[...\]` 四种定界符，需要归一化。

Python 版的做法是：

1. 先用 BeautifulSoup 去掉脚本、样式和注释（站点在每个 `.mathjax-process` 前留了一份注释副本）；
2. 把每个文本节点里的公式替换成占位符，避免 Markdown 转换器转义 `_`、`*` 破坏 `a_1`；
3. 用 markdownify 把 HTML 转成 Markdown，保留换行、列表、链接和图片（图片地址补成绝对地址）；
4. 还原占位符，并把 `\(...\)` / `\[...\]` 统一成 `$...$` / `$$...$$`。

还有一类**不是 MathJax** 的公式写法需要单独处理：站点部分题面用 HTML 上标写指数，
例如题目 1017 的 `X<sup>N</sup>%233333`。Markdown 和 GFM 都没有上标语法，
markdownify 默认会把 `<sup>` 标签丢掉只留文本，`X<sup>N</sup>` 就变成 `XN`——
语义从"X 的 N 次方"变成"变量 XN"，题面直接读错。因此转换器显式保留
`<sup>` / `<sub>` 标签，交给 marked + DOMPurify 正常渲染。

> 注意 MathJax 脚本是站点模板 **全局加载** 的，每个页面都有，所以"页面里有 MathJax"
> 不等于"题面用了 LaTeX"。判断依据应该是题面里有没有 `$` / `\(`，而不是有没有 MathJax。

> 配套改动：前端 `web/` 使用 KaTeX 渲染 `$...$` / `$$...$$`，否则入库的公式只会原样显示源码。

### 怎么验证公式没被破坏

注意题库第一页大多是入门题，**几乎不含公式**（数学符号用的是 `≤` 这类 Unicode 字符），
拿第一页测不出问题。已知能覆盖各种写法的题号：

| 题号 | 覆盖的写法 |
| --- | --- |
| `5382` | `$...$` 行内公式、`\le`、`a_{ij}`、`<br>` 换行与列表 |
| `10102` | 公式紧贴中文（`边长$a,b,c$`）、`\frac`、`\sqrt`、行内代码 |
| `10298` | 行内公式与中文混排 |
| `1017` | HTML 上标 `<sup>`（不是 MathJax 语法），最容易读错语义 |

```bash
uv run gzu-oj-crawler noobdream-problem 1017 /tmp/p1017.md
cat /tmp/p1017.md
```

预期输出里 `X<sup>N</sup>%233333`、`X<sup>30</sup>` 原样保留；
`5382` 则能看到 `$m$`、`$|a-b|$`、`$2 \le m \le 10^4$`，命令也会回报 `公式定界符 36 个` 这类统计。

## 使用

```bash
cd crawler
uv sync

# 采集列表
uv run gzu-oj-crawler noobdream-list \
  https://noobdream.com/DreamJudge/Issue/page/0/ /absolute/noobdream-list.csv

# 单题体检：只看一道题的题面，用来确认公式没有被破坏
uv run gzu-oj-crawler noobdream-problem 5382 /absolute/p5382.md

# 采集题面（需要项目根目录的 .env.crawler）
uv run gzu-oj-crawler noobdream-problems \
  https://noobdream.com/DreamJudge/Issue/page/0/ /absolute/noobdream-problems.csv

# 按学校筛选：只抓贵州大学的真题
uv run gzu-oj-crawler noobdream-list \
  https://noobdream.com/DreamJudge/Issue/page/0/ /absolute/guizhou.csv --school 贵州大学

# 转成标准导入包
uv run gzu-oj-crawler noobdream-import \
  /absolute/noobdream-problems.csv /absolute/noobdream-import.zip --default-year 2025
```

登录配置从项目根目录的 `.env.crawler` 读取，支持 `user_name` / `user_password`
以及兼容别名 `noobdream_account` / `noobdream_pwd`。该文件已被 `.gitignore` 忽略。

### 两个采集命令的区别

这是最容易踩的一个坑：**`noobdream-list` 的 CSV 里没有题面**。

| | `noobdream-list` | `noobdream-problems` |
| --- | --- | --- |
| 请求量 | 只抓列表页（全库约 82 个请求） | 列表页 + 每题一个详情请求（全库约 1700+ 请求） |
| CSV 内容 | 题号、标题、难度、题型、学校、`detailUrl` | 在列表基础上多出 `statementMarkdown` 等完整题面字段 |
| 用途 | 快速盘点题库、筛选出要抓的题号 | 拿题面去生成导入包 |

`detailUrl` 是**详情页链接**，不是题面内容。要题面就用 `noobdream-problems`：

```bash
# 只要清单（快）
uv run gzu-oj-crawler noobdream-list \
  https://noobdream.com/DreamJudge/Issue/page/0/ /absolute/noobdream-list.csv --school 贵州大学

# 要完整题面（慢，逐题请求）
uv run gzu-oj-crawler noobdream-problems \
  https://noobdream.com/DreamJudge/Issue/page/0/ /absolute/noobdream-problems.csv --school 贵州大学
```

### 样例即测试点

`noobdream-import` 默认把题面的**样例**提取出来写进 ZIP 的 `tests/` 目录：
**题面里有几组样例，就写几个测试点**，导入后它们就是题目的测试点，题目立刻可判，
而不是"导入了但没法提交"。

```
tests/problem-1/cases.csv    ordinal,inputPath,outputPath,sample
                             1,1.in,1.out,true
                             2,2.in,2.out,true
tests/problem-1/1.in         第一组样例的输入
tests/problem-1/1.out        第一组样例的输出
tests/problem-1/2.in         第二组样例的输入
tests/problem-1/2.out        第二组样例的输出
```

* 每个测试点带 `sample=true` 标记，前端会当作公开样例展示；
* 测试点**不带分值**：得分由服务端按「通过点数 / 总点数」折算，与通过了哪几个测试点无关；
* 题目没有样例（源站 `pre#input` / `pre#output` 为空）时不生成测试点，仍需人工补数据；
* 用 `--no-sample-test` 可以退回"只写题面、不含测试点"的旧行为。

**样例组数是怎么数出来的**：源站每个题目只有一个 `pre#input` / `pre#output`
（我核对过 33 个页面），多组样例会拼在同一个块里。题目 1002 的输入是 `2 100` / `2 22`
两行、输出是 `20` / `6` 两行，实际是两组样例。多组样例的写法是**每组一行输入对应一行输出**，
所以按「输入输出行数相同且都大于 1」切开；单组样例的输入输出行数通常不相等
（例如"第一行 n、第二行数组"对应"一行答案"），这时整块算一组。

转换完会点名含多组样例的题目，方便复核切分是否符合题意：

```
已把 17/20 道题的题面样例写成测试点（共 21 个，有几组样例就有几个测试点，标记为公开样例）。
以下题目的题面含多组样例，已逐组写成测试点，请复核切分是否符合题意：
  noobdream:1091（4 个）、noobdream:1002（2 个）
```

实测这三道题的结果：

```
noobdream:1006  1 组样例 → 1 个测试点 | in='Guiyang' -> out='gnayiuG'
noobdream:1002  2 组样例 → 2 个测试点 | in='2 100' -> out='20'
                                      | in='2 22'  -> out='6'
noobdream:1091  4 组样例 → 4 个测试点 | in='850'  -> out='discount=1,pay=850'
                                      | in='1230' -> out='discount=0.95,pay=1168.5'
                                      | in='5000' -> out='discount=0.8,pay=4000'
                                      | in='3560' -> out='discount=0.85,pay=3026'
```

判题时按通过点数折算提交得分：1 组全对是 100 分，1002 通过 1 组是 50 分，
1091 通过 3 组是 75 分（四舍五入）。

> **只从题面样例取数据**。转换器不会生成额外测试点，也不会凭空造期望输出——那需要可信的
> 标程，否则产出的测试数据会让正确程序判 WA。需要更多测试点请走项目的 AI 测试点生成流程，
> 导出包的 `tests/` 结构（`cases.csv` + `N.in` / `N.out`）与它是兼容的。

### 按学校筛选

`--school` 对应源站题库侧边栏的“请输入学校全称”筛选框，走的是 `problem_source` 查询参数：

* **服务端筛选**，只抓目标学校的题目。例如 `--school 贵州大学` 只有 2 页 30 题，
  而不是全库 82 页，比抓完再本地过滤省得多；
* 是**包含匹配**，匹配的是源站的“题目来源”原文，因此多校来源的题也会被带上
  （`兰州大学/贵州大学机试` 在 `--school 贵州大学` 下会命中，CSV 里的 `school` 列仍是 `兰州大学/贵州大学`）；
* 翻页会自动保留该条件，不会出现“第一页是贵大、第二页变成全库”的情况；
* 如果筛选条件没有匹配到任何题目，命令会明确提示“筛选条件没有匹配到题目（学校）”，
  而不是报成选择器失效。

也可以直接把手写好的筛选地址传给命令，效果等价：

```bash
uv run gzu-oj-crawler noobdream-list \
  "https://noobdream.com/DreamJudge/Issue/page/0/?problem_source=%E8%B4%B5%E5%B7%9E%E5%A4%A7%E5%AD%A6" \
  /absolute/guizhou.csv
```

`--school` 只是把这个参数拼进地址，两种写法可以混用；同时指定时以 `--school` 为准。

## 测试

```bash
cd crawler
uv run pytest
```
