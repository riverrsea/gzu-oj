"""题面保护与公式定界符归一化测试。"""

from __future__ import annotations

from gzu_oj_crawler.protection import TextProtector


def _roundtrip(text: str) -> str:
    """走一遍保护→还原流程，模拟 HTML→Markdown 转换。"""
    protector = TextProtector()
    return protector.restore(protector.protect_specials(protector.protect_math(text)))


def test_normalizes_latex_delimiters_to_markdown_math() -> None:
    """站点支持的四种定界符统一成 Markdown/KaTeX 的 $ 与 $$。"""
    assert _roundtrip("行内 \\(a_1\\) 与 $b_2$，块级 \\[x+y\\] 与 $$z$$") == (
        "行内 $a_1$ 与 $b_2$，块级 $$x+y$$ 与 $$z$$"
    )


def test_keeps_adjacent_chinese_without_inserting_spaces() -> None:
    """公式紧贴中文时不能被插入多余空格。"""
    assert _roundtrip("给定三角形三条边长$a,b,c$，求面积") == "给定三角形三条边长$a,b,c$，求面积"


def test_preserves_backslash_commands_and_subscripts() -> None:
    """反斜杠命令与下标必须原样保留，不能被 Markdown 转义。"""
    text = "面积 $S=\\sqrt{p(p-a)}$，范围 $2 \\le m \\le 10^4$，下标 $a_{ij}$"
    assert _roundtrip(text) == text


def test_double_dollar_is_not_split_into_inline_math() -> None:
    """$$...$$ 不能被拆成两段行内公式。"""
    assert _roundtrip("$$\\sum_{i=1}^{n} i$$") == "$$\\sum_{i=1}^{n} i$$"


def test_escaped_dollar_is_not_treated_as_math() -> None:
    """转义美元符号不是公式定界符。"""
    assert _roundtrip("价格 \\$5 到 \\$10") == "价格 \\$5 到 \\$10"


def test_plain_text_is_returned_unchanged() -> None:
    """不含公式的文本不做任何替换。"""
    assert _roundtrip("1≤L≤R≤100000") == "1≤L≤R≤100000"


def test_escapes_html_like_text_outside_math() -> None:
    """正文里的字面标签要转义，避免被渲染器当标签清洗或注入。"""
    assert _roundtrip("输出 <script>alert(1)</script>") == "输出 &lt;script>alert(1)&lt;/script>"


def test_keeps_angle_brackets_inside_math() -> None:
    """公式内部的 < > 属于 LaTeX 语法，不能被转义。"""
    assert _roundtrip("不等式 $a<b$ 与 $a>b$") == "不等式 $a<b$ 与 $a>b$"


def test_escapes_bare_backtick() -> None:
    """正文里的裸反引号不能被当成行内代码起始符。"""
    assert _roundtrip("使用 ` 符号表示") == "使用 \\` 符号表示"
