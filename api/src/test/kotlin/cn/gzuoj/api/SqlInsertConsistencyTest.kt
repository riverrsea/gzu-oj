package cn.gzuoj.api

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 静态校验源码里 INSERT 的列数与 VALUES 项数一致。
 *
 * 这类错误 Kotlin 编译器发现不了：SQL 只是字符串，列数和占位符对不上要等运行时才由 JDBC 抛错。
 * 删列或加列时非常容易只改一处，这里用测试兜住——实际就踩过一次：
 * 去掉 problem_test_case.score 时列名删了，`VALUES` 里的 `?` 没删，导入直接报
 * "INSERT has more expressions than target columns"。
 */
class SqlInsertConsistencyTest {
    /** 遍历主源码，报告列数与 VALUES 项数不一致的 INSERT。 */
    @Test
    fun allInsertsMatchColumnCount() {
        val sourceRoot = listOf(File("src/main/kotlin"), File("api/src/main/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("找不到 api 主源码目录")
        val mismatches = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { mismatches(it) }
            .toList()
        assertTrue(mismatches.isEmpty()) {
            "以下 INSERT 的列数与 VALUES 项数不一致：\n" + mismatches.joinToString("\n")
        }
    }

    /** 返回单个文件里全部不一致的 INSERT 描述。 */
    private fun mismatches(file: File): List<String> {
        val text = file.readText()
        return INSERT_PATTERN.findAll(text).mapNotNull { match ->
            val columnsOpen = match.range.last
            val columnsClose = matchingParen(text, columnsOpen) ?: return@mapNotNull null
            val columns = splitTopLevel(text.substring(columnsOpen + 1, columnsClose))
            val valuesKeyword = text.indexOf("VALUES", columnsClose)
            if (valuesKeyword < 0) return@mapNotNull null
            val valuesOpen = text.indexOf('(', valuesKeyword)
            val valuesClose = matchingParen(text, valuesOpen) ?: return@mapNotNull null
            val values = splitTopLevel(text.substring(valuesOpen + 1, valuesClose))
            if (columns.size == values.size) return@mapNotNull null
            val line = text.take(match.range.first).count { it == '\n' } + 1
            "${file.name}:$line 表=${match.groupValues[1]} 列=${columns.size} VALUES项=${values.size}"
        }.toList()
    }

    /** 返回与 open 位置括号配对的右括号下标；用于跳过 `now()` 这类函数调用里的括号。 */
    private fun matchingParen(text: String, open: Int): Int? {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    /** 按顶层逗号切分，忽略括号内部的逗号。 */
    private fun splitTopLevel(content: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        content.forEach { char ->
            when (char) {
                '(' -> {
                    depth++
                    current.append(char)
                }
                ')' -> {
                    depth--
                    current.append(char)
                }
                ',' -> if (depth == 0) {
                    parts += current.toString()
                    current.clear()
                } else {
                    current.append(char)
                }
                else -> current.append(char)
            }
        }
        parts += current.toString()
        return parts.map(String::trim).filter(String::isNotEmpty)
    }

    private companion object {
        /** 只匹配到左括号，列清单和 VALUES 由括号配对解析。 */
        val INSERT_PATTERN = Regex("""INSERT INTO\s+(\w+)\s*\(""")
    }
}
