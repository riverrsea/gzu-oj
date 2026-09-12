"""httpx 客户端构造测试：兼容 WSL/Windows 常见的 NO_PROXY 写法。"""

from __future__ import annotations

import os

import pytest

from gzu_oj_crawler.http_client import build_client, sanitize_no_proxy


def test_sanitize_drops_unparsable_no_proxy_entries(monkeypatch: pytest.MonkeyPatch) -> None:
    """WSL 常写的 <local>、[::1] 会让 httpx 抛 InvalidURL，必须被剔除。"""
    monkeypatch.setenv("no_proxy", "127.0.0.1,localhost,[::1],<local>,172.16.*")
    monkeypatch.setenv("NO_PROXY", "127.0.0.1,[::1]")

    sanitize_no_proxy()

    assert os.environ["no_proxy"] == "127.0.0.1,localhost,172.16.*"
    assert os.environ["NO_PROXY"] == "127.0.0.1"


def test_build_client_survives_hostile_no_proxy(monkeypatch: pytest.MonkeyPatch) -> None:
    """带非法条目的 NO_PROXY 不再阻止客户端创建。"""
    monkeypatch.setenv("no_proxy", "[::1],<local>")
    monkeypatch.setenv("NO_PROXY", "[::1],<local>")

    client = build_client(follow_redirects=False)
    try:
        # 条目被全部剔除后仍然是可用的客户端实例。
        assert client.follow_redirects is False
    finally:
        client.close()
