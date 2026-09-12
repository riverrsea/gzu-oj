package cn.gzuoj.crawler

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.jsoup.Jsoup
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

/** N 诺题库列表中的一行；详情页地址供后续登录采集使用。 */
data class NoobDreamListItem(
    /** 系统外部题目标识，使用来源命名空间避免与其他网站题号冲突。 */
    val externalKey: String,
    /** 外部题号，保留网站原始数字字符串。 */
    val problemId: String,
    /** 题目标题。 */
    val title: String,
    /** 网站展示的难度文本，例如“简单”。 */
    val difficulty: String,
    /** 网站展示的题型文本，例如“简单模拟”。 */
    val problemType: String,
    /** 从来源文字中识别出的学校名；无法识别时为空。 */
    val school: String?,
    /** 网站原始来源文字，仅用于详情补全和学校、年份提取。 */
    val sourceDescription: String?,
    /** 详情页绝对地址。 */
    val detailUrl: String,
)

/** 一个列表页的题目和站点声明的总页数。 */
data class NoobDreamListPage(
    /** 当前页题目。 */
    val items: List<NoobDreamListItem>,
    /** 当前筛选条件下的总页数。 */
    val totalPages: Int,
)

/** 解析 N 诺题库列表页，不进入需要登录的题目详情页。 */
class NoobDreamListParser {
    /** 从 HTML 表格中解析题目行。 */
    fun parse(html: String, pageUri: URI): List<NoobDreamListItem> = parsePage(html, pageUri).items

    /** 同时解析题目行和列表页的分页总数。 */
    fun parsePage(html: String, pageUri: URI): NoobDreamListPage {
        val document = Jsoup.parse(html, pageUri.toString())
        val rows = document.select("table tbody tr")
        require(rows.isNotEmpty()) { "列表页没有找到题目表格：$pageUri" }
        val items = rows.mapIndexed { index, row ->
            val cells = row.select("td")
            require(cells.size >= 5) { "第 ${index + 1} 个题目行字段不足：$pageUri" }
            val titleAnchor = cells[2].selectFirst("a[href*=/DreamJudge/Issue/page/]")
                ?: error("第 ${index + 1} 个题目行没有详情链接：$pageUri")
            val detailUrl = pageUri.resolve(titleAnchor.attr("href")).toString()
            val problemId = cells[1].text().trim()
            require(problemId.matches(Regex("^[0-9]+$"))) { "题号不是数字：$problemId" }
            val title = titleAnchor.ownText().trim()
            require(title.isNotBlank()) { "题目标题为空：$problemId" }
            val sourceDescription = cells[2].selectFirst(".tag-source")?.text()?.trim()?.takeIf(String::isNotBlank)
            NoobDreamListItem(
                externalKey = "noobdream:$problemId",
                problemId = problemId,
                title = title,
                difficulty = cells[3].selectFirst(".level-tag")?.text()?.trim().orEmpty(),
                problemType = cells[4].text().trim(),
                school = extractNoobDreamSchool(sourceDescription),
                sourceDescription = sourceDescription,
                detailUrl = detailUrl,
            )
        }.also { items ->
            require(items.map(NoobDreamListItem::problemId).distinct().size == items.size) {
                "列表页包含重复题号：$pageUri"
            }
        }
        val totalPages = document.select(".page-num .page-link")
            .mapNotNull { it.text().trim().toIntOrNull() }
            .maxOrNull()
            ?: 1
        return NoobDreamListPage(items, totalPages)
    }
}

