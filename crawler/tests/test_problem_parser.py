"""N 诺题目详情页解析测试。"""

from __future__ import annotations

from gzu_oj_crawler.list_crawler import NoobDreamListItem
from gzu_oj_crawler.problem_crawler import NoobDreamProblemParser


def _item(problem_id: str, title: str = "字符串翻转", school: str | None = "贵州大学") -> NoobDreamListItem:
    """构造一条与列表页解析结果等价的详情页输入。"""
    return NoobDreamListItem(
        external_key=f"noobdream:{problem_id}",
        problem_id=problem_id,
        title=title,
        difficulty="简单",
        problem_type="简单模拟",
        school=school,
        source_description="贵州大学机试题",
        detail_url=f"https://noobdream.com/DreamJudge/Issue/page/{problem_id}/",
    )


def test_parses_problem_detail() -> None:
    """解析限制、题面、输入输出描述、样例和来源。"""
    problem = NoobDreamProblemParser().parse(
        """
        <div class="title-container"><h1>字符串翻转</h1></div>
        <div class="bg-light"><span>Time Limit: 1000 ms</span><br><span>Memory Limit: 256 mb</span></div>
        <div class="OjInfo"><p>给定一个字符串，反序输出。</p></div>
        <div class="Problem-content"><h6>输入描述:</h6><article><div class="pre-style">输入一个字符串。</div></article></div>
        <div class="Problem-content"><h6>输出描述:</h6><article><div class="pre-style">输出反序字符串。</div></article></div>
        <pre id="input">Guiyang</pre><pre id="output">gnayiuG</pre>
        <h5>题目来源</h5><div><pre>贵州大学机试题</pre></div>
        """,
        _item("1006"),
    )
    assert problem.time_limit_ms == 1000
    assert problem.memory_limit_mib == 256
    assert problem.sample_input == "Guiyang"
    assert problem.sample_output == "gnayiuG"
    assert problem.external_key == "noobdream:1006"
    assert problem.school == "贵州大学"
    assert problem.source_url == "https://noobdream.com/DreamJudge/Issue/page/1006/"
    assert "## 输入描述" in problem.statement_markdown
    assert "```text" in problem.statement_markdown


def test_allows_missing_format_section() -> None:
    """输入或输出描述缺失时仍保留题目记录，缺失字段写为空。"""
    problem = NoobDreamProblemParser().parse("<h1>旧题</h1>", _item("1001", title="旧题", school=None))
    body = problem.statement_markdown.split("## 题目描述\n", 1)[1].split("\n\n## 输入描述", 1)[0]
    assert body == ""
    assert problem.input_description == ""
    assert problem.output_description == ""


def test_keeps_mathjax_source_in_statement() -> None:
    """站点用 MathJax 客户端渲染，采集必须保留原始 TeX 源码。"""
    problem = NoobDreamProblemParser().parse(
        """
        <div class="title-container"><h1>最大数组距离</h1></div>
        <div class="bg-light">Time Limit: 1000 ms Memory Limit: 256 mb</div>
        <div class="OjInfo"><p>给定 $m$ 个升序排列的整数数组，距离定义为 $|a-b|$。</p></div>
        <div class="Problem-content"><h6>输入描述:</h6><article>
          <!-- <pre>旧副本</pre> -->
          <div class="pre-style">第一行整数 $m$（$2 \\le m \\le 10^4$）。</div>
        </article></div>
        <div class="Problem-content"><h6>输出描述:</h6><article><div class="pre-style">最大距离。</div></article></div>
        <pre id="input">3
2 1 5</pre><pre id="output">4</pre>
        """,
        _item("5382", title="最大数组距离", school=None),
    )
    assert "$m$" in problem.statement_markdown
    assert "$|a-b|$" in problem.statement_markdown
    assert "$2 \\le m \\le 10^4$" in problem.statement_markdown
    assert "旧副本" not in problem.statement_markdown
    # CSV 里的输入描述也应是 Markdown，而不是被吃掉的纯文本。
    assert "$2 \\le m \\le 10^4$" in problem.input_description


def test_keeps_multiline_sample_exactly() -> None:
    """多行样例要逐字保留，不能被压缩成一行。"""
    problem = NoobDreamProblemParser().parse(
        "<h1>多行样例</h1><pre id='input'>2 100\n2 22</pre><pre id='output'>20\n6</pre>",
        _item("1002", title="多行样例", school=None),
    )
    assert problem.sample_input == "2 100\n2 22"
    assert problem.sample_output == "20\n6"
