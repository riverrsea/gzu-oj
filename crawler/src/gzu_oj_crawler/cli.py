"""爬虫 CLI 入口。

支持四个子命令，与 Kotlin 版 ``crawler-cli`` 完全对齐：

* ``local <canonical-problems.json> <output.zip>``
* ``noobdream-import <details.csv> <output.zip> [--default-year YYYY]``
* ``noobdream-list <list-url> <output.csv> [--single-page]``
* ``noobdream-problems <list-url> <output.csv> [--single-page]``
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
from .list_crawler import NoobDreamListClient, write_list_csv
from .problem_crawler import NoobDreamProblemClient, write_problem_csv


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

    convert = subparsers.add_parser("noobdream-import", help="把详情 CSV 转成无测试点导入包")
    convert.add_argument("source", help="noobdream-problems 生成的详情 CSV")
    convert.add_argument("target", help="输出 ZIP 路径")
    convert.add_argument("--default-year", type=int, default=None, help="填充 CSV 中为空的年份")

    listing = subparsers.add_parser("noobdream-list", help="采集题目列表 CSV")
    listing.add_argument("url", help="N 诺题库列表页地址")
    listing.add_argument("target", help="输出 CSV 路径")
    listing.add_argument("--single-page", action="store_true", help="只抓取给定页面，不翻页")

    problems = subparsers.add_parser("noobdream-problems", help="采集题目详情 CSV")
    problems.add_argument("url", help="N 诺题库列表页地址")
    problems.add_argument("target", help="输出 CSV 路径")
    problems.add_argument("--single-page", action="store_true", help="只抓取给定页面，不翻页")

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """执行子命令；业务错误只打印消息并返回非零退出码。"""
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "local":
            _run_local(Path(args.source), Path(args.target))
        elif args.command == "noobdream-import":
            _run_import(Path(args.source), Path(args.target), args.default_year)
        elif args.command == "noobdream-list":
            _run_list(args.url, Path(args.target), args.single_page)
        elif args.command == "noobdream-problems":
            _run_problems(args.url, Path(args.target), args.single_page)
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


def _run_import(source: Path, target: Path, default_year: int | None) -> None:
    """详情 CSV → 无测试点标准导入包。"""
    target_path = target.absolute()
    NoobDreamImportConverter().convert(source.absolute(), target_path, default_year)
    print(f"已生成无测试点标准导入包：{target_path}")


def _run_list(url: str, target: Path, single_page: bool) -> None:
    """采集列表页并写入 CSV；采集结束后登出。"""
    target_path = target.absolute()
    with _LoggedInSession() as (_, cookie_header):
        client = NoobDreamListClient(cookie_header=cookie_header)
        items = client.fetch(url) if single_page else client.fetch_all(url)
        write_list_csv(items, target_path)
        scope = "（单页）" if single_page else "（全部分页）"
        print(f"已采集 {len(items)} 道题目列表{scope}并写入 CSV：{target_path}")


def _run_problems(url: str, target: Path, single_page: bool) -> None:
    """先采集列表，再逐题抓取详情并写入 CSV；采集结束后登出。"""
    target_path = target.absolute()
    with _LoggedInSession() as (_, cookie_header):
        list_client = NoobDreamListClient(cookie_header=cookie_header)
        list_items = list_client.fetch(url) if single_page else list_client.fetch_all(url)
        problems = NoobDreamProblemClient(cookie_header=cookie_header).fetch_all(list_items)
        write_problem_csv(problems, target_path)
        print(f"已抓取 {len(problems)} 道题目详情并写入 CSV：{target_path}")


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
