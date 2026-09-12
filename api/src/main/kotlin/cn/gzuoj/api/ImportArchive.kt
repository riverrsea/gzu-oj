package cn.gzuoj.api

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/** 导入包中一行规范题目元数据。 */
data class ImportProblem(
    /** 外部导入题目标识；批量导入必须提供。 */
    val externalKey: String,
    /** 题目标题。 */
    val title: String,
    /** 学校名称。 */
    val school: String,
    /** 真题年份。 */
    val year: Int,
    /** 题目标签。 */
    val tags: List<String>,
    /** 难度。 */
    val difficulty: ProblemDifficulty,
    /** 原始来源地址。 */
    val sourceUrl: String?,
    /** C/C++ 基准时间限制。 */
    val timeLimitMs: Int,
    /** C/C++ 基准内存限制。 */
    val memoryLimitMiB: Int,
    /** Markdown 题面路径。 */
    val statementPath: String,
    /** 可选测试数据目录。 */
    val dataPath: String?,
    /** Markdown 题面。 */
    val statementMarkdown: String,
    /** 已校验测试点。 */
    val testCases: List<CreateTestCaseRequest>,
    /** 规范内容哈希。 */
    val contentSha256: String,
)

/** 安全读取标准导入 ZIP，并阻止路径穿越和压缩炸弹。 */
class SafeImportArchive(
    /** 原始 ZIP 字节。 */
    bytes: ByteArray,
) {
    /** 规范路径到解压内容的映射。 */
    val entries: Map<String, ByteArray> = readEntries(bytes)

    /** 读取必须存在的 UTF-8 文本。 */
    fun requiredText(path: String): String = text(path)
        ?: throw IllegalArgumentException("缺少文件：" + path)

    /** 读取可选 UTF-8 文本并拒绝替换非法字节。 */
    fun text(path: String): String? {
        val content = entries[validatePath(path)] ?: return null
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString()
                .removePrefix("\uFEFF")
        } catch (_: Exception) {
            throw IllegalArgumentException("文件不是有效 UTF-8：" + path)
        }
    }

    /** 逐项解压并执行数量、单项和总量限制。 */
    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size in 1..MAX_ARCHIVE_BYTES) { "导入 ZIP 不能超过 64 MiB" }
        val result = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes), StandardCharsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    validatePath(entry.name.trimEnd('/'))
                    continue
                }
                val path = validatePath(entry.name)
                require(result.size < MAX_ENTRY_COUNT) { "ZIP 文件数量超过上限" }
                require(path !in result) { "ZIP 包含重复路径：" + path }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var itemSize = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    itemSize += count
                    total += count
                    require(itemSize <= MAX_ENTRY_BYTES) { "ZIP 单文件解压后超过 32 MiB：" + path }
                    require(total <= MAX_TOTAL_BYTES) { "ZIP 解压后总量超过 256 MiB" }
                    output.write(buffer, 0, count)
                }
                if (entry.compressedSize > 0 && itemSize / entry.compressedSize.coerceAtLeast(1) > MAX_RATIO) {
                    throw IllegalArgumentException("ZIP 压缩比异常：" + path)
                }
                result[path] = output.toByteArray()
            }
        }
        require(result.isNotEmpty()) { "导入 ZIP 不能为空" }
        return result
    }

    companion object {
        /** ZIP 上传字节上限。 */
        const val MAX_ARCHIVE_BYTES: Int = 64 * 1024 * 1024
        /** ZIP 文件数量上限。 */
        const val MAX_ENTRY_COUNT: Int = 2_000
        /** 单文件解压字节上限。 */
        const val MAX_ENTRY_BYTES: Long = 32L * 1024L * 1024L
        /** 全部文件解压字节上限。 */
        const val MAX_TOTAL_BYTES: Long = 256L * 1024L * 1024L
        /** 允许的最大压缩比。 */
        const val MAX_RATIO: Long = 100

        /** 校验相对 POSIX 路径。 */
        fun validatePath(raw: String): String {
            require(raw.isNotBlank() && !raw.startsWith('/') && !raw.startsWith('\\')) { "ZIP 路径不合法" }
            require('\\' !in raw && '\u0000' !in raw) { "ZIP 路径不合法" }
            val segments = raw.split('/')
            require(segments.none { it.isBlank() || it == "." || it == ".." }) { "ZIP 路径不合法：" + raw }
            return segments.joinToString("/")
        }
    }
}

/** 标准导入包解析器。 */
class ProblemImportParser {
    /** 解析固定表头 problems.csv 和关联题面、测试目录。 */
    fun parse(bytes: ByteArray): List<Result<ImportProblem>> {
        val archive = SafeImportArchive(bytes)
        val csv = archive.requiredText("problems.csv")
        val format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .get()
        val parser = format.parse(csv.reader())
        val externalKeyHeader = when {
            parser.headerNames == REQUIRED_HEADERS -> "externalKey"
            parser.headerNames == LEGACY_HEADERS -> "sourceKey"
            else -> throw IllegalArgumentException("problems.csv 表头必须为：" + REQUIRED_HEADERS.joinToString(","))
        }
        val externalKeys = mutableSetOf<String>()
        return parser.records.map { record ->
            runCatching {
                val parsed = parseRecord(record, archive, externalKeyHeader)
                require(externalKeys.add(parsed.externalKey)) { "批次内 externalKey 重复" }
                parsed
            }
        }
    }

