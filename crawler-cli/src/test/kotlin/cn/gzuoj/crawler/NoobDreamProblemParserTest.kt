package cn.gzuoj.crawler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** N 诺题目详情页解析测试。 */
class NoobDreamProblemParserTest {
    /** 解析限制、题面、输入输出描述、样例和来源。 */
    @Test
    fun parsesProblemDetail() {
        val item = NoobDreamListItem(
            externalKey = "noobdream:1006",
            problemId = "1006",
            title = "字符串翻转",
            difficulty = "简单",
            problemType = "简单模拟",
            school = "贵州大学",
            sourceDescription = "贵州大学机试题",
            detailUrl = "https://noobdream.com/DreamJudge/Issue/page/1006/",
        )
        val problem = NoobDreamProblemParser().parse(
            """
            <div class="title-container"><h1>字符串翻转</h1></div>
            <div class="bg-light"><span>Time Limit: 1000 ms</span><br><span>Memory Limit: 256 mb</span></div>
            <div class="OjInfo"><p>给定一个字符串，反序输出。</p></div>
            <div class="Problem-content"><h6>输入描述:</h6><article><div class="pre-style">输入一个字符串。</div></article></div>
            <div class="Problem-content"><h6>输出描述:</h6><article><div class="pre-style">输出反序字符串。</div></article></div>
            <pre id="input">Guiyang</pre><pre id="output">gnayiuG</pre>
            <h5>题目来源</h5><div><pre>贵州大学机试题</pre></div>
            """.trimIndent(),
            item,
        )
        assertEquals(1000, problem.timeLimitMs)
        assertEquals(256, problem.memoryLimitMiB)
        assertEquals("Guiyang", problem.sampleInput)
        assertEquals("gnayiuG", problem.sampleOutput)
        assertEquals("noobdream:1006", problem.externalKey)
        assertEquals("贵州大学", problem.school)
        assertEquals("https://noobdream.com/DreamJudge/Issue/page/1006/", problem.sourceUrl)
        assertTrue(problem.statementMarkdown.contains("## 输入描述"))
        assertTrue(problem.statementMarkdown.contains("```text"))
    }

    /** 输入或输出描述缺失时仍保留题目记录，缺失字段写为空。 */
    @Test
    fun allowsMissingFormatSection() {
        val item = NoobDreamListItem(
            externalKey = "noobdream:1001",
            problemId = "1001",
            title = "旧题",
            difficulty = "简单",
            problemType = "简单模拟",
            school = null,
            sourceDescription = null,
            detailUrl = "https://noobdream.com/DreamJudge/Issue/page/1001/",
        )
        val problem = NoobDreamProblemParser().parse(
            "<h1>旧题</h1>",
            item,
        )
        assertEquals("", problem.statementMarkdown.substringAfter("## 题目描述\n").substringBefore("\n\n## 输入描述"))
        assertEquals("", problem.inputDescription)
        assertEquals("", problem.outputDescription)
    }
}
