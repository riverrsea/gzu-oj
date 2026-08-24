package cn.gzuoj.crawler

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.jsoup.nodes.Element
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

/** 已登录题目详情页中供人工复核和后续导入转换使用的题目数据。 */
data class NoobDreamProblem(
    /** 系统外部题目标识。 */
    val externalKey: String,
    /** 外部题号。 */
    val problemId: String,
    /** 题目标题。 */
    val title: String,
    /** 列表页难度。 */
    val difficulty: String,
    /** 列表页题型。 */
    val problemType: String,
    /** 从题目来源中识别出的学校名；无法识别时为空。 */
    val school: String?,
    /** 来源中明确标注的年份；未标注时为空。 */
    val year: Int?,
    /** 时间限制，单位毫秒。 */
    val timeLimitMs: Int?,
    /** 内存限制，单位 MiB。 */
    val memoryLimitMiB: Int?,
    /** 规范化后的 Markdown 题面。 */
    val statementMarkdown: String,
    /** 输入描述原文。 */
    val inputDescription: String,
    /** 输出描述原文。 */
    val outputDescription: String,
    /** 公开输入样例。 */
    val sampleInput: String?,
    /** 公开输出样例。 */
    val sampleOutput: String?,
    /** 题目详情页地址，也是导入时的来源地址。 */
    val sourceUrl: String,
)

