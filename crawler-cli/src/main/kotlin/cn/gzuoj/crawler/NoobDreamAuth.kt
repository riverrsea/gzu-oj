package cn.gzuoj.crawler

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** N 诺爬虫登录配置；密码只存在内存中，不参与日志和 CSV 输出。 */
data class NoobDreamCrawlerConfig(
    /** 登录接口或登录页地址。 */
    val loginUrl: URI,
    /** 登出接口地址。 */
    val logoutUrl: URI,
    /** 登录用户名。 */
    val username: String,
    /** 登录密码。 */
    val password: String,
) {
    companion object {
        /** 从项目根目录的 .env.crawler 读取登录配置。 */
        fun fromEnvFile(path: Path = defaultEnvPath()): NoobDreamCrawlerConfig {
            require(Files.isRegularFile(path)) { "爬虫环境文件不存在：$path" }
            val values = Files.readAllLines(path, StandardCharsets.UTF_8)
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator).trim() to unquote(line.substring(separator + 1).trim())
                }
                .toMap()
            val username = values["user_name"] ?: values["noobdream_account"]
            val password = values["user_password"] ?: values["noobdream_pwd"]
            require(!username.isNullOrBlank()) { ".env.crawler 缺少 user_name" }
            require(!password.isNullOrBlank()) { ".env.crawler 缺少 user_password" }
            val loginUrl = values["login_url"]?.trim()?.takeIf(String::isNotBlank)
                ?: error(".env.crawler 缺少 login_url")
            val loginUri = URI.create(loginUrl)
            val logoutUri = values["logout_url"]?.trim()?.takeIf(String::isNotBlank)?.let(URI::create)
                ?: loginUri.resolve("/users/logout/")
            return NoobDreamCrawlerConfig(loginUri, logoutUri, username, password)
        }

        /** 支持 .env 常见的单引号或双引号包裹值。 */
        private fun unquote(value: String): String =
            if (value.length >= 2 && value.first() == value.last() && value.first() in setOf('\'', '"')) {
                value.substring(1, value.length - 1)
            } else {
                value
            }

        /** Gradle run 通常以模块目录为工作目录，兼容项目根目录配置文件。 */
        private fun defaultEnvPath(): Path = sequenceOf(
            Path.of(".env.crawler"),
            Path.of("..", ".env.crawler"),
        ).map { it.toAbsolutePath().normalize() }
            .firstOrNull(Files::isRegularFile)
            ?: Path.of(".env.crawler")
    }
}

/** 登录成功后需要附加到请求的两个 Cookie。 */
data class NoobDreamCookies(
    /** Django CSRF Cookie。 */
    val csrfToken: String,
    /** 登录会话 Cookie。 */
    val sessionId: String,
) {
    /** HTTP Cookie 请求头值。 */
    val headerValue: String get() = "csrftoken=$csrfToken; sessionid=$sessionId"

    companion object {
        /** 从一个或多个 Set-Cookie 响应头解析登录 Cookie。 */
        fun fromSetCookieHeaders(headers: List<String>): NoobDreamCookies {
            val values = headers.asSequence()
                .map { it.substringBefore(';') }
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) null else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
                }
                .toMap()
            val csrfToken = values["csrftoken"]
            val sessionId = values["sessionid"]
            require(!csrfToken.isNullOrBlank() && !sessionId.isNullOrBlank()) {
                "登录响应没有同时返回 csrftoken 和 sessionid Cookie"
            }
            return NoobDreamCookies(csrfToken, sessionId)
        }
    }
}

/** 负责登录 N 诺并提取响应头中的会话 Cookie。 */
class NoobDreamLoginClient(
    /** 登录专用客户端不自动跟随重定向，确保捕获首次响应的 Set-Cookie。 */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {
    /** GET 登录页获取初始 CSRF，再 POST 表单完成登录。 */
    fun login(config: NoobDreamCrawlerConfig): NoobDreamCookies {
        require(config.loginUrl.scheme == "http" || config.loginUrl.scheme == "https") { "登录地址必须是 HTTP(S) URL" }
        val loginPage = sendGet(config.loginUrl)
        val initialHeaders = loginPage.headers().allValues("Set-Cookie")
        val csrfCookie = parseCookie(initialHeaders, "csrftoken")
        val csrfFormValue = Jsoup.parse(loginPage.body()).selectFirst("input[name=csrfmiddlewaretoken]")?.attr("value")
        val form = linkedMapOf(
            "user_name" to config.username,
            "user_password" to config.password,
        )
        if (!csrfFormValue.isNullOrBlank()) form["csrfmiddlewaretoken"] = csrfFormValue
        val request = HttpRequest.newBuilder(config.loginUrl)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .header("Referer", config.loginUrl.toString())
            .apply { if (!csrfCookie.isNullOrBlank()) header("Cookie", "csrftoken=$csrfCookie") }
            .apply { if (!csrfFormValue.isNullOrBlank()) header("X-CSRFToken", csrfFormValue) }
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }, StandardCharsets.UTF_8))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        require(response.statusCode() in 200..399) { "登录请求失败：HTTP ${response.statusCode()}" }
        return NoobDreamCookies.fromSetCookieHeaders(initialHeaders + response.headers().allValues("Set-Cookie"))
    }

    /** 使用 POST 携带会话 Cookie 登出；响应允许重定向。 */
    fun logout(config: NoobDreamCrawlerConfig, cookies: NoobDreamCookies) {
        require(config.logoutUrl.scheme == "http" || config.logoutUrl.scheme == "https") { "登出地址必须是 HTTP(S) URL" }
        val request = HttpRequest.newBuilder(config.logoutUrl)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", cookies.headerValue)
            .header("X-CSRFToken", cookies.csrfToken)
            .header("Referer", config.loginUrl.toString())
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        require(response.statusCode() in 200..399) { "登出请求失败：HTTP ${response.statusCode()}" }
    }

    /** 读取登录页，允许 2xx/3xx 页面继续进入 POST 步骤。 */
    private fun sendGet(uri: URI): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        require(response.statusCode() in 200..399) { "登录页请求失败：HTTP ${response.statusCode()}" }
        return response
    }

    /** 从 Set-Cookie 列表读取指定名称，值只在内存中使用。 */
    private fun parseCookie(headers: List<String>, name: String): String? = headers.asSequence()
        .map { it.substringBefore(';') }
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0 || part.substring(0, separator).trim() != name) null else part.substring(separator + 1).trim()
        }
        .lastOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        /** 说明访问用途的稳定爬虫 User-Agent。 */
        const val USER_AGENT = "GZU-OJ-Crawler/0.1 (+https://noobdream.com/DreamJudge/Issue/page/0/)"
    }
}
