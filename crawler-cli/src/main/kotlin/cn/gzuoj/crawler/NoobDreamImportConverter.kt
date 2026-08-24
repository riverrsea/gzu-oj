package cn.gzuoj.crawler

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 将已登录采集的题目详情 CSV 转换为标准导入包。当前阶段不写入测试点目录。 */
class NoobDreamImportConverter {
    /**
     * 转换详情 CSV；defaultYear 只填充 CSV 中为空的年份，不覆盖已有年份。
     * 转换失败时汇总全部行错误，避免只修正第一道题后重复试错。
     */
    fun convert(input: Path, target: Path, defaultYear: Int? = null) {
        require(Files.isRegularFile(input)) { "详情 CSV 不存在：$input" }
        defaultYear?.let { require(it in 1900..2200) { "默认年份必须在 1900..2200 之间" } }
        val records = parseRecords(input)
        require(records.isNotEmpty()) { "详情 CSV 没有题目记录" }
        val errors = mutableListOf<String>()
        val problems = records.mapIndexedNotNull { index, record ->
            runCatching { record.toCanonicalProblem(defaultYear) }
                .onFailure {
                    val key = record.get("externalKey").trim().ifEmpty { "?" }
                    val title = record.get("title").trim().ifEmpty { "未命名题目" }
                    errors += "第 ${index + 2} 行（$key，$title）：${it.message ?: "字段不合法"}"
                }
                .getOrNull()
        }
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(
                "详情 CSV 无法转换为标准导入包，共 ${errors.size} 行不完整：\n" + errors.joinToString("\n"),
            )
        }
        ImportPackageWriter().write(problems, target)
    }

    /** 使用严格 UTF-8，兼容 Windows 工具导出的 GB18030 CSV。 */
    private fun parseRecords(input: Path): List<CSVRecord> {
        val bytes = Files.readAllBytes(input)
        val text = decode(bytes, StandardCharsets.UTF_8)
            ?: decode(bytes, Charset.forName("GB18030"))
            ?: throw IllegalArgumentException("详情 CSV 不是有效的 UTF-8 或 GB18030 文本")
        val parser = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .get()
            .parse(StringReader(text))
        parser.use {
            require(it.headerNames == NoobDreamProblemCsvWriter.HEADERS) {
                "详情 CSV 表头必须为：${NoobDreamProblemCsvWriter.HEADERS.joinToString(",")}"
            }
            return it.records
        }
    }

    /** 严格解码，遇到非法字节时返回空。 */
    private fun decode(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    }.getOrNull()

    /** 将一行详情记录映射到标准导入模型；测试点暂时为空。 */
    private fun CSVRecord.toCanonicalProblem(defaultYear: Int?): CanonicalProblem {
        val externalKey = required("externalKey")
        require(externalKey.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))) { "externalKey 格式不正确" }
        val title = required("title").also { require(it.length <= 200) { "title 过长" } }
        val school = get("school").trim().ifEmpty { DEFAULT_SCHOOL }
            .also { require(it.length <= 200) { "school 过长" } }
        val year = get("year").trim().takeIf(String::isNotEmpty)?.toIntOrNull() ?: defaultYear
        require(year != null && year in 1900..2200) { "year 为空或超出 1900..2200 范围" }
        val difficulty = mapDifficulty(required("difficulty"))
        val timeLimitMs = required("timeLimitMs").toInt().also { require(it in 100..60_000) { "timeLimitMs 超出范围" } }
        val memoryLimitMiB = required("memoryLimitMiB").toInt().also { require(it in 16..2048) { "memoryLimitMiB 超出范围" } }
        val statement = required("statementMarkdown").also { require(it.length <= 1_000_000) { "statementMarkdown 过长" } }
        val problemType = get("problemType").trim()
        return CanonicalProblem(
            externalKey = externalKey,
            title = title,
            school = school,
            year = year,
            tags = problemType.takeIf(String::isNotEmpty)?.let(::listOf).orEmpty(),
            difficulty = difficulty,
            sourceUrl = get("sourceUrl").trim().takeIf(String::isNotEmpty),
            timeLimitMs = timeLimitMs,
            memoryLimitMiB = memoryLimitMiB,
            statementMarkdown = statement,
            testCases = emptyList(),
        )
    }

    /** 将站点中文难度映射到标准导入枚举。 */
    private fun mapDifficulty(value: String): String = when (value.trim().uppercase()) {
        "EASY", "简单", "容易" -> "EASY"
        "MEDIUM", "中等", "一般" -> "MEDIUM"
        "HARD", "困难" -> "HARD"
        else -> throw IllegalArgumentException("difficulty 不支持：$value")
    }

    /** 读取必填字段并去除两端空白。 */
    private fun CSVRecord.required(name: String): String = get(name).trim().also {
        require(it.isNotEmpty()) { "$name 不能为空" }
    }

    companion object {
        /** 外部来源未提供学校时的明确占位值，避免与真实学校混淆。 */
        const val DEFAULT_SCHOOL = "未注明"
    }
}
