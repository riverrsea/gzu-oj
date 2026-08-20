package cn.gzuoj.crawler

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 爬虫统一输出的规范测试点。 */
data class CanonicalTestCase(
    /** 测试输入。 */
    val input: String,
    /** 由可信标程生成的标准输出。 */
    val output: String,
    /** 测试点分值。 */
    val score: Int,
    /** 是否公开为样例。 */
    val sample: Boolean = false,
)

/** 与具体来源网站无关的规范题目。 */
data class CanonicalProblem(
    /** 外部题目标识；爬虫导入必须提供。 */
    val externalKey: String,
    /** 题目标题。 */
    val title: String,
    /** 学校名称。 */
    val school: String,
    /** 真题年份。 */
    val year: Int,
    /** 题目标签。 */
    val tags: List<String> = emptyList(),
    /** EASY、MEDIUM 或 HARD。 */
    val difficulty: String,
    /** 原始来源链接。 */
    val sourceUrl: String? = null,
    /** C/C++ 基准时间限制。 */
    val timeLimitMs: Int,
    /** C/C++ 基准内存限制。 */
    val memoryLimitMiB: Int,
    /** Markdown 题面。 */
    val statementMarkdown: String,
    /** 可选测试点；缺省时只生成题面导入包。 */
    val testCases: List<CanonicalTestCase> = emptyList(),
)

/** 数据来源适配器边界。 */
interface SourceAdapter {
    /** 适配器稳定名称。 */
    val name: String

    /** 从给定位置读取并规范化题目。 */
    fun fetch(source: URI): List<CanonicalProblem>
}

/** 读取本地 JSON 样例的首版适配器，不执行网络抓取。 */
class LocalSampleAdapter(
    /** JSON 编解码器。 */
    private val mapper: ObjectMapper,
) : SourceAdapter {
    /** 本地适配器名称。 */
    override val name: String = "local-sample"

    /** 从 file URI 读取 CanonicalProblem 数组。 */
    override fun fetch(source: URI): List<CanonicalProblem> {
        require(source.scheme == "file") { "本地样例适配器只接受 file URI" }
        val path = Path.of(source).toAbsolutePath().normalize()
        require(Files.isRegularFile(path)) { "本地样例文件不存在：" + path }
        return mapper.readerForListOf(CanonicalProblem::class.java)
            .readValue<List<CanonicalProblem>>(Files.readAllBytes(path))
            .also(::validate)
    }

    /** 在写包前校验与 API 导入契约一致的核心字段。 */
    private fun validate(problems: List<CanonicalProblem>) {
        require(problems.isNotEmpty()) { "题目列表不能为空" }
        require(problems.map { it.externalKey }.distinct().size == problems.size) { "externalKey 不能重复" }
        problems.forEach { problem ->
            require(problem.externalKey.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))) { "externalKey 格式不正确" }
            require(problem.title.isNotBlank() && problem.school.isNotBlank()) { "标题和学校不能为空" }
            require(problem.year in 1900..2200) { "年份超出范围" }
            require(problem.difficulty in setOf("EASY", "MEDIUM", "HARD")) { "难度不合法" }
            require(problem.timeLimitMs in 100..60_000) { "时间限制超出范围" }
            require(problem.memoryLimitMiB in 16..2048) { "内存限制超出范围" }
            require(problem.statementMarkdown.isNotBlank()) { "题面不能为空" }
            if (problem.testCases.isNotEmpty()) {
                require(problem.testCases.sumOf { it.score } == 100) { "测试点分值之和必须为 100" }
            }
        }
    }
}