    /** 解析单条元数据并校验所有引用路径。 */
    private fun parseRecord(record: CSVRecord, archive: SafeImportArchive, externalKeyHeader: String): ImportProblem {
        val externalKey = record.required(externalKeyHeader)
        require(externalKey.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))) { "externalKey 格式不正确" }
        val title = record.required("title")
        require(title.length <= 200) { "title 过长" }
        val school = record.required("school")
        require(school.length <= 200) { "school 过长" }
        val year = record.required("year").toInt().also { require(it in 1900..2200) { "year 超出范围" } }
        val tags = record["tags"].split(';').map(String::trim).filter(String::isNotEmpty)
        require(tags.size <= 20 && tags.all { it.length <= 40 }) { "tags 超出限制" }
        require(tags.distinctBy(String::lowercase).size == tags.size) { "tags 不能重复" }
        val difficulty = ProblemDifficulty.valueOf(record.required("difficulty").uppercase())
        val time = record.required("timeLimitMs").toInt().also { require(it in 100..60_000) { "timeLimitMs 超出范围" } }
        val memory = record.required("memoryLimitMiB").toInt().also { require(it in 16..2048) { "memoryLimitMiB 超出范围" } }
        val statementPath = SafeImportArchive.validatePath(record.required("statementPath"))
        require(statementPath.startsWith("statements/")) { "题面必须位于 statements/ 目录" }
        val statement = archive.requiredText(statementPath).also {
            require(it.isNotBlank() && it.length <= 1_000_000) { "题面为空或过大" }
        }
        val dataPath = record["dataPath"].trim().takeIf(String::isNotEmpty)?.let(SafeImportArchive::validatePath)
        val tests = dataPath?.let { parseTests(it, archive) }.orEmpty()
        val canonical = buildString {
            append(externalKey).append('\n')
            listOf("title", "school", "year", "tags", "difficulty", "sourceUrl", "timeLimitMs", "memoryLimitMiB", "statementPath", "dataPath")
                .forEach { append(record[it].trim()).append('\n') }
            append(statement.replace("\r\n", "\n")).append('\n')
            tests.forEach {
                append(SecureValues.sha256(it.input))
                append(SecureValues.sha256(it.output))
                append(it.sample)
            }
        }
        return ImportProblem(
            externalKey = externalKey,
            title = title,
            school = school,
            year = year,
            tags = tags,
            difficulty = difficulty,
            sourceUrl = record["sourceUrl"].trim().takeIf(String::isNotEmpty),
            timeLimitMs = time,
            memoryLimitMiB = memory,
            statementPath = statementPath,
            dataPath = dataPath,
            statementMarkdown = statement,
            testCases = tests,
            contentSha256 = SecureValues.sha256(canonical),
        )
    }

    /** 解析数据目录下固定 cases.csv；测试点不携带分值，得分按通过点数派生。 */
    private fun parseTests(directory: String, archive: SafeImportArchive): List<CreateTestCaseRequest> {
        val prefix = directory.trimEnd('/')
        val cases = CSVFormat.DEFAULT.builder()
            .setHeader("ordinal", "inputPath", "outputPath", "sample")
            .setSkipHeaderRecord(true)
            .get()
            .parse(archive.requiredText(prefix + "/cases.csv").reader())
            .records
            .map { record ->
                val inputPath = childPath(prefix, record.required("inputPath"))
                val outputPath = childPath(prefix, record.required("outputPath"))
                CreateTestCaseRequest(
                    input = archive.requiredText(inputPath),
                    output = archive.requiredText(outputPath),
                    sample = record.required("sample").toBooleanStrict(),
                )
            }
        require(cases.size in 1..200) { "测试点数量必须为 1 到 200" }
        return cases
    }

    /** 限制测试文件只能引用 dataPath 子目录。 */
    private fun childPath(prefix: String, relative: String): String {
        val safeRelative = SafeImportArchive.validatePath(relative)
        val path = SafeImportArchive.validatePath(prefix + "/" + safeRelative)
        require(path.startsWith(prefix + "/")) { "测试文件必须位于 dataPath 目录" }
        return path
    }

    /** 读取并去除字段两端空白。 */
    private fun CSVRecord.required(name: String): String = get(name).trim().also {
        require(it.isNotEmpty()) { name + " 不能为空" }
    }

    companion object {
        /** 批量导入唯一允许的固定列，明确不包含学院字段。 */
        val REQUIRED_HEADERS: List<String> = listOf(
            "externalKey",
            "title",
            "school",
            "year",
            "tags",
            "difficulty",
            "sourceUrl",
            "timeLimitMs",
            "memoryLimitMiB",
            "statementPath",
            "dataPath",
        )
        /** 兼容迁移前生成的 sourceKey 导入包。 */
        val LEGACY_HEADERS: List<String> = REQUIRED_HEADERS.map { if (it == "externalKey") "sourceKey" else it }
    }
}
