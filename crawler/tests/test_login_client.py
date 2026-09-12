"""登录/登出客户端测试：请求头、CSRF 表单和失败拦截。"""

from __future__ import annotations

import httpx
import pytest
import respx

from gzu_oj_crawler.auth import (
    NoobDreamCookies,
    NoobDreamCrawlerConfig,
    NoobDreamLoginClient,
)
from gzu_oj_crawler.errors import CrawlerError
from gzu_oj_crawler.http_client import build_client

_LOGIN_URL = "https://noobdream.com/users/login/"
_LOGOUT_URL = "https://noobdream.com/users/logout/"

# 站点登录页的关键结构：隐藏的 CSRF 表单字段。
_LOGIN_PAGE = "<input type='hidden' name='csrfmiddlewaretoken' value='form-token' />"


def _config() -> NoobDreamCrawlerConfig:
    """构造测试用登录配置。"""
    return NoobDreamCrawlerConfig(
        login_url=_LOGIN_URL,
        logout_url=_LOGOUT_URL,
        username="demo-user",
        password="demo-password",
    )


@respx.mock
def test_login_sends_crawler_user_agent_and_reads_session_cookie() -> None:
    """登录必须带上爬虫 User-Agent，否则站点会重新返回登录页。"""
    respx.get(_LOGIN_URL).mock(
        return_value=httpx.Response(
            200,
            text=_LOGIN_PAGE,
            headers=[("set-cookie", "csrftoken=cookie-token; Path=/")],
        ),
    )
    post_route = respx.post(_LOGIN_URL).mock(
        return_value=httpx.Response(
            302,
            headers=[
                ("set-cookie", "csrftoken=new-token; Path=/"),
                ("set-cookie", "sessionid=session-token; Path=/; HttpOnly"),
                ("location", "/DreamJudge/Issue/page/0/"),
            ],
        ),
    )

    client = build_client(follow_redirects=False)
    try:
        cookies = NoobDreamLoginClient(client=client).login(_config())
    finally:
        client.close()

    assert cookies.header_value == "csrftoken=new-token; sessionid=session-token"
    request = post_route.calls.last.request
    assert request.headers["user-agent"].startswith("GZU-OJ-Crawler/")
    assert request.headers["x-csrftoken"] == "form-token"
    body = request.content.decode("utf-8")
    assert "csrfmiddlewaretoken=form-token" in body
    assert "user_name=demo-user" in body
    assert "user_password=demo-password" in body


@respx.mock
def test_login_rejects_rejected_credentials() -> None:
    """站点重新渲染登录页时不能把匿名 sessionid 当成登录成功。"""
    respx.get(_LOGIN_URL).mock(
        return_value=httpx.Response(
            200,
            text=_LOGIN_PAGE,
            headers=[
                ("set-cookie", "csrftoken=cookie-token; Path=/"),
                ("set-cookie", "sessionid=anonymous; Path=/"),
            ],
        ),
    )
    respx.post(_LOGIN_URL).mock(return_value=httpx.Response(200, text=_LOGIN_PAGE))

    client = build_client(follow_redirects=False)
    try:
        with pytest.raises(CrawlerError, match="登录被站点拒绝"):
            NoobDreamLoginClient(client=client).login(_config())
    finally:
        client.close()


@respx.mock
def test_logout_posts_with_session_cookie() -> None:
    """登出使用 POST 并携带会话 Cookie 与 CSRF 头。"""
    route = respx.post(_LOGOUT_URL).mock(return_value=httpx.Response(302))

    client = build_client(follow_redirects=False)
    try:
        login_client = NoobDreamLoginClient(client=client)
        login_client.logout(_config(), NoobDreamCookies("csrf-token", "session-token"))
    finally:
        client.close()

    request = route.calls.last.request
    assert request.headers["cookie"] == "csrftoken=csrf-token; sessionid=session-token"
    assert request.headers["x-csrftoken"] == "csrf-token"
