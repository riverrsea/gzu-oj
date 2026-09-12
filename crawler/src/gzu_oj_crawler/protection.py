"""题面文本中需要保护、不能被 Markdown 转换器改写的片段。

这里处理两类问题：

1. **数学公式**。目标站点用 MathJax 3 在浏览器端渲染公式，服务器返回的 HTML 里保存的是
   **原始 TeX 源码**，因此采集不会丢失公式，前提是提取过程不破坏它。站点题目页内联脚本里的
   配置为 ``inlineMath``：``$...$`` 与 ``\\(...\\)``，``displayMath``：``$$...$$`` 与 ``\\[...\\]``。
   下游 Markdown 渲染器（marked + KaTeX）只识别 ``$`` 定界符，所以统一归一化成 ``$...$``
   （行内）与 ``$$...$$``（块级）。
2. **会被当成 HTML 的字符**。HTML 实体解码后，正文里可能出现 ``<script>`` 这类字面文本；
   直接进入 Markdown 会被渲染器当作标签，轻则内容被清洗掉，重则形成注入面。
   这里把它们换成占位符，转换完成后还原为 ``&lt;`` 等安全写法。

两类保护都必须发生在 HTML→Markdown 转换**之前**：Markdown 转换器会转义 ``_``、``*``、
``` ` ``` 等字符，直接处理 ``a_1`` 会变成 ``a\\_1``，破坏 LaTeX 下标。
"""

from __future__ import annotations

import re
from collections.abc import Callable

#: 占位符模板；只用大写字母和数字，避免被 Markdown 转换器转义或重新解释。
_TOKEN_TEMPLATE = "MJXMATHPLACEHOLDER{n}X"

#: 依次匹配块级公式和行内公式；先处理块级，避免 ``$$`` 被当成两个行内 ``$``。
_MATH_PATTERNS: tuple[tuple[re.Pattern[str], Callable[[str], str]], ...] = (
    # $$...$$：站点块级公式，原样保留为 $$...$$。
    (re.compile(r"\$\$(?P<body>[\s\S]+?)\$\$"), lambda body: f"$${body}$$"),
    # \[...\]：KaTeX 也支持 $$...$$，统一成 $$...$$。
    (re.compile(r"\\\[(?P<body>[\s\S]+?)\\\]"), lambda body: f"$${body}$$"),
    # \(...\)：归一化为行内 $...$。
    (re.compile(r"\\\((?P<body>[\s\S]+?)\\\)"), lambda body: f"${body}$"),
    # $...$：站点默认行内公式；不跨行、不允许内部再出现未转义的 $。
    (
        re.compile(r"(?<!\\)\$(?!\$)(?P<body>[^$\n]+?)(?<!\\)\$(?!\$)"),
        lambda body: f"${body}$",
    ),
)

#: 可能被解析成 HTML 标签的尖括号：后跟标签名、斜杠、感叹号或问号。
_ANGLE_BRACKET_REGEX = re.compile(r"<(?=[A-Za-z/!?])")


class TextProtector:
    """在一次 HTML→Markdown 转换中保护公式和会被误认为 HTML 的字符。

    用法：转换前对每个文本节点调用 :meth:`protect_math` 与 :meth:`protect_specials`，
    转换后对整个结果调用 :meth:`restore`。实例只在单次转换内复用，不跨题目共享。
    """

    def __init__(self) -> None:
        """初始化空的占位符缓冲区。"""
        self._parts: list[str] = []

    def protect_math(self, text: str) -> str:
        """把文本中的公式替换为占位符，并记录归一化后的公式原文。"""
        # 绝大多数文本节点不含公式，提前返回可以省掉多次正则扫描。
        if "$" not in text and "\\(" not in text and "\\[" not in text:
            return text
        for pattern, wrap in _MATH_PATTERNS:
            text = pattern.sub(lambda match, wrap=wrap: self._store(wrap(match["body"])), text)
        return text

    def protect_specials(self, text: str) -> str:
        """把可能被当作 HTML 标签的 ``<`` 和裸反引号替换为占位符。

        还原时：``<`` 变成 ``&lt;``，反引号变成 ``\\```，两者在 Markdown 中都按字面量渲染。
        """
        if "<" in text:
            text = _ANGLE_BRACKET_REGEX.sub(lambda _match: self._store("&lt;"), text)
        if "`" in text:
            text = text.replace("`", self._store("\\`"))
        return text

    def restore(self, text: str) -> str:
        """把占位符还原为受保护片段。"""
        for index, value in enumerate(self._parts):
            text = text.replace(_TOKEN_TEMPLATE.format(n=index), value)
        return text

    def _store(self, value: str) -> str:
        """记录一个受保护片段并返回其占位符。"""
        self._parts.append(value)
        return _TOKEN_TEMPLATE.format(n=len(self._parts) - 1)
