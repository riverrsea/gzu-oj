package cn.gzuoj.crawler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipFile

/** noobdream 详情 CSV 转标准无测试点导入包测试。 */
class NoobDreamImportConverterTest {
    /** 转换多行 Markdown，并把默认年份填入空年份。 */
    @Test
    fun convertsDetailsCsvWithoutTests() {
        val input = Files.createTempFile("noobdream-details-", ".csv")
        val output = Files.createTempFile("noobdream-import-", ".zip")
        try {
            Files.writeString(
                input,
                """externalKey,problemId,title,school,year,difficulty,problemType,timeLimitMs,memoryLimitMiB,statementMarkdown,inputDescription,outputDescription,sampleInput,sampleOutput,sourceUrl
noobdream:1006,1006,字符串翻转,贵州大学,,简单,简单模拟,1000,256,"# 字符串翻转

## 输入描述
输入一个字符串。",,,,,https://noobdream.com/DreamJudge/Issue/page/1006/
""".trimIndent(),
            )
            NoobDreamImportConverter().convert(input, output, defaultYear = 2025)
            ZipFile(output.toFile()).use { zip ->
                val csv = zip.getInputStream(zip.getEntry("problems.csv")).bufferedReader().readText()
                assertTrue(csv.startsWith("externalKey,title,school,year,tags,difficulty,sourceUrl,timeLimitMs,memoryLimitMiB,statementPath,dataPath"))
                assertTrue(csv.contains("noobdream:1006,字符串翻转,贵州大学,2025,简单模拟,EASY"))
                assertEquals("# 字符串翻转\n\n## 输入描述\n输入一个字符串。", zip.getInputStream(zip.getEntry("statements/problem-1.md")).bufferedReader().readText())
                assertTrue(zip.getEntry("tests/problem-1/cases.csv") == null)
            }
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }

    /** 兼容 Windows 常见 GB18030 编码的详情 CSV。 */
    @Test
    fun readsGb18030Csv() {
        val input = Files.createTempFile("noobdream-details-gb-", ".csv")
        val output = Files.createTempFile("noobdream-import-gb-", ".zip")
        try {
            val csv = """externalKey,problemId,title,school,year,difficulty,problemType,timeLimitMs,memoryLimitMiB,statementMarkdown,inputDescription,outputDescription,sampleInput,sampleOutput,sourceUrl
noobdream:1,1,A+B,贵州大学,2025,EASY,模拟,1000,256,# A+B,,,,,
""".trimIndent()
            Files.write(input, csv.toByteArray(Charset.forName("GB18030")))
            NoobDreamImportConverter().convert(input, output)
            ZipFile(output.toFile()).use { zip -> assertTrue(zip.getEntry("statements/problem-1.md") != null) }
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }
}
