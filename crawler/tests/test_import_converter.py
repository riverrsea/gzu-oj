"""noobdream 详情 CSV 转标准无测试点导入包测试。"""

from __future__ import annotations

import zipfile
from pathlib import Path

from gzu_oj_crawler.import_converter import (
    DEFAULT_SCHOOL,
    DEFAULT_YEAR,
    NoobDreamImportConverter,
)

_HEADER = (
    "externalKey,problemId,title,school,year,difficulty,problemType,"
    "timeLimitMs,memoryLimitMiB,statementMarkdown,inputDescription,outputDescription,"
    "sampleInput,sampleOutput,sourceUrl"
)


def _convert(tmp_path: Path, csv_text: str, default_year: int | None = None) -> zipfile.ZipFile:
    """写出详情 CSV 并转换，返回已打开的导入包（调用方负责关闭）。"""
    source = tmp_path / "details.csv"
    target = tmp_path / "import.zip"
    source.write_text(csv_text, encoding="utf-8")
    NoobDreamImportConverter().convert(source, target, default_year)
    return zipfile.ZipFile(target)


def test_converts_details_csv_without_tests(tmp_path: Path) -> None:
    """转换多行 Markdown，并把默认年份填入空年份。"""
    csv_text = (
        _HEADER
        + "\n"
        + 'noobdream:1006,1006,字符串翻转,贵州大学,,简单,简单模拟,1000,256,"# 字符串翻转\n'
        + '\n'
        + '## 输入描述\n'
        + '输入一个字符串。",,,,,https://noobdream.com/DreamJudge/Issue/page/1006/\n'
    )
    with _convert(tmp_path, csv_text, default_year=2025) as archive:
        problems_csv = archive.read("problems.csv").decode("utf-8")
        assert problems_csv.startswith(
            "externalKey,title,school,year,tags,difficulty,sourceUrl,timeLimitMs,memoryLimitMiB,statementPath,dataPath",
        )
        assert "noobdream:1006,字符串翻转,贵州大学,2025,简单模拟,EASY" in problems_csv
        assert archive.read("statements/problem-1.md").decode("utf-8") == (
            "# 字符串翻转\n\n## 输入描述\n输入一个字符串。"
        )
        assert "tests/problem-1/cases.csv" not in archive.namelist()


def test_reads_gb18030_csv(tmp_path: Path) -> None:
    """兼容 Windows 常见 GB18030 编码的详情 CSV。"""
    source = tmp_path / "details-gb.csv"
    target = tmp_path / "import-gb.zip"
    csv_text = (
        _HEADER
        + "\n"
        + "noobdream:1,1,A+B,贵州大学,2025,EASY,模拟,1000,256,# A+B,,,,,\n"
    )
    source.write_bytes(csv_text.encode("gb18030"))
    NoobDreamImportConverter().convert(source, target)
    with zipfile.ZipFile(target) as archive:
        assert "statements/problem-1.md" in archive.namelist()


def test_fills_missing_school(tmp_path: Path) -> None:
    """空学校转换为明确占位值，仍可通过导入器的非空校验。"""
    csv_text = (
        _HEADER + "\n" + "noobdream:1,1,A+B,,2025,EASY,模拟,1000,256,# A+B,,,,,\n"
    )
    with _convert(tmp_path, csv_text) as archive:
        problems_csv = archive.read("problems.csv").decode("utf-8")
        assert f"noobdream:1,A+B,{DEFAULT_SCHOOL},2025" in problems_csv


def test_fills_missing_year_with_1900(tmp_path: Path) -> None:
    """空年份默认写为 1900，已有年份和显式默认年份不受影响。"""
    csv_text = (
        _HEADER + "\n" + "noobdream:1,1,A+B,贵州大学,,EASY,模拟,1000,256,# A+B,,,,,\n"
    )
    with _convert(tmp_path, csv_text) as archive:
        problems_csv = archive.read("problems.csv").decode("utf-8")
        assert f"noobdream:1,A+B,贵州大学,{DEFAULT_YEAR}" in problems_csv


def test_normalizes_source_metadata(tmp_path: Path) -> None:
    """源站难度后缀、旧 KiB、拼接值和低于系统下限的内存值转换为项目标准值。"""
    csv_text = (
        _HEADER
        + "\n"
        + "noobdream:1,1,旧题一,清华大学,,中等-,模拟,1000,32678,# 旧题一,,,,,\n"
        + "noobdream:2,2,旧题二,北京大学,,中等+,数学,1000,25632768,# 旧题二,,,,,\n"
        + "noobdream:3,3,旧题三,,,简单,数学,1000,10,# 旧题三,,,,,\n"
    )
    with _convert(tmp_path, csv_text) as archive:
        problems_csv = archive.read("problems.csv").decode("utf-8")
        assert "noobdream:1,旧题一,清华大学,1900,模拟,MEDIUM,,1000,32" in problems_csv
        assert "noobdream:2,旧题二,北京大学,1900,数学,MEDIUM,,1000,256" in problems_csv
        assert "noobdream:3,旧题三,未注明,1900,数学,EASY,,1000,16" in problems_csv


def test_reports_all_invalid_rows(tmp_path: Path) -> None:
    """错误一次性汇总，便于一次修正整份 CSV。"""
    from gzu_oj_crawler.errors import CrawlerError

    csv_text = (
        _HEADER
        + "\n"
        + "noobdream:1,1,坏难度,贵州大学,2025,很难,模拟,1000,256,# A,,,,,\n"
        + "noobdream:2,2,坏时间,贵州大学,2025,EASY,模拟,10,256,# B,,,,,\n"
    )
    source = tmp_path / "bad.csv"
    source.write_text(csv_text, encoding="utf-8")
    try:
        NoobDreamImportConverter().convert(source, tmp_path / "bad.zip")
    except CrawlerError as error:
        message = str(error)
        assert "共 2 行不完整" in message
        assert "第 2 行" in message
        assert "第 3 行" in message
    else:  # pragma: no cover - 校验失败时必须抛错
        raise AssertionError("应当拒绝不合法的详情 CSV")
