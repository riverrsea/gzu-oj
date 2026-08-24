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

    /** 空学校转换为明确占位值，仍可通过导入器的非空校验。 */
    @Test
    fun fillsMissingSchool() {
        val input = Files.createTempFile("noobdream-details-empty-school-", ".csv")
        val output = Files.createTempFile("noobdream-import-empty-school-", ".zip")
        try {
            val csv = """externalKey,problemId,title,school,year,difficulty,problemType,timeLimitMs,memoryLimitMiB,statementMarkdown,inputDescription,outputDescription,sampleInput,sampleOutput,sourceUrl
noobdream:1,1,A+B,,2025,EASY,模拟,1000,256,# A+B,,,,,
""".trimIndent()
            Files.writeString(input, csv)
            NoobDreamImportConverter().convert(input, output)
            ZipFile(output.toFile()).use { zip ->
                val problemsCsv = zip.getInputStream(zip.getEntry("problems.csv")).bufferedReader().readText()
                assertTrue(problemsCsv.contains("noobdream:1,A+B,${NoobDreamImportConverter.DEFAULT_SCHOOL},2025"))
            }
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }

    /** 空年份默认写为 1900，已有年份和显式默认年份不受影响。 */
    @Test
    fun fillsMissingYearWith1900() {
        val input = Files.createTempFile("noobdream-details-empty-year-", ".csv")
        val output = Files.createTempFile("noobdream-import-empty-year-", ".zip")
        try {
            val csv = """externalKey,problemId,title,school,year,difficulty,problemType,timeLimitMs,memoryLimitMiB,statementMarkdown,inputDescription,outputDescription,sampleInput,sampleOutput,sourceUrl
noobdream:1,1,A+B,贵州大学,,EASY,模拟,1000,256,# A+B,,,,,
""".trimIndent()
            Files.writeString(input, csv)
            NoobDreamImportConverter().convert(input, output)
            ZipFile(output.toFile()).use { zip ->
                val problemsCsv = zip.getInputStream(zip.getEntry("problems.csv")).bufferedReader().readText()
                assertTrue(problemsCsv.contains("noobdream:1,A+B,贵州大学,${NoobDreamImportConverter.DEFAULT_YEAR}"))
            }
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }

    /** 源站难度后缀、旧 KiB、拼接值和低于系统下限的内存值转换为项目标准值。 */
    @Test
    fun normalizesSourceMetadata() {
        val input = Files.createTempFile("noobdream-details-source-metadata-", ".csv")
        val output = Files.createTempFile("noobdream-import-source-metadata-", ".zip")
        try {
            val csv = """externalKey,problemId,title,school,year,difficulty,problemType,timeLimitMs,memoryLimitMiB,statementMarkdown,inputDescription,outputDescription,sampleInput,sampleOutput,sourceUrl
noobdream:1,1,旧题一,清华大学,,中等-,模拟,1000,32678,# 旧题一,,,,,
noobdream:2,2,旧题二,北京大学,,中等+,数学,1000,25632768,# 旧题二,,,,,
noobdream:3,3,旧题三,,,简单,数学,1000,10,# 旧题三,,,,,
""".trimIndent()
            Files.writeString(input, csv)
            NoobDreamImportConverter().convert(input, output)
            ZipFile(output.toFile()).use { zip ->
                val problemsCsv = zip.getInputStream(zip.getEntry("problems.csv")).bufferedReader().readText()
                assertTrue(problemsCsv.contains("noobdream:1,旧题一,清华大学,1900,模拟,MEDIUM,,1000,32"))
                assertTrue(problemsCsv.contains("noobdream:2,旧题二,北京大学,1900,数学,MEDIUM,,1000,256"))
                assertTrue(problemsCsv.contains("noobdream:3,旧题三,未注明,1900,数学,EASY,,1000,16"))
            }
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }
}
