"""把题面 HTML 片段转换为 Markdown，并保留公式、换行、列表和图片。

Kotlin 版使用 jsoup 的 ``wholeText()`` 取纯文本，会丢掉 ``<br>`` 换行、段落、列表、
图片和链接。这里改用 HTML→Markdown 转换，得到的题面既有结构也不丢公式。
"""

from __future__ import annotations

from urllib.parse import urljoin

import markdownify
from bs4 import BeautifulSoup, Comment, Tag

from .protection import TextProtector

#: 站点模板里会出现、但不属于题面内容的标签。
_DROPPED_TAGS = ("script", "style", "noscript")

#: 需要把相对地址补成绝对地址的标签与属性，保证导入后图片仍可访问。
_URL_ATTRIBUTES = (("img", "src"), ("a", "href"), ("source", "src"), ("video", "src"))


class _StatementConverter(markdownify.MarkdownConverter):
    """在 markdownify 基础上保留 Markdown 没有对应语法的语义标签。

    ``<sup>`` / ``<sub>`` 在 Markdown 和 GFM 里都没有原生语法，markdownify 默认会把
    标签丢掉、只留文本，于是站点里的 ``X<sup>N</sup>`` 会变成 ``XN``——
    语义从“X 的 N 次方”变成“变量 XN”，题面直接读错。
    这里原样输出 HTML 标签，下游 marked + DOMPurify 会正常渲染并清洗。
    """

    def convert_sup(self, el: Tag, text: str, parent_tags: set[str]) -> str:
        """保留上标，例如 ``X<sup>N</sup>``。"""
        return f"<sup>{text}</sup>"

    def convert_sub(self, el: Tag, text: str, parent_tags: set[str]) -> str:
        """保留下标，与上标同理。"""
        return f"<sub>{text}</sub>"


def to_markdown(element: Tag, base_url: str | None = None) -> str:
    """把一个 HTML 元素转换成 Markdown 文本。

    :param element: 待转换的元素（例如 ``.OjInfo`` 或某个 ``<article>``）。
    :param base_url: 详情页地址；提供后相对图片、链接地址会补全为绝对地址。
    """
    # 复制一份再转换，避免在原解析树上就地修改影响后续选择器。
    fragment = BeautifulSoup(str(element), "html.parser")
    _drop_non_content(fragment)
    _absolutize_urls(fragment, base_url)

    protector = TextProtector()
    for node in fragment.find_all(string=True):
        if isinstance(node, Comment):
            continue
        # <pre>/<code> 内部是代码原文：公式不应渲染，字符也不应转义。
        if node.find_parent(["pre", "code", "kbd", "samp"]) is not None:
            continue
        protected = protector.protect_specials(protector.protect_math(str(node)))
        if protected != str(node):
            node.replace_with(protected)

    rendered = _StatementConverter(heading_style="ATX", bullets="-").convert(str(fragment))
    return _normalize(protector.restore(rendered))


def plain_text(element: Tag) -> str:
    """取元素的原始文本，只统一换行符并去除首尾空白。

    对应 jsoup 的 ``wholeText()``：不压缩中间空白，用于样例输入输出等必须逐字保留的内容。
    """
    return element.get_text().replace("\r\n", "\n").replace("\r", "\n").strip()


def normalized_text(element: Tag) -> str:
    """取元素的展示文本，并把连续空白压缩为单个空格。

    对应 jsoup 的 ``text()``：用于标题、难度、题型等短字段。
    """
    return " ".join(element.get_text().split())


def _drop_non_content(fragment: BeautifulSoup) -> None:
    """删除脚本、样式和 HTML 注释。

    站点会在 ``.mathjax-process`` 容器前保留一份注释掉的 ``<pre>`` 副本，
    注释必须剔除，否则题面会重复一遍。
    """
    for tag in fragment.find_all(list(_DROPPED_TAGS)):
        tag.decompose()
    for comment in fragment.find_all(string=lambda text: isinstance(text, Comment)):
        comment.extract()


def _absolutize_urls(fragment: BeautifulSoup, base_url: str | None) -> None:
    """把图片、链接等相对地址补全为绝对地址。"""
    if not base_url:
        return
    for tag_name, attribute in _URL_ATTRIBUTES:
        for node in fragment.find_all(tag_name):
            value = node.get(attribute)
            if isinstance(value, str) and value and not value.startswith(("data:", "javascript:")):
                node[attribute] = urljoin(base_url, value)


def _normalize(text: str) -> str:
    """统一换行符并去除首尾空白；保留行尾空格，避免破坏 Markdown 硬换行。"""
    return text.replace("\r\n", "\n").replace("\r", "\n").strip()
