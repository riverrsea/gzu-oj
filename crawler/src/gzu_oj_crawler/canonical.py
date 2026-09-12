"""与具体来源网站无关的规范题目模型、本地 JSON 适配器和标准 ZIP 写出。"""

from __future__ import annotations

import json
import re
import zipfile
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import unquote, urlparse

from .csv_io import csv_bytes
from .errors import require

#: externalKey 的命名空间格式，与 API 导入契约保持一致。
_EXTERNAL_KEY_REGEX = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

#: 标准导入包 problems.csv 的固定表头。
IMPORT_HEADERS = (
    "externalKey",
    "title",
    "school",
    "year",
    "tags",
    "difficulty",
    "sourceUrl",
    "timeLimitMs",
    "memoryLimitMiB",
    "statementPath",
    "dataPath",
)

#: 测试点 cases.csv 的固定表头。
CASE_HEADERS = ("ordinal", "inputPath", "outputPath", "sample")


@dataclass(slots=True)
class CanonicalTestCase:
    """爬虫统一输出的规范测试点；不含分值，得分由服务端按通过点数派生。"""

    #: 测试输入。
    input: str
    #: 由可信标程生成的标准输出。
    output: str
    #: 是否公开为样例。
    sample: bool = False


@dataclass(slots=True)
class CanonicalProblem:
    """与具体来源网站无关的规范题目。"""

    #: 外部题目标识；爬虫导入必须提供。
    external_key: str
    #: 题目标题。
    title: str
    #: 学校名称。
    school: str
    #: 真题年份。
    year: int
    #: EASY、MEDIUM 或 HARD。
    difficulty: str
    #: C/C++ 基准时间限制。
    time_limit_ms: int
    #: C/C++ 基准内存限制。
    memory_limit_mib: int
    #: Markdown 题面。
    statement_markdown: str
    #: 题目标签。
    tags: list[str] = field(default_factory=list)
    #: 原始来源链接。
    source_url: str | None = None
    #: 可选测试点；缺省时只生成题面导入包。
    test_cases: list[CanonicalTestCase] = field(default_factory=list)

    @classmethod
    def from_json(cls, payload: dict[str, object]) -> CanonicalProblem:
        """从本地样例 JSON 对象构造规范题目，缺字段时直接报错。"""
        required = (
            "externalKey",
            "title",
            "school",
            "year",
            "difficulty",
            "timeLimitMs",
            "memoryLimitMiB",
            "statementMarkdown",
        )
        missing = [name for name in required if name not in payload]
        require(not missing, "本地样例缺少字段：" + ", ".join(missing))
        tags = payload.get("tags") or []
        require(isinstance(tags, list), "tags 必须是字符串数组")
        raw_cases = payload.get("testCases") or []
        require(isinstance(raw_cases, list), "testCases 必须是数组")
        source_url = payload.get("sourceUrl")
        return cls(
            external_key=str(payload["externalKey"]),
            title=str(payload["title"]),
            school=str(payload["school"]),
            year=int(payload["year"]),  # type: ignore[arg-type]
            difficulty=str(payload["difficulty"]),
            time_limit_ms=int(payload["timeLimitMs"]),  # type: ignore[arg-type]
            memory_limit_mib=int(payload["memoryLimitMiB"]),  # type: ignore[arg-type]
            statement_markdown=str(payload["statementMarkdown"]),
            tags=[str(tag) for tag in tags],
            source_url=None if source_url is None else str(source_url),
            test_cases=[
                CanonicalTestCase(
                    input=str(case["input"]),
                    output=str(case["output"]),
                    sample=bool(case.get("sample", False)),
                )
                for case in raw_cases
            ],
        )