/** 解析登录后的 N 诺题目详情页。 */
class NoobDreamProblemParser {
    /** 从一条列表记录对应的详情 HTML 构造题目数据。 */
    fun parse(html: String, item: NoobDreamListItem): NoobDreamProblem {
        val document = Jsoup.parse(html, item.detailUrl)
        val title = document.selectFirst(".title-container h1, h1")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: item.title
        val limits = document.select(".bg-light")
            .firstOrNull { it.text().contains("Time Limit", ignoreCase = true) && it.text().contains("Memory Limit", ignoreCase = true) }
        val timeLimitMs = LIMIT_REGEX.find(limits?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val memoryLimitMiB = MEMORY_REGEX.find(limits?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val description = document.selectFirst(".OjInfo")?.let(::textOf).orEmpty()
        val inputDescription = sectionText(document, "输入描述")
        val outputDescription = sectionText(document, "输出描述")
        val sampleInput = document.selectFirst("pre#input")?.let(::textOf)?.takeIf(String::isNotBlank)
        val sampleOutput = document.selectFirst("pre#output")?.let(::textOf)?.takeIf(String::isNotBlank)
        val sourceDescription = sourceText(document) ?: item.sourceDescription
        return NoobDreamProblem(
            externalKey = item.externalKey,
            problemId = item.problemId,
            title = title,
            difficulty = item.difficulty,
            problemType = item.problemType,
            school = extractNoobDreamSchool(sourceDescription) ?: item.school,
            year = extractNoobDreamYear(sourceDescription),
            timeLimitMs = timeLimitMs,
            memoryLimitMiB = memoryLimitMiB,
            statementMarkdown = buildMarkdown(title, description, inputDescription, outputDescription, sampleInput, sampleOutput, sourceDescription),
            inputDescription = inputDescription,
            outputDescription = outputDescription,
            sampleInput = sampleInput,
            sampleOutput = sampleOutput,
            sourceUrl = item.detailUrl,
        )
    }

    /** 按标题定位输入或输出描述所在的内容块。 */
    private fun sectionText(document: org.jsoup.nodes.Document, heading: String): String {
        val section = document.select(".Problem-content").firstOrNull { block ->
            block.selectFirst("h6")?.text()?.trim()?.startsWith(heading) == true
        } ?: return ""
        return section.selectFirst(".pre-style, pre, article")?.let(::textOf).orEmpty()
    }

    /** 读取题目来源标题后的第一个 pre。 */
    private fun sourceText(document: org.jsoup.nodes.Document): String? {
        val heading = document.select("h5").firstOrNull { it.text().trim() == "题目来源" } ?: return null
        return heading.nextElementSibling()?.selectFirst("pre")?.let(::textOf)?.takeIf(String::isNotBlank)
    }

    /** 将页面文本规整为稳定的换行文本。 */
    private fun textOf(element: Element): String = element.wholeText()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()

    /** 生成后续人工校验和导入都能读取的 Markdown 题面。 */
    private fun buildMarkdown(
        title: String,
        description: String,
        inputDescription: String,
        outputDescription: String,
        sampleInput: String?,
        sampleOutput: String?,
        sourceDescription: String?,
    ): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("## 题目描述")
        appendLine(description)
        appendLine()
        appendLine("## 输入描述")
        appendLine(inputDescription)
        appendLine()
        appendLine("## 输出描述")
        appendLine(outputDescription)
        if (!sampleInput.isNullOrBlank() || !sampleOutput.isNullOrBlank()) {
            appendLine()
            appendLine("## 输入输出样例")
            if (!sampleInput.isNullOrBlank()) {
                appendLine()
                appendLine("输入：")
                appendLine("```text")
                appendLine(sampleInput)
                appendLine("```")
            }
            if (!sampleOutput.isNullOrBlank()) {
                appendLine()
                appendLine("输出：")
                appendLine("```text")
                appendLine(sampleOutput)
                appendLine("```")
            }
        }
        if (!sourceDescription.isNullOrBlank()) {
            appendLine()
            appendLine("## 题目来源")
            appendLine(sourceDescription)
        }
    }.trim()

    private companion object {
        /** 页面限制文本的兼容匹配。 */
        val LIMIT_REGEX = Regex("Time\\s*Limit\\s*:\\s*(\\d+)\\s*ms", RegexOption.IGNORE_CASE)
        /** 页面内存限制文本的兼容匹配。 */
        val MEMORY_REGEX = Regex("Memory\\s*Limit\\s*:\\s*(\\d+)\\s*mb", RegexOption.IGNORE_CASE)
    }
}

/** 使用登录 Cookie 顺序抓取题目详情。 */
class NoobDreamProblemClient(
    /** 详情请求不自动跟随登录重定向，避免把登录页误当题面。 */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
    /** 题面 DOM 解析器。 */
    private val parser: NoobDreamProblemParser = NoobDreamProblemParser(),
    /** 登录后的 Cookie 请求头。 */
    private val cookieHeader: String,
) {
    /** 请求并解析一条题目详情。 */
    fun fetch(item: NoobDreamListItem): NoobDreamProblem {
        val request = HttpRequest.newBuilder(URI.create(item.detailUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cookie", cookieHeader)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        require(response.statusCode() in 200..299) {
            if (response.statusCode() in 300..399) "题目 ${item.problemId} 被重定向，登录 Cookie 可能已失效" else "题目 ${item.problemId} 请求失败：HTTP ${response.statusCode()}"
        }
        return parser.parse(response.body(), item)
    }

    /** 顺序抓取题目，避免对目标站点造成并发压力。 */
    fun fetchAll(items: List<NoobDreamListItem>): List<NoobDreamProblem> {
        return items.mapIndexed { index, item ->
            fetch(item).also { println("已抓取题目 ${index + 1}/${items.size}：${item.problemId}") }
        }
    }

    private companion object {
        /** 说明访问用途的稳定爬虫 User-Agent。 */
        const val USER_AGENT = "GZU-OJ-Crawler/0.1 (+https://noobdream.com/DreamJudge/Issue/page/0/)"
    }
}

/** 将题目详情写为一个带完整题面的 UTF-8 CSV。 */
class NoobDreamProblemCsvWriter {
    /** 覆盖写入目标文件，并确保父目录存在。 */
    fun write(problems: List<NoobDreamProblem>, target: Path) {
        require(problems.isNotEmpty()) { "没有可写入 CSV 的题目" }
        val normalizedTarget = target.toAbsolutePath().normalize()
        normalizedTarget.parent?.let(Files::createDirectories)
        val output = ByteArrayOutputStream()
        BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
            CSVPrinter(writer, CSVFormat.DEFAULT).use { csv ->
                csv.printRecord(HEADERS)
                problems.forEach { problem ->
                    csv.printRecord(
                        problem.externalKey,
                        problem.problemId,
                        problem.title,
                        problem.school.orEmpty(),
                        problem.year?.toString().orEmpty(),
                        problem.difficulty,
                        problem.problemType,
                        problem.timeLimitMs?.toString().orEmpty(),
                        problem.memoryLimitMiB?.toString().orEmpty(),
                        problem.statementMarkdown,
                        problem.inputDescription,
                        problem.outputDescription,
                        problem.sampleInput.orEmpty(),
                        problem.sampleOutput.orEmpty(),
                        problem.sourceUrl,
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
        /** 题目详情 CSV 的固定表头。 */
        val HEADERS = listOf(
            "externalKey", "problemId", "title", "school", "year", "difficulty", "problemType",
            "timeLimitMs", "memoryLimitMiB", "statementMarkdown", "inputDescription", "outputDescription",
            "sampleInput", "sampleOutput", "sourceUrl",
        )
    }
}