/** 将规范题目写为 API 可暂存导入的标准 ZIP。 */
class ImportPackageWriter {
    /** 写入 problems.csv、statements 和可选 tests 目录。 */
    fun write(problems: List<CanonicalProblem>, target: Path) {
        val normalizedTarget = target.toAbsolutePath().normalize()
        normalizedTarget.parent?.let(Files::createDirectories)
        Files.newOutputStream(
            normalizedTarget,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { file ->
            ZipOutputStream(file, StandardCharsets.UTF_8).use { zip ->
                add(zip, "problems.csv", problemsCsv(problems))
                problems.forEachIndexed { index, problem ->
                    val stem = artifactStem(index)
                    add(zip, "statements/$stem.md", problem.statementMarkdown.toByteArray(StandardCharsets.UTF_8))
                    if (problem.testCases.isNotEmpty()) writeCases(zip, problem, stem)
                }
            }
        }
    }

    /** 构造固定表头的 UTF-8 CSV。 */
    private fun problemsCsv(problems: List<CanonicalProblem>): ByteArray {
        val output = ByteArrayOutputStream()
        BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
            CSVPrinter(writer, CSVFormat.DEFAULT).use { csv ->
                csv.printRecord(HEADERS)
                problems.forEachIndexed { index, problem ->
                    val stem = artifactStem(index)
                    val dataPath = if (problem.testCases.isEmpty()) "" else "tests/$stem"
                    csv.printRecord(
                        problem.externalKey,
                        problem.title,
                        problem.school,
                        problem.year,
                        problem.tags.joinToString(";"),
                        problem.difficulty,
                        problem.sourceUrl.orEmpty(),
                        problem.timeLimitMs,
                        problem.memoryLimitMiB,
                        "statements/$stem.md",
                        dataPath,
                    )
                }
            }
        }
        return output.toByteArray()
    }

    /** 写入固定 cases.csv 及对应输入输出。 */
    private fun writeCases(zip: ZipOutputStream, problem: CanonicalProblem, stem: String) {
        val prefix = "tests/$stem"
        val output = ByteArrayOutputStream()
        BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
            CSVPrinter(writer, CSVFormat.DEFAULT).use { csv ->
                csv.printRecord("ordinal", "inputPath", "outputPath", "score", "sample")
                problem.testCases.forEachIndexed { index, test ->
                    val ordinal = index + 1
                    val inputName = "$ordinal.in"
                    val outputName = "$ordinal.out"
                    csv.printRecord(ordinal, inputName, outputName, test.score, test.sample)
                    add(zip, "$prefix/$inputName", test.input.toByteArray(StandardCharsets.UTF_8))
                    add(zip, "$prefix/$outputName", test.output.toByteArray(StandardCharsets.UTF_8))
                }
            }
        }
        add(zip, "$prefix/cases.csv", output.toByteArray())
    }

    /** 生成与外部键无关的安全文件名，兼容 Windows 解压和跨平台导入。 */
    private fun artifactStem(index: Int): String = "problem-" + (index + 1)

    /** 向 ZIP 写入单个规范相对路径。 */
    private fun add(zip: ZipOutputStream, path: String, content: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content)
        zip.closeEntry()
    }

    private companion object {
        /** 与 API 导入解析器严格一致的表头。 */
        val HEADERS: List<String> = listOf(
            "externalKey", "title", "school", "year", "tags", "difficulty", "sourceUrl",
            "timeLimitMs", "memoryLimitMiB", "statementPath", "dataPath",
        )
    }
}

/** 创建注册了 Kotlin 模块的 Jackson 3 映射器。 */
private fun jsonMapper(): ObjectMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .build()

/** 爬虫 CLI 入口；支持本地规范 JSON 和 N 诺公开题库列表 CSV。 */
fun main(args: Array<String>) {
    when {
        args.size == 3 && args[0] == "local" -> {
            val adapter = LocalSampleAdapter(jsonMapper())
            val source = Path.of(args[1]).toAbsolutePath().normalize().toUri()
            val target = Path.of(args[2]).toAbsolutePath().normalize()
            ImportPackageWriter().write(adapter.fetch(source), target)
            println("已生成标准导入包：" + target)
        }
        args.size in 3..4 && args[0] == "noobdream-list" -> {
            val source = URI.create(args[1])
            val target = Path.of(args[2]).toAbsolutePath().normalize()
            val singlePage = args.getOrNull(3) == "--single-page"
            require(args.size == 3 || singlePage) { "第四个参数只能是 --single-page" }
            val items = if (singlePage) NoobDreamListClient().fetch(source) else NoobDreamListClient().fetchAll(source)
            NoobDreamListCsvWriter().write(items, target)
            println("已采集 ${items.size} 道题目列表${if (singlePage) "（单页）" else "（全部分页）"}并写入 CSV：$target")
        }
        else -> error(
            "用法：crawler-cli local <canonical-problems.json> <output.zip>\n" +
                "或：crawler-cli noobdream-list <list-url> <output.csv> [--single-page]",
        )
    }
}
