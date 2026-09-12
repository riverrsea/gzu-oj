"""标准导入包写出器测试。"""

from __future__ import annotations

import zipfile
from pathlib import Path

from gzu_oj_crawler.canonical import CanonicalProblem, CanonicalTestCase, ImportPackageWriter


def test_writes_canonical_archive(tmp_path: Path) -> None:
    """写入题面、固定表头和测试点文件。"""
    target = tmp_path / "import.zip"
    ImportPackageWriter().write(
        [
            CanonicalProblem(
                external_key="noobdream:1006",
                title="两数之和",
                school="贵州大学",
                year=2025,
                tags=["数组"],
                difficulty="EASY",
                time_limit_ms=1000,
                memory_limit_mib=256,
                statement_markdown="# 两数之和",
                test_cases=[CanonicalTestCase(input="1 2\n", output="3\n", sample=True)],
            ),
        ],
        target,
    )
    with zipfile.ZipFile(target) as archive:
        names = archive.namelist()
        assert "problems.csv" in names
        assert "statements/problem-1.md" in names
        assert "tests/problem-1/cases.csv" in names
        assert archive.read("statements/problem-1.md").decode("utf-8") == "# 两数之和"
        csv_text = archive.read("problems.csv").decode("utf-8")
        assert csv_text.startswith(
            "externalKey,title,school,year,tags,difficulty,sourceUrl,timeLimitMs,memoryLimitMiB,statementPath,dataPath",
        )
        assert "noobdream:1006,两数之和,贵州大学,2025,数组,EASY,,1000,256,statements/problem-1.md,tests/problem-1" in csv_text
        cases = archive.read("tests/problem-1/cases.csv").decode("utf-8")
        assert cases.startswith("ordinal,inputPath,outputPath,sample")
        assert "1,1.in,1.out,true" in cases


def test_writes_statement_only_archive(tmp_path: Path) -> None:
    """没有测试点时只写题面，dataPath 留空。"""
    target = tmp_path / "import-no-tests.zip"
    ImportPackageWriter().write(
        [
            CanonicalProblem(
                external_key="noobdream:1",
                title="A+B",
                school="贵州大学",
                year=1900,
                difficulty="EASY",
                time_limit_ms=1000,
                memory_limit_mib=256,
                statement_markdown="# A+B",
            ),
        ],
        target,
    )
    with zipfile.ZipFile(target) as archive:
        assert "tests/problem-1/cases.csv" not in archive.namelist()
        assert archive.read("problems.csv").decode("utf-8").rstrip("\r\n").endswith(
            "statements/problem-1.md,",
        )
