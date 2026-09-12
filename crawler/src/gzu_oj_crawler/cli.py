"""爬虫 CLI 入口。

支持五个子命令；前四个与 Kotlin 版 ``crawler-cli`` 完全对齐：

* ``local <canonical-problems.json> <output.zip>``
* ``noobdream-import <details.csv> <output.zip> [--default-year YYYY] [--no-sample-test]``
* ``noobdream-list <list-url> <output.csv> [--single-page] [--school 学校名]``
* ``noobdream-problems <list-url> <output.csv> [--single-page] [--school 学校名]``
* ``noobdream-problem <题号或地址> <output.md>``：单题体检，只写题面，便于核对公式

``--school`` 使用源站 ``problem_source`` 查询参数做服务端筛选，只抓目标学校的题目。
"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from .auth import NoobDreamCrawlerConfig, NoobDreamLoginClient
from .canonical import ImportPackageWriter, LocalSampleAdapter
from .errors import CrawlerError
from .import_converter import NoobDreamImportConverter
from .list_crawler import NoobDreamListClient, with_school_filter, write_list_csv
from .problem_crawler import (
    NoobDreamProblemClient,
    build_detail_item,
    resolve_problem_reference,
    write_problem_csv,
    write_statement,
)


def build_parser() -> argparse.ArgumentParser:
    """构造与 Kotlin 版等价的参数解析器。"""
    parser = argparse.ArgumentParser(
        prog="gzu-oj-crawler",
        description="GZU OJ 题库采集与标准导入包生成",
    )
    subparsers = parser.add_subparsers(dest="command", required=True, metavar="COMMAND")

    local = subparsers.add_parser("local", help="把本地规范 JSON 转成标准导入包")
    local.add_argument("source", help="CanonicalProblem JSON 文件")
    local.add_argument("target", help="输出 ZIP 路径")

    convert = subparsers.add_parser("noobdream-import", help="把详情 CSV 转成标准导入包")
    convert.add_argument("source", help="noobdream-problems 生成的详情 CSV")
    convert.add_argument("target", help="输出 ZIP 路径")
    convert.add_argument("--default-year", type=int, default=None, help="填充 CSV 中为空的年份")
    convert.add_argument(
        "--sample-test",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="把公开样例写成唯一测试点，让题目导入后立刻可判（默认开启）",
    )

    listing = subparsers.add_parser("noobdream-list", help="采集题目列表 CSV")
    listing.add_argument("url", help="N 诺题库列表页地址")
    listing.add_argument("target", help="输出 CSV 路径")
    listing.add_argument("--single-page", action="store_true", help="只抓取给定页面，不翻页")
    listing.add_argument("--school", default=None, help="只采集指定学校的题目，例如 --school 贵州大学")

    problems = subparsers.add_parser("noobdream-problems", help="采集题目详情 CSV")
    problems.add_argument("url", help="N 诺题库列表页地址")
    problems.add_argument("target", help="输出 CSV 路径")
    problems.add_argument("--single-page", action="store_true", help="只抓取给定页面，不翻页")
    problems.add_argument("--school", default=None, help="只采集指定学校的题目，例如 --school 贵州大学")

    single = subparsers.add_parser(
        "noobdream-problem",
        help="单题体检：抓取一道题的题面 Markdown，便于核对公式",
    )
    single.add_argument("problem", help="题号（如 5382）或详情页地址")
    single.add_argument("output", help="输出 Markdown 路径")

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """执行子命令；业务错误只打印消息并返回非零退出码。"""
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "local":
            _run_local(Path(args.source), Path(args.target))
        elif args.command == "noobdream-import":
            _run_import(Path(args.source), Path(args.target), args.default_year, args.sample_test)
        elif args.command == "noobdream-list":
            _run_list(args.url, Path(args.target), args.single_page, args.school)
        elif args.command == "noobdream-problems":
            _run_problems(args.url, Path(args.target), args.single_page, args.school)
        elif args.command == "noobdream-problem":
            _run_problem(args.problem, Path(args.output))
        else:  # pragma: no cover - argparse 已保证命令合法
            parser.error(f"未知子命令：{args.command}")
    except CrawlerError as error:
        print(f"错误：{error}", file=sys.stderr)
        return 1
    return 0


def _run_local(source: Path, target: Path) -> None:
    """本地规范 JSON → 标准导入包。"""
    source_path = source.absolute()
    target_path = target.absolute()
    adapter = LocalSampleAdapter()
    ImportPackageWriter().write(adapter.fetch(source_path.as_uri()), target_path)
    print(f"已生成标准导入包：{target_path}")


def _run_import(
    source: Path,
    target: Path,
    default_year: int | None,
    sample_as_test: bool = True,
) -> None:
    """详情 CSV → 标准导入包。"""
    target_path = target.absolute()
    summary = NoobDreamImportConverter().convert(
        source.absolute(),
        target_path,
        default_year,
        sample_as_test,
    )
    print(f"已生成标准导入包：{target_path}")
    if summary.sample_test_count:
        print(
            f"已把 {summary.sample_test_count}/{summary.problem_count} 道题的公开样例写成测试点"
            f"（每个样例一个测试点，分值 100，标记为公开样例）。",
        )
    if summary.multi_case_warnings:
        print(
            "注意：以下题目的样例块里可能拼了多组用例，整块作为单个测试点时，"
            "只处理单组的程序可能判 WA：",
        )
        print("  " + "、".join(summary.multi_case_warnings))


def _run_list(url: str, target: Path, single_page: bool, school: str | None = None) -> None:
    """采集列表页并写入 CSV；采集结束后登出。

    列表页只有元数据和详情页链接，没有题面。这里在结果后面补一句提示，
    避免把 ``detailUrl`` 当成题面内容。
    """
    target_path = target.absolute()
    page_uri = with_school_filter(url, school)
    with _LoggedInSession() as (_, cookie_header):
        client = NoobDreamListClient(cookie_header=cookie_header)
        items = client.fetch(page_uri) if single_page else client.fetch_all(page_uri)
        write_list_csv(items, target_path)
        print(f"已采集 {len(items)} 道题目列表{_scope_text(single_page, school)}并写入 CSV：{target_path}")
    print(_statement_hint(url, school))


def _statement_hint(url: str, school: str | None) -> str:
    """提示如何拿到完整题面。

    ``noobdream-list`` 与 ``noobdream-problems`` 的分工容易被误解：前者只抓列表页，
    所以 CSV 里只有标题、学校等元数据和 ``detailUrl`` 链接，没有题面。
    """
    command = ["uv run gzu-oj-crawler", "noobdream-problems", url, "<output.csv>"]
    name = (school or "").strip()
    if name:
        command += ["--school", name]
    return (
        "提示：noobdream-list 只采集列表元数据，CSV 里的 detailUrl 是详情页链接而不是题面。"
        "需要完整题面请改用：\n  " + " ".join(command)
    )


def _run_problems(
    url: str,
    target: Path,
    single_page: bool,
    school: str | None = None,
) -> None:
    """先采集列表，再逐题抓取详情并写入 CSV；采集结束后登出。"""
    target_path = target.absolute()
    page_uri = with_school_filter(url, school)
    with _LoggedInSession() as (_, cookie_header):
        list_client = NoobDreamListClient(cookie_header=cookie_header)
        list_items = list_client.fetch(page_uri) if single_page else list_client.fetch_all(page_uri)
        problems = NoobDreamProblemClient(cookie_header=cookie_header).fetch_all(list_items)
        write_problem_csv(problems, target_path)
        print(f"已抓取 {len(problems)} 道题目详情{_scope_text(single_page, school)}并写入 CSV：{target_path}")


def _scope_text(single_page: bool, school: str | None) -> str:
    """拼出采集范围描述，把翻页范围和学校筛选都写清楚。"""
    parts = ["单页" if single_page else "全部分页"]
    name = (school or "").strip()
    if name:
        parts.append(f"学校={name}")
    return "（" + "，".join(parts) + "）"


def _run_problem(problem_reference: str, output: Path) -> None:
    """单题体检：抓取一道题的详情并写成 Markdown，便于核对公式。

    先解析题号再登录：参数写错时不必白跑一次登录请求。
    """
    problem_id, detail_url = resolve_problem_reference(problem_reference)
    output_path = output.absolute()
    with _LoggedInSession() as (_, cookie_header):
        client = NoobDreamProblemClient(cookie_header=cookie_header)
        problem = client.fetch(build_detail_item(problem_id, detail_url))
        write_statement(problem, output_path)
    delimiters = problem.statement_markdown.count("$")
    print(
        f"已抓取题目 {problem_id}：{problem.title}"
        f"（公式定界符 {delimiters} 个，时限 {problem.time_limit_ms}ms，内存 {problem.memory_limit_mib}MiB），"
        f"已写入：{output_path}",
    )


class _LoggedInSession:
    """登录上下文管理器；无论成功失败都尝试登出，避免会话悬挂。"""

    def __init__(self) -> None:
        """创建登录客户端并占位配置与 Cookie。"""
        self._config: NoobDreamCrawlerConfig | None = None
        self._client = NoobDreamLoginClient()
        self._cookies = None

    def __enter__(self) -> tuple[NoobDreamCrawlerConfig, str]:
        """登录并返回配置与 Cookie 请求头。"""
        self._config = NoobDreamCrawlerConfig.from_env_file()
        self._cookies = self._client.login(self._config)
        return self._config, self._cookies.header_value

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> bool:
        """尽最大努力登出，不掩盖业务异常。"""
        if self._config is not None and self._cookies is not None:
            try:
                self._client.logout(self._config, self._cookies)
            except Exception:  # noqa: BLE001 - 登出失败不应影响已完成的采集结果
                pass
        return False


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
