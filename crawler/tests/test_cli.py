"""CLI 入口测试：子命令分派、退出码与错误输出。"""

from __future__ import annotations

import json
import zipfile
from pathlib import Path

import pytest

from gzu_oj_crawler.cli import main

_VALID_PROBLEM = {
    "externalKey": "noobdream:1006",
    "title": "A+B",
    "school": "贵州大学",
    "year": 2025,
    "tags": ["模拟"],
    "difficulty": "EASY",
    "sourceUrl": "https://noobdream.com/DreamJudge/Issue/page/1006/",
    "timeLimitMs": 1000,
    "memoryLimitMiB": 256,
    "statementMarkdown": "# A+B\n\n输入 $a,b$，输出 $a+b$。",
    "testCases": [],
}


def test_local_command_writes_import_package(tmp_path: Path) -> None:
    """local 子命令把规范 JSON 写成标准导入包并返回 0。"""
    source = tmp_path / "problems.json"
    source.write_text(json.dumps([_VALID_PROBLEM], ensure_ascii=False), encoding="utf-8")
    target = tmp_path / "import.zip"

    assert main(["local", str(source), str(target)]) == 0

    with zipfile.ZipFile(target) as archive:
        assert "problems.csv" in archive.namelist()
        statement = archive.read("statements/problem-1.md").decode("utf-8")
        assert "$a+b$" in statement


def test_local_command_reports_invalid_json(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """契约不满足时返回 1 并把原因写到 stderr。"""
    source = tmp_path / "problems.json"
    # 难度不在枚举内，校验应当失败。
    source.write_text(json.dumps([{**_VALID_PROBLEM, "difficulty": "VERY_HARD"}]), encoding="utf-8")

    assert main(["local", str(source), str(tmp_path / "import.zip")]) == 1
    assert "难度不合法" in capsys.readouterr().err


def test_requires_subcommand() -> None:
    """不带子命令时由 argparse 直接以退出码 2 结束。"""
    with pytest.raises(SystemExit) as error:
        main([])
    assert error.value.code == 2