/** 通过 Java 21 HttpClient 请求公开的 N 诺题库列表页。 */
class NoobDreamListClient(
    /** HTTP 客户端；构造器注入便于测试。 */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
    /** 列表 HTML 解析器。 */
    private val parser: NoobDreamListParser = NoobDreamListParser(),
    /** 登录后得到的 Cookie 请求头；为空时兼容公开列表采集。 */
    private val cookieHeader: String? = null,
) {
    /** 请求并解析一个列表页；配置会话时携带登录 Cookie。 */
    fun fetch(pageUri: URI): List<NoobDreamListItem> {
        return requestPage(pageUri).items
    }

    /** 从第一页开始顺序抓取当前筛选条件下的全部分页。 */
    fun fetchAll(firstPageUri: URI): List<NoobDreamListItem> {
        val firstPage = requestPage(firstPageUri)
        val allItems = firstPage.items.toMutableList()
        for (pageNumber in 2..firstPage.totalPages) {
            allItems += requestPage(pageUri(firstPageUri, pageNumber)).items
        }
        return allItems
            .distinctBy(NoobDreamListItem::problemId)
            .also { items ->
                require(items.isNotEmpty()) { "全部分页没有采集到题目：$firstPageUri" }
            }
    }

    /** 请求并解析单个页面，同时读取该筛选条件的分页总数。 */
    private fun requestPage(pageUri: URI): NoobDreamListPage {
        require(pageUri.scheme == "http" || pageUri.scheme == "https") { "列表地址必须是 HTTP(S) URL" }
        val request = HttpRequest.newBuilder(pageUri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", USER_AGENT)
            .apply { if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader) }
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        require(response.statusCode() in 200..299) { "列表页请求失败：HTTP ${response.statusCode()} $pageUri" }
        return parser.parsePage(response.body(), pageUri)
    }

    /** 构造下一页 URL，保留原有筛选条件并覆盖 page 参数。 */
    private fun pageUri(firstPageUri: URI, pageNumber: Int): URI {
        val base = firstPageUri.toString().substringBefore('#').substringBefore('?')
        val query = firstPageUri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() && !it.substringBefore('=').equals("page", ignoreCase = true) }
            .toMutableList()
            .apply { add("page=$pageNumber") }
            .joinToString("&")
        return URI.create("$base?$query")
    }

    private companion object {
        /** 说明访问用途的稳定爬虫 User-Agent。 */
        const val USER_AGENT = "GZU-OJ-Crawler/0.1 (+https://noobdream.com/DreamJudge/Issue/page/0/)"
    }
}

/** 将列表采集结果写为单个 UTF-8 CSV 文件。 */
class NoobDreamListCsvWriter {
    /** 覆盖写入目标文件，并确保父目录存在。 */
    fun write(items: List<NoobDreamListItem>, target: Path) {
        require(items.isNotEmpty()) { "没有可写入 CSV 的题目" }
        val normalizedTarget = target.toAbsolutePath().normalize()
        normalizedTarget.parent?.let(Files::createDirectories)
        val output = ByteArrayOutputStream()
        BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
            CSVPrinter(writer, CSVFormat.DEFAULT).use { csv ->
                csv.printRecord(HEADERS)
                items.forEach { item ->
                    csv.printRecord(
                        item.externalKey,
                        item.problemId,
                        item.title,
                        item.difficulty,
                        item.problemType,
                        item.school.orEmpty(),
                        item.detailUrl,
                    )
                }
            }
        }
        Files.write(
            normalizedTarget,
            output.toByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private companion object {
        /** CSV 字段固定顺序，避免后续导入脚本依赖页面顺序。 */
        val HEADERS = listOf(
            "externalKey", "problemId", "title", "difficulty", "problemType", "school", "detailUrl",
        )
    }
}

/** 从来源文字中提取一个或多个学校名；“真题”等站点标签不会作为学校。 */
internal fun extractNoobDreamSchool(sourceDescription: String?): String? {
    if (sourceDescription.isNullOrBlank()) return null
    val schools = SCHOOL_REGEX.findAll(sourceDescription)
        .map { it.value.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    return schools.takeIf { it.isNotEmpty() }?.joinToString("/")
}

/** 从来源文字中提取明确标注的年份，未标注时为空。 */
internal fun extractNoobDreamYear(sourceDescription: String?): Int? =
    sourceDescription?.let { YEAR_REGEX.find(it)?.value?.toIntOrNull() }

/** 学校名称常见结尾；允许一个来源同时包含多所学校。 */
private val SCHOOL_REGEX = Regex("[\\p{IsHan}A-Za-z0-9]+?(?:大学|学院|研究所|科学院)")

/** 来源文字中的四位年份。 */
private val YEAR_REGEX = Regex("(?:19|20)\\d{2}")

/** 读取 HTML 文本并复用同一解析器，供离线测试使用。 */
internal fun parseNoobDreamListHtml(html: String, pageUri: URI): List<NoobDreamListItem> =
    NoobDreamListParser().parse(html, pageUri)
