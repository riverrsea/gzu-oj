"""HTML→Markdown 转换测试：结构、图片与注释处理。"""

from __future__ import annotations

from bs4 import BeautifulSoup

from gzu_oj_crawler.html_markdown import to_markdown

# 站点真实结构：每个 mathjax-process 容器前还有一份注释掉的 <pre> 副本。
_REAL_SECTION_HTML = """
<article class="my-3">
  <!-- <pre class="mathjax-process">旧副本不应出现</pre> -->
  <div class="pre-style mathjax-process">给定边长$a,b,c$。<br />
第二行含公式 $p=\\frac{a+b+c}{2}$。</div>
</article>
"""


def test_drops_commented_duplicate_pre() -> None:
    """注释里的旧副本必须剔除，否则题面会重复。"""
    fragment = BeautifulSoup(_REAL_SECTION_HTML, "html.parser")
    rendered = to_markdown(fragment.select_one("article"))
    assert "旧副本" not in rendered
    assert "给定边长$a,b,c$。" in rendered
    assert "$p=\\frac{a+b+c}{2}$" in rendered


def test_converts_br_to_markdown_hard_break() -> None:
    """<br /> 要变成 Markdown 硬换行，而不是被吃掉。"""
    fragment = BeautifulSoup("<div>第一行<br />第二行</div>", "html.parser")
    rendered = to_markdown(fragment.select_one("div"))
    assert rendered.splitlines() == ["第一行  ", "第二行"]


def test_converts_lists_and_absolutizes_images() -> None:
    """列表保留为 Markdown，相对图片地址补成绝对地址。"""
    html = """
    <div>数据范围
      <ul><li>数组总数 $m$：$2 \\le m \\le 10^4$</li><li>元素 $a_{ij}$</li></ul>
      <img src="/static/img/sample.png" alt="示意图" />
    </div>
    """
    fragment = BeautifulSoup(html, "html.parser")
    rendered = to_markdown(
        fragment.select_one("div"),
        base_url="https://noobdream.com/DreamJudge/Issue/page/5382/",
    )
    assert "- 数组总数 $m$：$2 \\le m \\le 10^4$" in rendered
    assert "- 元素 $a_{ij}$" in rendered
    assert "![示意图](https://noobdream.com/static/img/sample.png)" in rendered


def test_escapes_html_like_text_but_keeps_math() -> None:
    """会被当成标签的尖括号转成实体，公式里的尖括号不受影响。"""
    fragment = BeautifulSoup("<div>输出 &lt;script&gt;alert(1)&lt;/script&gt;，且 $a&lt;b$ 成立</div>", "html.parser")
    rendered = to_markdown(fragment.select_one("div"))
    assert "&lt;script>alert(1)&lt;/script>" in rendered
    # 公式内的 < 仍是 LaTeX 运算符，交给 KaTeX 处理。
    assert "$a<b$" in rendered


def test_does_not_escape_inside_code_elements() -> None:
    """<code> 内是代码原文，公式与尖括号都不应被改写。"""
    fragment = BeautifulSoup("<div>示例 <code>&lt;iostream&gt; 与 $x$</code></div>", "html.parser")
    rendered = to_markdown(fragment.select_one("div"))
    assert "`<iostream> 与 $x$`" in rendered
