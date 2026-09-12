"""N 诺登录配置和 Cookie 响应头解析测试。"""

from __future__ import annotations

from pathlib import Path

from gzu_oj_crawler.auth import NoobDreamCookies, NoobDreamCrawlerConfig


def test_reads_env_aliases(tmp_path: Path) -> None:
    """支持用户指定的键名，并兼容当前 .env.crawler 中的旧别名。"""
    env_file = tmp_path / ".env.crawler"
    env_file.write_text(
        "\n".join(
            [
                "user_name='demo-user'",
                'user_password="demo-password"',
                "login_url=https://noobdream.com/users/login/",
            ],
        ),
        encoding="utf-8",
    )
    config = NoobDreamCrawlerConfig.from_env_file(env_file)
    assert config.username == "demo-user"
    assert config.password == "demo-password"
    assert config.login_url == "https://noobdream.com/users/login/"
    # 未配置 logout_url 时按登录地址推导站点默认登出路径。
    assert config.logout_url == "https://noobdream.com/users/logout/"


def test_reads_legacy_env_keys(tmp_path: Path) -> None:
    """兼容当前仓库 .env.crawler 使用的 noobdream_account / noobdream_pwd。"""
    env_file = tmp_path / ".env.crawler"
    env_file.write_text(
        "\n".join(
            [
                "noobdream_account=legacy-user",
                "noobdream_pwd=legacy-password",
                "login_url=https://noobdream.com/users/login/",
                "logout_url=https://noobdream.com/users/logout/",
            ],
        ),
        encoding="utf-8",
    )
    config = NoobDreamCrawlerConfig.from_env_file(env_file)
    assert config.username == "legacy-user"
    assert config.password == "legacy-password"
    assert config.logout_url == "https://noobdream.com/users/logout/"


def test_reads_session_cookies() -> None:
    """从多个 Set-Cookie 头中提取会话所需的两个 Cookie。"""
    cookies = NoobDreamCookies.from_set_cookie_headers(
        [
            "csrftoken=csrf-value; Path=/; SameSite=Lax",
            "sessionid=session-value; Path=/; HttpOnly",
        ],
    )
    assert cookies.csrf_token == "csrf-value"
    assert cookies.session_id == "session-value"
    assert cookies.header_value == "csrftoken=csrf-value; sessionid=session-value"
