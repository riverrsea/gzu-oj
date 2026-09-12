"""N 诺题库列表页的解析、抓取与 CSV 写出。

列表页是公开页面，但站点要求登录后才能进入详情页；这里与 Kotlin 版一致，
登录后携带 Cookie 抓取，并按 ``?page=N`` 顺序翻页，避免并发压力。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urljoin, urlparse

import httpx
from bs4 import BeautifulSoup, Comment, NavigableString, Tag

from .auth import USER_AGENT
from .csv_io import write_csv
from .errors import require
from .http_client import build_client

#: 列表页请求头。
_LIST_HEADERS = {
    "Accept": "text/html,application/xhtml+xml",
    "Accept-Language": "zh-CN,zh;q=0.9",
    "User-Agent": USER_AGENT,
}

#: 详情页链接选择器；站点地址形如 ``/DreamJudge/Issue/page/1006/``。
_DETAIL_LINK_SELECTOR = 'a[href*="/DreamJudge/Issue/page/"]'

#: 学校名称常见结尾；允许一个来源同时包含多所学校。
_SCHOOL_REGEX = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbfA-Za-z0-9]+?(?:大学|学院|研究所|科学院)")

#: 来源文字中的四位年份。
_YEAR_REGEX = re.compile(r"(?:19|20)\d{2}")

#: 列表 CSV 的固定表头。
LIST_HEADERS = (
    "externalKey",
    "problemId",
    "title",
    "difficulty",
    "problemType",
    "school",
    "detailUrl",
)


@dataclass(slots=True)
class NoobDreamListItem:
    """N 诺题库列表中的一行；详情页地址供后续登录采集使用。"""

    #: 系统外部题目标识，使用来源命名空间避免与其他网站题号冲突。
    external_key: str
    #: 外部题号，保留网站原始数字字符串。
    problem_id: str
    #: 题目标题。
    title: str
    #: 网站展示的难度文本，例如“简单”。
    difficulty: str
    #: 网站展示的题型文本，例如“简单模拟”。
    problem_type: str
    #: 从来源文字中识别出的学校名；无法识别时为空。
    school: str | None
    #: 网站原始来源文字，仅用于详情补全和学校、年份提取。
    source_description: str | None
    #: 详情页绝对地址。
    detail_url: str


@dataclass(slots=True)
class NoobDreamListPage:
    """一个列表页的题目和站点声明的总页数。"""

    #: 当前页题目。
    items: list[NoobDreamListItem]
    #: 当前筛选条件下的总页数。
    total_pages: int


class NoobDreamListParser:
    """解析 N 诺题库列表页，不进入需要登录的题目详情页。"""

    def parse(self, html: str, page_uri: str) -> list[NoobDreamListItem]:
        """从 HTML 表格中解析题目行。"""
        return self.parse_page(html, page_uri).items

    def parse_page(self, html: str, page_uri: str) -> NoobDreamListPage:
        """同时解析题目行和列表页的分页总数。"""
        document = BeautifulSoup(html, "html.parser")
        rows = document.select("table tbody tr")
        require(rows, f"列表页没有找到题目表格：{page_uri}")
        items: list[NoobDreamListItem] = []
        for index, row in enumerate(rows):
            cells = row.select("td")
            require(len(cells) >= 5, f"第 {index + 1} 个题目行字段不足：{page_uri}")
            title_anchor = cells[2].select_one(_DETAIL_LINK_SELECTOR)
            require(title_anchor is not None, f"第 {index + 1} 个题目行没有详情链接：{page_uri}")
            detail_url = urljoin(page_uri, str(title_anchor.get("href")))
            problem_id = _collapsed_text(cells[1])
            require(re.fullmatch(r"[0-9]+", problem_id), f"题号不是数字：{problem_id}")
            title = _own_text(title_anchor)
            require(title, f"题目标题为空：{problem_id}")
            source_node = cells[2].select_one(".tag-source")
            source_description = _collapsed_text(source_node) if source_node else ""
            difficulty_node = cells[3].select_one(".level-tag")
            items.append(
                NoobDreamListItem(
                    external_key=f"noobdream:{problem_id}",
                    problem_id=problem_id,
                    title=title,
                    difficulty=_collapsed_text(difficulty_node) if difficulty_node else "",
                    problem_type=_collapsed_text(cells[4]),
                    school=extract_noobdream_school(source_description or None),
                    source_description=source_description or None,
                    detail_url=detail_url,
                ),
            )
        require(
            len({item.problem_id for item in items}) == len(items),
            f"列表页包含重复题号：{page_uri}",
        )
        page_numbers = [
            number
            for node in document.select(".page-num .page-link")
            if (number := _to_int(_collapsed_text(node))) is not None
        ]
        return NoobDreamListPage(items=items, total_pages=max(page_numbers, default=1))


class NoobDreamListClient:
    """通过 httpx 请求公开的 N 诺题库列表页。"""

    def __init__(
        self,
        client: httpx.Client | None = None,
        parser: NoobDreamListParser | None = None,
        cookie_header: str | None = None,
    ) -> None:
        """注入 HTTP 客户端、解析器和登录 Cookie，便于离线测试。"""
        self._client = client or build_client(follow_redirects=True)
        self._parser = parser or NoobDreamListParser()
        self._cookie_header = cookie_header

    def fetch(self, page_uri: str) -> list[NoobDreamListItem]:
        """请求并解析一个列表页；配置会话时携带登录 Cookie。"""
        return self._request_page(page_uri).items

    def fetch_all(self, first_page_uri: str) -> list[NoobDreamListItem]:
        """从第一页开始顺序抓取当前筛选条件下的全部分页。"""
        first_page = self._request_page(first_page_uri)
        all_items = list(first_page.items)
        for page_number in range(2, first_page.total_pages + 1):
            all_items.extend(self._request_page(_page_uri(first_page_uri, page_number)).items)
        deduplicated: list[NoobDreamListItem] = []
        seen: set[str] = set()
        for item in all_items:
            if item.problem_id not in seen:
                seen.add(item.problem_id)
                deduplicated.append(item)
        require(deduplicated, f"全部分页没有采集到题目：{first_page_uri}")
        return deduplicated

    def _request_page(self, page_uri: str) -> NoobDreamListPage:
        """请求并解析单个页面，同时读取该筛选条件的分页总数。"""
        require(urlparse(page_uri).scheme in {"http", "https"}, "列表地址必须是 HTTP(S) URL")
        headers = dict(_LIST_HEADERS)
        if self._cookie_header:
            headers["Cookie"] = self._cookie_header
        response = self._client.get(page_uri, headers=headers)
        require(response.status_code < 300, f"列表页请求失败：HTTP {response.status_code} {page_uri}")
        return self._parser.parse_page(response.text, page_uri)


def write_list_csv(items: list[NoobDreamListItem], target: Path) -> None:
    """把列表采集结果写为单个 UTF-8 CSV 文件。"""
    require(items, "没有可写入 CSV 的题目")
    write_csv(
        target,
        LIST_HEADERS,
        [
            (
                item.external_key,
                item.problem_id,
                item.title,
                item.difficulty,
                item.problem_type,
                item.school or "",
                item.detail_url,
            )
            for item in items
        ],
    )


def extract_noobdream_school(source_description: str | None) -> str | None:
    """从来源文字中提取一个或多个学校名；“真题”等站点标签不会作为学校。"""
    if not source_description or not source_description.strip():
        return None
    schools = []
    for match in _SCHOOL_REGEX.finditer(source_description):
        name = match.group().strip()
        if name and name not in schools:
            schools.append(name)
    return "/".join(schools) if schools else None


def extract_noobdream_year(source_description: str | None) -> int | None:
    """从来源文字中提取明确标注的年份，未标注时为空。"""
    if not source_description:
        return None
    match = _YEAR_REGEX.search(source_description)
    return int(match.group()) if match else None


def parse_noobdream_list_html(html: str, page_uri: str) -> list[NoobDreamListItem]:
    """读取 HTML 文本并复用同一解析器，供离线测试和人工复核使用。"""
    return NoobDreamListParser().parse(html, page_uri)


def _page_uri(first_page_uri: str, page_number: int) -> str:
    """构造下一页 URL，保留原有筛选条件并覆盖 page 参数。"""
    without_fragment = first_page_uri.split("#", 1)[0]
    base = without_fragment.split("?", 1)[0]
    query = without_fragment.partition("?")[2]
    parts = [
        part
        for part in query.split("&")
        if part and part.split("=", 1)[0].lower() != "page"
    ]
    parts.append(f"page={page_number}")
    return f"{base}?{'&'.join(parts)}"


def _own_text(anchor: Tag) -> str:
    """读取元素的直接文本，不含子元素文本。

    对应 jsoup 的 ``ownText()``：标题链接里还嵌着“真题”“贵州大学机试题”等标签，
    取直接文本才能得到干净的题名。
    """
    direct = (
        child
        for child in anchor.children
        if isinstance(child, NavigableString) and not isinstance(child, Comment)
    )
    return " ".join("".join(str(child) for child in direct).split())


def _collapsed_text(element: Tag) -> str:
    """取元素文本并把连续空白压缩为单个空格。"""
    return " ".join(element.get_text().split())


def _to_int(value: str) -> int | None:
    """把纯数字文本转成整数，失败返回 ``None``。"""
    return int(value) if re.fullmatch(r"\d+", value) else None
