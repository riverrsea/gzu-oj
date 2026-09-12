"""构造对宿主环境更宽容的 httpx 客户端。

WSL 和 Windows 常把 ``<local>``、``[::1]`` 这类条目写进 ``no_proxy``。
httpx 会把每个条目转成 ``all://*<entry>`` 并交给 URL 解析，碰到方括号 IPv6 字面量
会直接抛 ``InvalidURL``，导致客户端根本无法创建。这里在创建客户端前剔除语法上
不可能出现在 ``no_proxy`` 里的条目，其余代理配置（``http_proxy``、``https_proxy`` 等）
保持原样生效。
"""

from __future__ import annotations

import os
import re

import httpx

#: 需要清理的 NO_PROXY 环境变量名；大小写两种写法都要覆盖。
_NO_PROXY_KEYS = ("no_proxy", "NO_PROXY")

#: 合法的 NO_PROXY 条目：主机名、通配前缀，或带端口的主机名。
#: IPv6 字面量（``[::1]``、``::1``）和 Windows 的 ``<local>`` 都落在这个语法之外。
_SAFE_NO_PROXY_ENTRY = re.compile(r"^[A-Za-z0-9*._-]+(?::\d+)?$")

#: 站点请求通用的超时：连接 15 秒、整体 30 秒，对应 Kotlin 版的 HttpClient 配置。
DEFAULT_TIMEOUT = httpx.Timeout(30.0, connect=15.0)


def build_client(
    *,
    follow_redirects: bool,
    timeout: httpx.Timeout = DEFAULT_TIMEOUT,
) -> httpx.Client:
    """创建 httpx 客户端，并先清理 NO_PROXY 中 httpx 解析不了的条目。"""
    sanitize_no_proxy()
    return httpx.Client(follow_redirects=follow_redirects, timeout=timeout)


def sanitize_no_proxy() -> None:
    """把 NO_PROXY 收敛为 httpx 能解析的条目集合。

    被剔除的条目只会让这些地址改走代理，不影响访问目标站点。
    """
    for key in _NO_PROXY_KEYS:
        raw = os.environ.get(key)
        if raw is None:
            continue
        entries = (entry.strip() for entry in raw.split(","))
        os.environ[key] = ",".join(entry for entry in entries if _SAFE_NO_PROXY_ENTRY.match(entry))
