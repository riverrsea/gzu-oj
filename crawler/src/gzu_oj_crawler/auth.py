"""登录配置、Cookie 解析与 N 诺登录/登出客户端。"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urljoin, urlparse

import httpx
from bs4 import BeautifulSoup

from .errors import require
from .http_client import build_client

#: 说明访问用途的稳定爬虫 User-Agent。
USER_AGENT = "GZU-OJ-Crawler/0.1 (+https://noobdream.com/DreamJudge/Issue/page/0/)"

#: 表单默认请求头，登录页和登出接口共用。
#: User-Agent 必须显式带上：站点会拒绝未知 UA 的登录提交，并重新返回登录页。
_FORM_HEADERS = {
    "Accept": "text/html,application/xhtml+xml",
    "Accept-Language": "zh-CN,zh;q=0.9",
    "User-Agent": USER_AGENT,
}


@dataclass(slots=True)
class NoobDreamCrawlerConfig:
    """N 诺爬虫登录配置；密码只存在内存中，不参与日志和 CSV 输出。"""

    #: 登录接口或登录页地址。
    login_url: str
    #: 登出接口地址。
    logout_url: str
    #: 登录用户名。
    username: str
    #: 登录密码。
    password: str

    @classmethod
    def from_env_file(cls, path: Path | None = None) -> NoobDreamCrawlerConfig:
        """从项目根目录的 ``.env.crawler`` 读取登录配置。"""
        env_path = path or default_env_path()
        require(env_path.is_file(), f"爬虫环境文件不存在：{env_path}")
        values = _parse_env(env_path.read_text(encoding="utf-8"))
        username = values.get("user_name") or values.get("noobdream_account")
        password = values.get("user_password") or values.get("noobdream_pwd")
        require(username and username.strip(), ".env.crawler 缺少 user_name")
        require(password and password.strip(), ".env.crawler 缺少 user_password")
        login_url = (values.get("login_url") or "").strip()
        require(login_url, ".env.crawler 缺少 login_url")
        logout_url = (values.get("logout_url") or "").strip() or urljoin(login_url, "/users/logout/")
        return cls(login_url=login_url, logout_url=logout_url, username=username, password=password)


@dataclass(slots=True)
class NoobDreamCookies:
    """登录成功后需要附加到请求的两个 Cookie。"""

    #: Django CSRF Cookie。
    csrf_token: str
    #: 登录会话 Cookie。
    session_id: str

    @property
    def header_value(self) -> str:
        """HTTP Cookie 请求头值。"""
        return f"csrftoken={self.csrf_token}; sessionid={self.session_id}"

    @classmethod
    def from_set_cookie_headers(cls, headers: list[str]) -> NoobDreamCookies:
        """从一个或多个 Set-Cookie 响应头解析登录 Cookie。"""
        values: dict[str, str] = {}
        for header in headers:
            pair = header.split(";", 1)[0]
            name, separator, value = pair.partition("=")
            if separator:
                values[name.strip()] = value.strip()
        csrf_token = values.get("csrftoken")
        session_id = values.get("sessionid")
        require(
            csrf_token and session_id,
            "登录响应没有同时返回 csrftoken 和 sessionid Cookie",
        )
        return cls(csrf_token=csrf_token, session_id=session_id)


def default_env_path() -> Path:
    """定位 ``.env.crawler``；兼容从仓库根目录和 ``crawler/`` 目录运行。"""
    candidates = [Path(".env.crawler"), Path("..", ".env.crawler")]
    for candidate in candidates:
        resolved = candidate.absolute()
        if resolved.is_file():
            return resolved
    return Path(".env.crawler").absolute()


def _parse_env(text: str) -> dict[str, str]:
    """解析 ``KEY=VALUE`` 文本，跳过空行与注释，并支持引号包裹的值。"""
    values: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = _unquote(value.strip())
    return values


def _unquote(value: str) -> str:
    """支持 .env 常见的单引号或双引号包裹值。"""
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


class NoobDreamLoginClient:
    """负责登录 N 诺并提取响应头中的会话 Cookie。"""

    def __init__(self, client: httpx.Client | None = None) -> None:
        """注入 HTTP 客户端；默认不跟随重定向，确保捕获首次响应的 Set-Cookie。"""
        self._client = client or build_client(follow_redirects=False)

    def login(self, config: NoobDreamCrawlerConfig) -> NoobDreamCookies:
        """GET 登录页获取初始 CSRF，再 POST 表单完成登录。"""
        _require_http_url(config.login_url, "登录地址必须是 HTTP(S) URL")
        login_page = self._client.get(config.login_url, headers=_FORM_HEADERS)
        require(
            login_page.status_code < 400,
            f"登录页请求失败：HTTP {login_page.status_code}",
        )
        initial_headers = login_page.headers.get_list("set-cookie")
        csrf_cookie = _parse_cookie(initial_headers, "csrftoken")
        csrf_form_value = _form_csrf(login_page.text)

        form = {"user_name": config.username, "user_password": config.password}
        if csrf_form_value:
            form["csrfmiddlewaretoken"] = csrf_form_value
        headers = dict(_FORM_HEADERS)
        headers["Referer"] = config.login_url
        if csrf_cookie:
            headers["Cookie"] = f"csrftoken={csrf_cookie}"
        if csrf_form_value:
            headers["X-CSRFToken"] = csrf_form_value

        response = self._client.post(config.login_url, data=form, headers=headers)
        require(response.status_code < 400, f"登录请求失败：HTTP {response.status_code}")
        # 登录失败时 Django 会重新渲染登录页（HTTP 200）并继续下发匿名 sessionid，
        # 只检查状态码会把匿名会话当成登录成功，导致后续详情全部被重定向。这里显式拦截。
        require(
            300 <= response.status_code <= 399,
            "登录被站点拒绝：请检查 .env.crawler 中的账号密码，以及请求 User-Agent 是否被拦截",
        )
        return NoobDreamCookies.from_set_cookie_headers(
            initial_headers + response.headers.get_list("set-cookie"),
        )

    def logout(self, config: NoobDreamCrawlerConfig, cookies: NoobDreamCookies) -> None:
        """使用 POST 携带会话 Cookie 登出；响应允许重定向。"""
        _require_http_url(config.logout_url, "登出地址必须是 HTTP(S) URL")
        headers = dict(_FORM_HEADERS)
        headers.update(
            {
                "Cookie": cookies.header_value,
                "X-CSRFToken": cookies.csrf_token,
                "Referer": config.login_url,
            },
        )
        response = self._client.post(config.logout_url, content=b"", headers=headers)
        require(response.status_code < 400, f"登出请求失败：HTTP {response.status_code}")


def _form_csrf(html: str) -> str | None:
    """读取登录表单里的 ``csrfmiddlewaretoken`` 隐藏字段。"""
    field = BeautifulSoup(html, "html.parser").select_one("input[name=csrfmiddlewaretoken]")
    value = field.get("value") if field else None
    return value if isinstance(value, str) and value.strip() else None


def _parse_cookie(headers: list[str], name: str) -> str | None:
    """从 Set-Cookie 列表读取指定名称；重复出现时取最后一个。"""
    found: str | None = None
    for header in headers:
        pair = header.split(";", 1)[0]
        cookie_name, separator, value = pair.partition("=")
        if separator and cookie_name.strip() == name:
            found = value.strip()
    return found


def _require_http_url(url: str, message: str) -> None:
    """校验地址协议，避免误把本地路径当成站点地址。"""
    require(urlparse(url).scheme in {"http", "https"}, message)
