package cn.gzuoj.crawler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.ZipFile

/** 标准导入包写入器测试。 */
class ImportPackageWriterTest {
    /** 写入题面、固定表头和测试点文件。 */
    @Test
    fun writesCanonicalArchive() {
        val target = Files.createTempFile("gzu-oj-crawler-", ".zip")
        try {
            ImportPackageWriter().write(
                listOf(
                    CanonicalProblem(
                        sourceKey = "local-two-sum",
                        title = "两数之和",
                        school = "贵州大学",
                        year = 2025,
                        tags = listOf("数组"),
                        difficulty = "EASY",
                        timeLimitMs = 1_000,
                        memoryLimitMiB = 256,
                        statementMarkdown = "# 两数之和",
                        testCases = listOf(CanonicalTestCase("1 2\n", "3\n", 100, true)),
                    ),
                ),
                target,
            )
            ZipFile(target.toFile()).use { zip ->
                assertTrue(zip.getEntry("problems.csv") != null)
                assertTrue(zip.getEntry("statements/local-two-sum.md") != null)
                assertTrue(zip.getEntry("tests/local-two-sum/cases.csv") != null)
                val csv = zip.getInputStream(zip.getEntry("problems.csv")).bufferedReader().readText()
                assertTrue(csv.startsWith("sourceKey,title,school,year,tags,difficulty,sourceUrl,timeLimitMs,memoryLimitMiB,statementPath,dataPath"))
                assertEquals("# 两数之和", zip.getInputStream(zip.getEntry("statements/local-two-sum.md")).bufferedReader().readText())
            }
        } finally {
            Files.deleteIfExists(target)
        }
    }
}
