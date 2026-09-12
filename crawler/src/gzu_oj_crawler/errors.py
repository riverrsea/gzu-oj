"""爬虫可预期的业务错误。

约定：所有字段校验、契约校验和网络状态校验都抛出 :class:`CrawlerError`，
CLI 层统一捕获后只打印消息、不输出堆栈，避免把 Cookie、密码等上下文带进日志。
"""

from __future__ import annotations

from typing import NoReturn


class CrawlerError(ValueError):
    """输入、网络响应或数据契约不满足要求。"""


def require(condition: object, message: str) -> None:
    """断言条件成立，否则抛出 :class:`CrawlerError`。

    对应 Kotlin 版的 ``require``；保留同名语义可以让两版迁移时的错误文案一一对应。
    """
    if not condition:
        raise CrawlerError(message)


def fail(message: str) -> NoReturn:
    """无条件抛出业务错误，用于 ``when/else`` 之类的兜底分支。"""
    raise CrawlerError(message)
