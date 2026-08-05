package cn.gzuoj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 标准导入 ZIP 的安全边界测试。 */
class ImportArchiveTest {
    /** 拒绝 ZIP Slip 路径。 */
    @Test
    fun rejectsParentTraversal() {
        val bytes = archive(mapOf("../secret.txt" to "secret".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { SafeImportArchive(bytes) }
    }

    /** 拒绝包含非法 UTF-8 的题面。 */
    @Test
    fun rejectsInvalidUtf8() {
        val bytes = archive(mapOf("statement.md" to byteArrayOf(0xC3.toByte(), 0x28)))
        val archive = SafeImportArchive(bytes)
        assertThrows(IllegalArgumentException::class.java) { archive.requiredText("statement.md") }
    }

    /** 接受显式目录项和普通文件。 */
    @Test
    fun acceptsDirectoryEntries() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("statements/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("statements/a.md"))
            zip.write("# A".toByteArray())
            zip.closeEntry()
        }
        assertEquals("# A", SafeImportArchive(output.toByteArray()).requiredText("statements/a.md"))
    }

    /** 重复来源键会让后出现的条目进入无效结果。 */
    @Test
    fun rejectsDuplicateSourceKey() {
        val header = ProblemImportParser.REQUIRED_HEADERS.joinToString(",")
        val row = "same-key,题目,贵州大学,2025,数组,EASY,,1000,256,statements/a.md,"
        val bytes = archive(
            mapOf(
                "problems.csv" to (header + "\n" + row + "\n" + row + "\n").toByteArray(StandardCharsets.UTF_8),
                "statements/a.md" to "# 题目".toByteArray(StandardCharsets.UTF_8),
            ),
        )
        val result = ProblemImportParser().parse(bytes)
        assertEquals(2, result.size)
        assertEquals(true, result.first().isSuccess)
        assertEquals(true, result.last().isFailure)
    }

    /** 构造仅供测试使用的小型 ZIP。 */
    private fun archive(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
