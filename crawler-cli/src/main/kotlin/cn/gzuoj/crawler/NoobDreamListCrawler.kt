package cn.gzuoj.crawler

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

/** N 诺题库列表中的一行；详情页内容需要登录，暂不在此阶段抓取。 */
data class NoobDreamListItem(
    /** 外部题号，保留网站原始数字字符串。 */
    val problemId: String,
    /** 题目标题。 */
    val title: String,
    /** 网站展示的难度文本，例如“简单”。 */
    val difficulty: String,
    /** 网站展示的题型文本，例如“简单模拟”。 */
    val problemType: String,
    /** 题目名称旁的学校标签，可能为空或为“真题”。 */
    val schoolTag: String?,
    /** 题目名称旁的来源标签，例如“贵州大学机试题”。 */
    val sourceTag: String?,
    /** 详情页绝对地址。 */
    val detailUrl: String,
    /** 列表页绝对地址。 */
    val sourcePageUrl: String,
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
            NoobDreamListItem(
                problemId = problemId,
                title = title,
                difficulty = cells[3].selectFirst(".level-tag")?.text()?.trim().orEmpty(),
                problemType = cells[4].text().trim(),
                schoolTag = cells[2].selectFirst(".tag-school")?.text()?.trim()?.takeIf(String::isNotBlank),
                sourceTag = cells[2].selectFirst(".tag-source")?.text()?.trim()?.takeIf(String::isNotBlank),
                detailUrl = detailUrl,
                sourcePageUrl = pageUri.toString(),
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
) {
    /** 请求并解析一个列表页；不携带登录 Cookie。 */
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
                        item.problemId,
                        item.title,
                        item.difficulty,
                        item.problemType,
                        item.schoolTag.orEmpty(),
                        item.sourceTag.orEmpty(),
                        item.detailUrl,
                        item.sourcePageUrl,
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
            "problemId", "title", "difficulty", "problemType", "schoolTag", "sourceTag", "detailUrl", "sourcePageUrl",
        )
    }
}

/** 读取 HTML 文本并复用同一解析器，供离线测试使用。 */
internal fun parseNoobDreamListHtml(html: String, pageUri: URI): List<NoobDreamListItem> =
    NoobDreamListParser().parse(html, pageUri)
