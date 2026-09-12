"""N 诺题库列表解析和 CSV 写入测试。"""

from __future__ import annotations

from pathlib import Path

import pytest

from gzu_oj_crawler.errors import CrawlerError
from gzu_oj_crawler.list_crawler import (
    NoobDreamListItem,
    NoobDreamListParser,
    extract_noobdream_school,
    extract_noobdream_year,
    parse_noobdream_list_html,
    with_school_filter,
    write_list_csv,
)

_PAGE_URI = "https://noobdream.com/DreamJudge/Issue/page/0/"

_LIST_HTML = """
<html><body><table><tbody>
  <tr>
    <td><i aria-label="未解决"></i></td><td>1006</td>
    <td><a href="/DreamJudge/Issue/page/1006/" target="_blank">字符串翻转
      <span class="tag tag-school">真题</span>
      <span class="tag tag-source">贵州大学机试题</span>
    </a></td>
    <td><span class="level-tag">简单</span></td><td>简单模拟</td>
  </tr>
  <tr>
    <td></td><td>1000</td>
    <td><a href="/DreamJudge/Issue/page/1000/">A+B问题<span class="tag tag-source">计算机考研机试入门题</span></a></td>
    <td><span class="level-tag">简单</span></td><td>简单模拟</td>
  </tr>
</tbody></table><ul class="pagination">
  <li class="page-num"><a class="page-link">1</a></li>
  <li class="page-num" style="display:none"><a class="page-link">82</a></li>
</ul></body></html>
"""


def test_parses_list_rows() -> None:
    """解析列表行中的题号、标题、标签和绝对详情地址。"""
    items = parse_noobdream_list_html(_LIST_HTML, _PAGE_URI)
    assert len(items) == 2
    assert items[0].external_key == "noobdream:1006"
    assert items[0].problem_id == "1006"
    assert items[0].title == "字符串翻转"
    assert items[0].school == "贵州大学"
    assert items[0].source_description == "贵州大学机试题"
    assert items[0].detail_url == "https://noobdream.com/DreamJudge/Issue/page/1006/"
    assert items[0].difficulty == "简单"
    assert items[0].problem_type == "简单模拟"
    assert items[1].source_description == "计算机考研机试入门题"
    assert items[1].school is None
    assert extract_noobdream_school("兰州大学/贵州大学机试") == "兰州大学/贵州大学"
    assert extract_noobdream_year("南京大学2025年机试题") == 2025


def test_reads_total_pages() -> None:
    """分页总数取站点隐藏页码里的最大值。"""
    page = NoobDreamListParser().parse_page(_LIST_HTML, _PAGE_URI)
    assert page.total_pages == 82


def test_writes_csv(tmp_path: Path) -> None:
    """输出固定表头和带引号转义的单个 CSV 文件。"""
    target = tmp_path / "noobdream-list.csv"
    write_list_csv(
        [
            NoobDreamListItem(
                external_key="noobdream:1006",
                problem_id="1006",
                title="字符串翻转",
                difficulty="简单",
                problem_type="简单模拟",
                school="贵州大学",
                source_description="贵州大学机试题",
                detail_url="https://noobdream.com/DreamJudge/Issue/page/1006/",
            ),
        ],
        target,
    )
    csv_text = target.read_text(encoding="utf-8")
    assert csv_text.startswith("externalKey,problemId,title,difficulty,problemType,school,detailUrl")
    assert "noobdream:1006" in csv_text
    assert "贵州大学" in csv_text


def test_school_filter_adds_problem_source_parameter() -> None:
    """学校筛选走源站的 problem_source 参数，由服务端过滤。"""
    assert with_school_filter(_PAGE_URI, "贵州大学") == f"{_PAGE_URI}?problem_source=%E8%B4%B5%E5%B7%9E%E5%A4%A7%E5%AD%A6"


def test_school_filter_keeps_other_conditions() -> None:
    """追加学校筛选时保留已有题型等条件。"""
    url = f"{_PAGE_URI}?algorithm_type=%E6%95%B0%E5%AD%A6&page=3"
    filtered = with_school_filter(url, "清华大学")
    assert "algorithm_type=%E6%95%B0%E5%AD%A6" in filtered
    assert "page=3" in filtered
    assert filtered.endswith("problem_source=%E6%B8%85%E5%8D%8E%E5%A4%A7%E5%AD%A6")


def test_school_filter_replaces_existing_value() -> None:
    """重复指定学校时覆盖旧值，而不是叠加两个同名参数。"""
    url = f"{_PAGE_URI}?problem_source=%E5%8C%97%E4%BA%AC%E5%A4%A7%E5%AD%A6"
    filtered = with_school_filter(url, "贵州大学")
    assert filtered.count("problem_source=") == 1
    assert "北京大学" not in filtered


@pytest.mark.parametrize("school", [None, "", "   "])
def test_school_filter_is_noop_without_school(school: str | None) -> None:
    """没有指定学校时地址原样返回。"""
    assert with_school_filter(_PAGE_URI, school) == _PAGE_URI


def test_pagination_keeps_school_filter() -> None:
    """翻页时必须保留学校筛选，否则第二页会变成全库题目。"""
    from gzu_oj_crawler.list_crawler import _page_uri

    filtered = with_school_filter(_PAGE_URI, "贵州大学")
    second_page = _page_uri(filtered, 2)
    assert "problem_source=%E8%B4%B5%E5%B7%9E%E5%A4%A7%E5%AD%A6" in second_page
    assert second_page.endswith("page=2")


def test_empty_result_reports_filter_instead_of_missing_table() -> None:
    """筛选无结果时站点返回空 tbody，提示要点明是筛选条件的问题。"""
    filtered = with_school_filter(_PAGE_URI, "不存在的大学")
    with pytest.raises(CrawlerError) as error:
        NoobDreamListParser().parse_page("<table><tbody></tbody></table>", filtered)
    assert "筛选条件没有匹配到题目" in str(error.value)
    assert "学校" in str(error.value)


def test_empty_result_without_filter_keeps_original_message() -> None:
    """没有筛选条件时保持原来的表格缺失提示。"""
    with pytest.raises(CrawlerError, match="没有找到题目表格"):
        NoobDreamListParser().parse_page("<table><tbody></tbody></table>", _PAGE_URI)
