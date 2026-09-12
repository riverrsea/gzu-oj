"""N 诺题目详情页的解析、抓取与 CSV / 单题 Markdown 写出。

题面里的公式在服务器返回的 HTML 中是原始 TeX 源码（MathJax 只在浏览器端渲染），
因此这里用 :mod:`gzu_oj_crawler.html_markdown` 做结构化转换，
把 ``$...$`` / ``\\(...\\)`` 公式连同换行、列表、图片一起保留下来。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import httpx
from bs4 import BeautifulSoup, Tag

from .auth import USER_AGENT
from .csv_io import write_csv
from .errors import fail, require
from .html_markdown import normalized_text, plain_text, to_markdown
from .http_client import build_client
from .list_crawler import (
    NoobDreamListItem,
    extract_noobdream_school,
    extract_noobdream_year,
)

#: 详情页请求头。
_DETAIL_HEADERS = {
    "Accept": "text/html,application/xhtml+xml",
    "Accept-Language": "zh-CN,zh;q=0.9",
    "User-Agent": USER_AGENT,
}

#: 只给题号时使用的站点详情页地址模板。
_DETAIL_URL_TEMPLATE = "https://noobdream.com/DreamJudge/Issue/page/{problem_id}/"

#: 从详情页地址里提取题号。
_DETAIL_PATH_REGEX = re.compile(r"/DreamJudge/Issue/page/(?P<problem_id>\d+)/?")

#: 单题参数无法识别时的统一提示，引导用户改用题号。
_REFERENCE_HINT = "无法从「{reference}」识别题号，请使用题号（如 5382）或详情页地址"

#: 页面限制文本的兼容匹配。
_LIMIT_REGEX = re.compile(r"Time\s*Limit\s*:\s*(\d+)\s*ms", re.IGNORECASE)

#: 页面内存限制文本的兼容匹配。
_MEMORY_REGEX = re.compile(r"Memory\s*Limit\s*:\s*(\d+)\s*mb", re.IGNORECASE)

#: 详情 CSV 的固定表头。
PROBLEM_HEADERS = (
    "externalKey",
    "problemId",
    "title",
    "school",
    "year",
    "difficulty",
    "problemType",
    "timeLimitMs",
    "memoryLimitMiB",
    "statementMarkdown",
    "inputDescription",
    "outputDescription",
    "sampleInput",
    "sampleOutput",
    "sourceUrl",
)


@dataclass(slots=True)
class NoobDreamProblem:
    """已登录题目详情页中供人工复核和后续导入转换使用的题目数据。"""

    #: 系统外部题目标识。
    external_key: str
    #: 外部题号。
    problem_id: str
    #: 题目标题。
    title: str
    #: 列表页难度。
    difficulty: str
    #: 列表页题型。
    problem_type: str
    #: 从题目来源中识别出的学校名；无法识别时为空。
    school: str | None
    #: 来源中明确标注的年份；未标注时为空。
    year: int | None
    #: 时间限制，单位毫秒。
    time_limit_ms: int | None
    #: 内存限制，单位 MiB。
    memory_limit_mib: int | None
    #: 规范化后的 Markdown 题面。
    statement_markdown: str
    #: 输入描述（Markdown）。
    input_description: str
    #: 输出描述（Markdown）。
    output_description: str
    #: 公开输入样例。
    sample_input: str | None
    #: 公开输出样例。
    sample_output: str | None
    #: 题目详情页地址，也是导入时的来源地址。
    source_url: str


class NoobDreamProblemParser:
    """解析登录后的 N 诺题目详情页。"""

    def parse(self, html: str, item: NoobDreamListItem) -> NoobDreamProblem:
        """从一条列表记录对应的详情 HTML 构造题目数据。"""
        document = BeautifulSoup(html, "html.parser")
        title_node = document.select_one(".title-container h1, h1")
        title = (normalized_text(title_node) if title_node else "") or item.title

        limits = next(
            (
                node
                for node in document.select(".bg-light")
                if "time limit" in node.get_text().lower() and "memory limit" in node.get_text().lower()
            ),
            None,
        )
        limit_text = limits.get_text() if limits else ""
        time_limit_ms = _first_int(_LIMIT_REGEX, limit_text)
        memory_limit_mib = _first_int(_MEMORY_REGEX, limit_text)

        description_node = document.select_one(".OjInfo")
        description = to_markdown(description_node, item.detail_url) if description_node else ""
        input_description = self._section_text(document, "输入描述", item.detail_url)
        output_description = self._section_text(document, "输出描述", item.detail_url)
        sample_input = _sample_text(document, "pre#input")
        sample_output = _sample_text(document, "pre#output")
        source_description = _source_text(document) or item.source_description

        return NoobDreamProblem(
            external_key=item.external_key,
            problem_id=item.problem_id,
            title=title,
            difficulty=item.difficulty,
            problem_type=item.problem_type,
            # 详情页来源更完整，优先使用；缺失时回退到列表页识别结果。
            school=extract_noobdream_school(source_description) or item.school,
            year=extract_noobdream_year(source_description),
            time_limit_ms=time_limit_ms,
            memory_limit_mib=memory_limit_mib,
            statement_markdown=_build_markdown(
                title=title,
                description=description,
                input_description=input_description,
                output_description=output_description,
                sample_input=sample_input,
                sample_output=sample_output,
                source_description=source_description,
            ),
            input_description=input_description,
            output_description=output_description,
            sample_input=sample_input,
            sample_output=sample_output,
            source_url=item.detail_url,
        )

    def _section_text(self, document: BeautifulSoup, heading: str, base_url: str) -> str:
        """按标题定位输入或输出描述所在的内容块。"""
        for block in document.select(".Problem-content"):
            header = block.select_one("h6")
            if header and plain_text(header).startswith(heading):
                content = block.select_one(".pre-style, pre, article")
                return to_markdown(content, base_url) if content else ""
        return ""


class NoobDreamProblemClient:
    """使用登录 Cookie 顺序抓取题目详情。"""

    def __init__(
        self,
        cookie_header: str,
        client: httpx.Client | None = None,
        parser: NoobDreamProblemParser | None = None,
    ) -> None:
        """注入登录 Cookie、HTTP 客户端和解析器。

        详情请求不自动跟随登录重定向，避免把登录页误当题面。
        """
        self._cookie_header = cookie_header
        self._client = client or build_client(follow_redirects=False)
        self._parser = parser or NoobDreamProblemParser()

    def fetch(self, item: NoobDreamListItem) -> NoobDreamProblem:
        """请求并解析一条题目详情。"""
        headers = dict(_DETAIL_HEADERS)
        headers["Cookie"] = self._cookie_header
        response = self._client.get(item.detail_url, headers=headers)
        status = response.status_code
        if not 200 <= status <= 299:
            if 300 <= status <= 399:
                fail(f"题目 {item.problem_id} 被重定向，登录 Cookie 可能已失效")
            fail(f"题目 {item.problem_id} 请求失败：HTTP {status}")
        return self._parser.parse(response.text, item)

    def fetch_all(self, items: list[NoobDreamListItem]) -> list[NoobDreamProblem]:
        """顺序抓取题目，避免对目标站点造成并发压力。"""
        problems: list[NoobDreamProblem] = []
        for index, item in enumerate(items):
            problems.append(self.fetch(item))
            print(f"已抓取题目 {index + 1}/{len(items)}：{item.problem_id}")
        return problems


def write_problem_csv(problems: list[NoobDreamProblem], target: Path) -> None:
    """将题目详情写为一个带完整题面的 UTF-8 CSV。"""
    require(problems, "没有可写入 CSV 的题目")
    write_csv(
        target,
        PROBLEM_HEADERS,
        [
            (
                problem.external_key,
                problem.problem_id,
                problem.title,
                problem.school or "",
                "" if problem.year is None else problem.year,
                problem.difficulty,
                problem.problem_type,
                "" if problem.time_limit_ms is None else problem.time_limit_ms,
                "" if problem.memory_limit_mib is None else problem.memory_limit_mib,
                problem.statement_markdown,
                problem.input_description,
                problem.output_description,
                problem.sample_input or "",
                problem.sample_output or "",
                problem.source_url,
            )
            for problem in problems
        ],
    )


def resolve_problem_reference(reference: str) -> tuple[str, str]:
    """把题号或详情页地址解析成 ``(题号, 详情页地址)``。

    支持两种写法：纯题号 ``5382``，或任意镜像上的详情页地址
    ``https://noobdream.com/DreamJudge/Issue/page/5382/``。传入地址时保留原样，
    方便对着测试环境或镜像站做单题体检。
    """
    text = reference.strip()
    require(text, "题号不能为空")
    if re.fullmatch(r"\d+", text):
        return text, _DETAIL_URL_TEMPLATE.format(problem_id=text)
    parsed = urlparse(text)
    if parsed.scheme:
        require(parsed.scheme in {"http", "https"}, "题目地址必须是 HTTP(S) URL")
        match = _DETAIL_PATH_REGEX.search(parsed.path)
        require(match is not None, _REFERENCE_HINT.format(reference=reference))
        return match.group("problem_id"), text
    fail(_REFERENCE_HINT.format(reference=reference))


def build_detail_item(problem_id: str, detail_url: str) -> NoobDreamListItem:
    """为单题采集构造最小列表记录。

    难度和题型只存在于列表页，单题模式下拿不到，因此留空；
    标题、学校、年份和来源由详情页解析补全，不影响题面输出。
    """
    return NoobDreamListItem(
        external_key=f"noobdream:{problem_id}",
        problem_id=problem_id,
        title="",
        difficulty="",
        problem_type="",
        school=None,
        source_description=None,
        detail_url=detail_url,
    )


def write_statement(problem: NoobDreamProblem, target: Path) -> None:
    """把单题题面写成 Markdown，并在末尾补一个换行。"""
    normalized = target.absolute()
    normalized.parent.mkdir(parents=True, exist_ok=True)
    normalized.write_text(problem.statement_markdown + "\n", encoding="utf-8")


def _build_markdown(
    *,
    title: str,
    description: str,
    input_description: str,
    output_description: str,
    sample_input: str | None,
    sample_output: str | None,
    source_description: str | None,
) -> str:
    """生成后续人工校验和导入都能读取的 Markdown 题面。"""
    lines = [
        f"# {title}",
        "",
        "## 题目描述",
        description,
        "",
        "## 输入描述",
        input_description,
        "",
        "## 输出描述",
        output_description,
    ]
    if sample_input or sample_output:
        lines.extend(["", "## 输入输出样例"])
        if sample_input:
            lines.extend(["", "输入：", *_fenced(sample_input)])
        if sample_output:
            lines.extend(["", "输出：", *_fenced(sample_output)])
    if source_description:
        lines.extend(["", "## 题目来源", source_description])
    return "\n".join(lines).strip()


def _fenced(content: str) -> list[str]:
    """用代码块包裹样例；样例自身含反引号时自动加长围栏。"""
    longest = max((len(run) for run in re.findall(r"`+", content)), default=0)
    fence = "`" * max(3, longest + 1)
    return [f"{fence}text", content, fence]


def _sample_text(document: BeautifulSoup, selector: str) -> str | None:
    """读取公开样例；空白内容视为缺失。"""
    node = document.select_one(selector)
    if node is None:
        return None
    text = plain_text(node)
    return text or None


def _source_text(document: BeautifulSoup) -> str | None:
    """读取题目来源标题后的第一个 pre。"""
    heading = document.find("h5", string=lambda text: bool(text) and text.strip() == "题目来源")
    if not isinstance(heading, Tag):
        return None
    sibling = heading.find_next_sibling()
    if not isinstance(sibling, Tag):
        return None
    node = sibling.select_one("pre")
    if node is None:
        return None
    return plain_text(node) or None


def _first_int(pattern: re.Pattern[str], text: str) -> int | None:
    """取正则第一个捕获组的整数，未匹配返回 ``None``。"""
    match = pattern.search(text)
    return int(match.group(1)) if match else None
