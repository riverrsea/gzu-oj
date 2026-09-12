"""把已登录采集的题目详情 CSV 转换为标准导入包。

默认会把公开样例写成唯一测试点，让导入后的题目立刻可判；样例块疑似包含多组用例时
会在返回值里列出题号，交人工在管理页面拆分。

注意导入契约要求测试点分值之和正好为 100，所以只放一个样例测试点时它必然是 100 分。
"""

from __future__ import annotations

import csv
import io
import re
from dataclasses import dataclass
from pathlib import Path

from .canonical import (
    CanonicalProblem,
    CanonicalTestCase,
    ImportPackageWriter,
    validate_canonical_problems,
)
from .errors import CrawlerError, require
from .problem_crawler import PROBLEM_HEADERS

#: externalKey 的命名空间格式，与 API 导入契约保持一致。
_EXTERNAL_KEY_REGEX = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

#: 外部来源未提供学校时的明确占位值，避免与真实学校混淆。
DEFAULT_SCHOOL = "未注明"

#: 外部来源未提供年份时的占位值，也是数据库允许的最早年份。
DEFAULT_YEAR = 1900

#: 样例测试点的分值。导入契约要求测试点分值之和为 100，只放一个测试点时只能给满分。
SAMPLE_TEST_SCORE = 100


@dataclass(slots=True)
class ConversionSummary:
    """详情 CSV → 导入包的转换结果，供 CLI 报告和人工复核。"""

    #: 成功转换的题目数。
    problem_count: int
    #: 带样例测试点的题目数。
    sample_test_count: int
    #: 样例块疑似包含多组用例、需要人工拆分的题目标识。
    multi_case_suspects: list[str]


class NoobDreamImportConverter:
    """将详情 CSV 转换为标准导入包。"""

    def convert(
        self,
        input_path: Path,
        target: Path,
        default_year: int | None = None,
        sample_as_test: bool = True,
    ) -> ConversionSummary:
        """转换详情 CSV；``default_year`` 只填充空年份，不覆盖已有年份。

        ``sample_as_test`` 为真时把公开样例写成唯一测试点（分值 100、标记为公开样例），
        让题目导入后立刻可判；样例块疑似含多组用例的题号会写进返回值，供人工拆分。
        未显式指定 default_year 时使用 1900 表示外部来源没有提供年份。
        转换失败时汇总全部行错误，避免只修正第一道题后重复试错。
        """
        require(input_path.is_file(), f"详情 CSV 不存在：{input_path}")
        if default_year is not None:
            require(1900 <= default_year <= 2200, "默认年份必须在 1900..2200 之间")
        records = _parse_records(input_path)
        require(records, "详情 CSV 没有题目记录")
        errors: list[str] = []
        problems: list[CanonicalProblem] = []
        multi_case_suspects: list[str] = []
        for index, record in enumerate(records):
            try:
                problem = _to_canonical_problem(record, default_year, sample_as_test)
            except CrawlerError as error:
                key = (record.get("externalKey") or "").strip() or "?"
                title = (record.get("title") or "").strip() or "未命名题目"
                errors.append(f"第 {index + 2} 行（{key}，{title}）：{error}")
                continue
            problems.append(problem)
            if problem.test_cases and _sample_looks_like_multiple_cases(problem.test_cases[0]):
                multi_case_suspects.append(problem.external_key)
        if errors:
            raise CrawlerError(
                f"详情 CSV 无法转换为标准导入包，共 {len(errors)} 行不完整：\n" + "\n".join(errors),
            )
        validate_canonical_problems(problems)
        ImportPackageWriter().write(problems, target)
        return ConversionSummary(
            problem_count=len(problems),
            sample_test_count=sum(1 for problem in problems if problem.test_cases),
            multi_case_suspects=multi_case_suspects,
        )


def _parse_records(input_path: Path) -> list[dict[str, str]]:
    """使用严格 UTF-8，兼容 Windows 工具导出的 GB18030 CSV。"""
    raw = input_path.read_bytes()
    text = _decode(raw, "utf-8") or _decode(raw, "gb18030")
    require(text is not None, "详情 CSV 不是有效的 UTF-8 或 GB18030 文本")
    reader = csv.DictReader(io.StringIO(text))
    require(
        reader.fieldnames == list(PROBLEM_HEADERS),
        "详情 CSV 表头必须为：" + ",".join(PROBLEM_HEADERS),
    )
    return [record for record in reader]


def _decode(raw: bytes, encoding: str) -> str | None:
    """严格解码，遇到非法字节时返回空。"""
    try:
        return raw.decode(encoding, errors="strict").removeprefix("\ufeff")
    except UnicodeDecodeError:
        return None


