"""N 诺题库列表解析和 CSV 写入测试。"""

from __future__ import annotations

from pathlib import Path

from gzu_oj_crawler.list_crawler import (
    NoobDreamListItem,
    NoobDreamListParser,
    extract_noobdream_school,
    extract_noobdream_year,
    parse_noobdream_list_html,
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
