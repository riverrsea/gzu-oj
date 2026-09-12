# gzu-oj-crawler

GZU OJ 的题库采集与标准导入包生成 CLI，是 Kotlin 版 `crawler-cli` 的 Python 迁移实现。

## 能力

| 子命令 | 作用 |
| --- | --- |
| `local <canonical-problems.json> <output.zip>` | 把本地规范 JSON 转成标准导入包 |
| `noobdream-import <details.csv> <output.zip> [--default-year YYYY]` | 把详情 CSV 转成无测试点导入包 |
| `noobdream-list <list-url> <output.csv> [--single-page]` | 采集 N 诺题库列表 |
| `noobdream-problems <list-url> <output.csv> [--single-page]` | 采集 N 诺题目详情（含完整题面） |
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

# 转成标准导入包
uv run gzu-oj-crawler noobdream-import \
  /absolute/noobdream-problems.csv /absolute/noobdream-import.zip --default-year 2025
```

登录配置从项目根目录的 `.env.crawler` 读取，支持 `user_name` / `user_password`
以及兼容别名 `noobdream_account` / `noobdream_pwd`。该文件已被 `.gitignore` 忽略。

## 测试

```bash
cd crawler
uv run pytest
```
