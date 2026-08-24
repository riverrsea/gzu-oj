package cn.gzuoj.crawler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** N 诺登录配置和 Cookie 响应头解析测试。 */
class NoobDreamAuthTest {
    /** 支持用户指定的键名，并兼容当前 .env.crawler 中的旧别名。 */
    @Test
    fun readsEnvAliases() {
        val file = Files.createTempFile("noobdream-crawler-", ".env")
        try {
            Files.writeString(
                file,
                """
                user_name='demo-user'
                user_password="demo-password"
                login_url=https://noobdream.com/users/login/
                """.trimIndent(),
            )
            val config = NoobDreamCrawlerConfig.fromEnvFile(file)
            assertEquals("demo-user", config.username)
            assertEquals("demo-password", config.password)
            assertEquals("https://noobdream.com/users/login/", config.loginUrl.toString())
            assertEquals("https://noobdream.com/users/logout/", config.logoutUrl.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** 从多个 Set-Cookie 头中提取会话所需的两个 Cookie。 */
    @Test
    fun readsSessionCookies() {
        val cookies = NoobDreamCookies.fromSetCookieHeaders(
            listOf(
                "csrftoken=csrf-value; Path=/; SameSite=Lax",
                "sessionid=session-value; Path=/; HttpOnly",
            ),
        )
        assertEquals("csrf-value", cookies.csrfToken)
        assertEquals("session-value", cookies.sessionId)
        assertEquals("csrftoken=csrf-value; sessionid=session-value", cookies.headerValue)
    }
}
