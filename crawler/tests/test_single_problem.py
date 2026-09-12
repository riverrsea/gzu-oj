"""单题体检测试：题号解析、题面写出与详情客户端错误分支。"""

from __future__ import annotations

from pathlib import Path

import httpx
import pytest
import respx

from gzu_oj_crawler.cli import main
from gzu_oj_crawler.errors import CrawlerError
from gzu_oj_crawler.http_client import build_client
from gzu_oj_crawler.problem_crawler import (
    NoobDreamProblem,
    NoobDreamProblemClient,
    build_detail_item,
    resolve_problem_reference,
    write_statement,
)

_DETAIL_URL = "https://noobdream.com/DreamJudge/Issue/page/5382/"


def test_resolves_bare_problem_id() -> None:
    """只给题号时按站点模板补全详情页地址。"""
    assert resolve_problem_reference("5382") == ("5382", _DETAIL_URL)


def test_resolves_problem_url_and_keeps_host() -> None:
    """传入地址时保留原始主机，方便对着镜像站体检。"""
    url = "https://mirror.example.com/DreamJudge/Issue/page/5382/"
    assert resolve_problem_reference(url) == ("5382", url)


def test_accepts_url_without_trailing_slash() -> None:
    """详情页地址末尾没有斜杠也能识别。"""
    assert resolve_problem_reference("https://noobdream.com/DreamJudge/Issue/page/5382")[0] == "5382"


@pytest.mark.parametrize(
    "reference",
    ["", "   ", "abc", "5382abc", "https://noobdream.com/", "ftp://noobdream.com/DreamJudge/Issue/page/1/"],
)
def test_rejects_unrecognized_reference(reference: str) -> None:
    """无法识别的题号或地址直接报错，不发起网络请求。"""
    with pytest.raises(CrawlerError):
        resolve_problem_reference(reference)


def test_cli_reports_bad_reference_without_login(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """参数写错时在登录之前就失败，并给出可操作的提示。"""
    assert main(["noobdream-problem", "not-a-problem", str(tmp_path / "p.md")]) == 1
    assert "无法从" in capsys.readouterr().err


def test_write_statement_creates_parent_directories(tmp_path: Path) -> None:
    """题面写到多级目录时会自动创建父目录，并在末尾补换行。"""
    problem = NoobDreamProblem(
        external_key="noobdream:5382",
        problem_id="5382",
        title="最大数组距离",
        difficulty="",
        problem_type="",
        school="四川大学",
        year=2025,
        time_limit_ms=1000,
        memory_limit_mib=256,
        statement_markdown="# 最大数组距离\n\n距离定义为 $|a-b|$。",
        input_description="",
        output_description="",
        sample_input=None,
        sample_output=None,
        source_url=_DETAIL_URL,
    )
    target = tmp_path / "nested" / "dir" / "p5382.md"

    write_statement(problem, target)

    assert target.read_text(encoding="utf-8") == "# 最大数组距离\n\n距离定义为 $|a-b|$。\n"


@respx.mock
def test_fetch_parses_single_problem_and_keeps_math() -> None:
    """单题抓取沿用同一解析链路，公式必须保留。"""
    respx.get(_DETAIL_URL).mock(
        return_value=httpx.Response(
            200,
            text=(
                "<div class='title-container'><h1>最大数组距离</h1></div>"
                "<div class='bg-light'>Time Limit: 1000 ms Memory Limit: 256 mb</div>"
                "<div class='OjInfo'><p>距离定义为 $|a-b|$。</p></div>"
            ),
        ),
    )
    client = build_client(follow_redirects=False)
    try:
        problem_client = NoobDreamProblemClient(cookie_header="sessionid=demo", client=client)
        problem = problem_client.fetch(build_detail_item("5382", _DETAIL_URL))
    finally:
        client.close()

    assert problem.title == "最大数组距离"
    assert problem.time_limit_ms == 1000
    assert "$|a-b|$" in problem.statement_markdown


@respx.mock
def test_fetch_reports_expired_cookie() -> None:
    """详情页被重定向回登录页时给出明确原因。"""
    respx.get(_DETAIL_URL).mock(
        return_value=httpx.Response(302, headers={"location": "/users/login/"}),
    )
    client = build_client(follow_redirects=False)
    try:
        problem_client = NoobDreamProblemClient(cookie_header="sessionid=demo", client=client)
        with pytest.raises(CrawlerError, match="被重定向"):
            problem_client.fetch(build_detail_item("5382", _DETAIL_URL))
    finally:
        client.close()