class LocalSampleAdapter:
    """读取本地 JSON 样例的适配器，不执行网络抓取。"""

    #: 适配器稳定名称。
    name = "local-sample"

    def fetch(self, source: str) -> list[CanonicalProblem]:
        """从 file URI 读取 CanonicalProblem 数组。"""
        parsed = urlparse(source)
        require(parsed.scheme == "file", "本地样例适配器只接受 file URI")
        path = Path(unquote(parsed.path)).absolute()
        require(path.is_file(), f"本地样例文件不存在：{path}")
        payload = json.loads(path.read_bytes())
        require(isinstance(payload, list), "本地样例必须是 CanonicalProblem 数组")
        problems = [CanonicalProblem.from_json(item) for item in payload]
        validate_canonical_problems(problems)
        return problems


class ImportPackageWriter:
    """将规范题目写为 API 可暂存导入的标准 ZIP。"""

    def write(self, problems: list[CanonicalProblem], target: Path) -> None:
        """写入 problems.csv、statements 和可选 tests 目录。"""
        normalized = target.absolute()
        normalized.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(normalized, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("problems.csv", _problems_csv(problems))
            for index, problem in enumerate(problems):
                stem = _artifact_stem(index)
                archive.writestr(
                    f"statements/{stem}.md",
                    problem.statement_markdown.encode("utf-8"),
                )
                if problem.test_cases:
                    self._write_cases(archive, problem, stem)

    def _write_cases(
        self,
        archive: zipfile.ZipFile,
        problem: CanonicalProblem,
        stem: str,
    ) -> None:
        """写入固定 cases.csv 及对应输入输出。"""
        prefix = f"tests/{stem}"
        rows: list[Sequence[object]] = []
        for index, test in enumerate(problem.test_cases):
            ordinal = index + 1
            input_name = f"{ordinal}.in"
            output_name = f"{ordinal}.out"
            rows.append((ordinal, input_name, output_name, _bool_text(test.sample)))
            archive.writestr(f"{prefix}/{input_name}", test.input.encode("utf-8"))
            archive.writestr(f"{prefix}/{output_name}", test.output.encode("utf-8"))
        archive.writestr(f"{prefix}/cases.csv", csv_bytes(CASE_HEADERS, rows))


def validate_canonical_problems(problems: list[CanonicalProblem]) -> None:
    """在写包前校验与 API 导入契约一致的核心字段。"""
    require(problems, "题目列表不能为空")
    keys = [problem.external_key for problem in problems]
    require(len(set(keys)) == len(keys), "externalKey 不能重复")
    for problem in problems:
        require(
            _EXTERNAL_KEY_REGEX.fullmatch(problem.external_key) is not None,
            f"externalKey 格式不正确：{problem.external_key}",
        )
        require(
            problem.title.strip() and problem.school.strip(),
            "标题和学校不能为空",
        )
        require(1900 <= problem.year <= 2200, "年份超出范围")
        require(problem.difficulty in {"EASY", "MEDIUM", "HARD"}, "难度不合法")
        require(100 <= problem.time_limit_ms <= 60_000, "时间限制超出范围")
        require(16 <= problem.memory_limit_mib <= 2048, "内存限制超出范围")
        require(problem.statement_markdown.strip(), "题面不能为空")


def _problems_csv(problems: list[CanonicalProblem]) -> bytes:
    """构造固定表头的 UTF-8 CSV。"""
    rows = []
    for index, problem in enumerate(problems):
        stem = _artifact_stem(index)
        data_path = "" if not problem.test_cases else f"tests/{stem}"
        rows.append(
            (
                problem.external_key,
                problem.title,
                problem.school,
                problem.year,
                ";".join(problem.tags),
                problem.difficulty,
                problem.source_url or "",
                problem.time_limit_ms,
                problem.memory_limit_mib,
                f"statements/{stem}.md",
                data_path,
            ),
        )
    return csv_bytes(IMPORT_HEADERS, rows)


def _artifact_stem(index: int) -> str:
    """生成与外部键无关的安全文件名，兼容 Windows 解压和跨平台导入。"""
    return f"problem-{index + 1}"


def _bool_text(value: bool) -> str:
    """按 Kotlin ``Boolean.toString()`` 的习惯输出 ``true`` / ``false``。"""
    return "true" if value else "false"