def _to_canonical_problem(
    record: dict[str, str],
    default_year: int | None,
    sample_as_test: bool = True,
) -> CanonicalProblem:
    """将一行详情记录映射到标准导入模型。"""
    external_key = _required(record, "externalKey")
    require(
        _external_key_valid(external_key),
        "externalKey 格式不正确",
    )
    title = _required(record, "title")
    require(len(title) <= 200, "title 过长")
    school = _field(record, "school") or DEFAULT_SCHOOL
    require(len(school) <= 200, "school 过长")
    year_text = _field(record, "year")
    year = int(year_text) if _is_int(year_text) else (default_year if default_year is not None else DEFAULT_YEAR)
    require(1900 <= year <= 2200, "year 超出 1900..2200 范围")
    difficulty = _map_difficulty(_required(record, "difficulty"))
    time_limit_ms = _to_int(_required(record, "timeLimitMs"), "timeLimitMs")
    require(100 <= time_limit_ms <= 60_000, "timeLimitMs 超出范围")
    memory_limit_mib = _normalize_memory_limit(_required(record, "memoryLimitMiB"))
    statement = _required(record, "statementMarkdown")
    require(len(statement) <= 1_000_000, "statementMarkdown 过长")
    problem_type = _field(record, "problemType")
    return CanonicalProblem(
        external_key=external_key,
        title=title,
        school=school,
        year=year,
        tags=[problem_type] if problem_type else [],
        difficulty=difficulty,
        source_url=_field(record, "sourceUrl") or None,
        time_limit_ms=time_limit_ms,
        memory_limit_mib=memory_limit_mib,
        statement_markdown=statement,
        test_cases=_sample_test_cases(record) if sample_as_test else [],
    )


def _sample_test_cases(record: dict[str, str]) -> list[CanonicalTestCase]:
    """把公开样例转成唯一测试点。

    导入契约要求测试点分值之和正好为 100，所以只有一个测试点时它只能是满分。
    样例标记为 ``sample=True``，前端会把它当作公开样例展示。
    样例不完整（缺输入或缺输出）时不生成测试点，题目仍需人工补数据。
    """
    sample_input = _field(record, "sampleInput")
    sample_output = _field(record, "sampleOutput")
    if not sample_input or not sample_output:
        return []
    return [
        CanonicalTestCase(
            input=sample_input,
            output=sample_output,
            score=SAMPLE_TEST_SCORE,
            sample=True,
        ),
    ]


def _sample_looks_like_multiple_cases(test_case: CanonicalTestCase) -> bool:
    """判断样例块是否疑似塞了多组用例。

    站点把多组样例拼在同一个 ``<pre>`` 里：题目 1002 的输入是两行 ``2 100`` / ``2 22``，
    输出也是两行 ``20`` / ``6``，实际是两组独立用例。整块当成一个测试点会让只处理单组的
    正确程序判 WA，所以按“输入输出行数相同且都大于 1”给出疑似信号，交人工拆分。
    """
    input_lines = [line for line in test_case.input.splitlines() if line.strip()]
    output_lines = [line for line in test_case.output.splitlines() if line.strip()]
    return len(input_lines) > 1 and len(input_lines) == len(output_lines)


def _map_difficulty(value: str) -> str:
    """将站点中文难度映射到标准导入枚举。"""
    key = value.strip().upper().rstrip("+-")
    if key in {"EASY", "简单", "容易"}:
        return "EASY"
    if key in {"MEDIUM", "中等", "一般"}:
        return "MEDIUM"
    if key in {"HARD", "困难"}:
        return "HARD"
    raise CrawlerError(f"difficulty 不支持：{value}")


def _normalize_memory_limit(value: str) -> int:
    """规范化源站内存限制。

    新题按 MiB 保存；部分旧题沿用 KiB 数值，另有少量页面把 MiB 与旧值拼接。
    转换只在原值超出系统范围时纠正。
    """
    raw = _to_int(value, "memoryLimitMiB")
    if 16 <= raw <= 2048:
        return raw
    if 1 <= raw < 16:
        return 16

    # 例如 25632768 表示页面把 256 MiB 与旧的 32768 KiB 文本拼接。
    concatenated_prefix: int | None = None
    for split in range(2, len(value)):
        prefix = value[:split]
        if _is_int(prefix) and 16 <= int(prefix) <= 2048:
            concatenated_prefix = int(prefix)
    if concatenated_prefix is not None and len(value) >= 7:
        return concatenated_prefix

    # 旧题常见 32768 KiB；对 32678 之类的源站笔误按最接近的 MiB 换算。
    converted = (raw + 512) // 1024
    require(16 <= converted <= 2048, f"memoryLimitMiB 超出范围：{raw}")
    return converted


def _required(record: dict[str, str], name: str) -> str:
    """读取必填字段并去除两端空白。"""
    value = _field(record, name)
    require(value, f"{name} 不能为空")
    return value


def _field(record: dict[str, str], name: str) -> str:
    """读取字段并去除两端空白；缺失时返回空串。"""
    value = record.get(name)
    return value.strip() if isinstance(value, str) else ""


def _to_int(value: str, name: str) -> int:
    """把字段转成整数，失败时抛出带字段名的业务错误。"""
    text = value.strip()
    body = text[1:] if text[:1] in {"+", "-"} else text
    require(body.isdigit(), f"{name} 不是整数：{value}")
    return int(text)


def _is_int(value: str) -> bool:
    """判断字符串是否为可选带符号的十进制整数。"""
    text = value.strip()
    if not text:
        return False
    body = text[1:] if text[0] in {"+", "-"} else text
    return body.isdigit()


def _external_key_valid(value: str) -> bool:
    """校验 externalKey 是否符合 API 导入契约。"""
    return _EXTERNAL_KEY_REGEX.fullmatch(value) is not None
