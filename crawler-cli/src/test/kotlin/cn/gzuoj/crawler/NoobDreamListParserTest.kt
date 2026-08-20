package cn.gzuoj.crawler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipFile

/** N 诺题库列表解析和 CSV 写入测试。 */
class NoobDreamListParserTest {
    /** 解析列表行中的题号、标题、标签和绝对详情地址。 */
    @Test
    fun parsesListRows() {
        val page = URI.create("https://noobdream.com/DreamJudge/Issue/page/0/")
        val items = parseNoobDreamListHtml(
            """
            <html><body><table><tbody>
              <tr>
                <td><i aria-label="未解决"></i></td><td>1006</td>
                <td><a href="/DreamJudge/Issue/page/1006/" target="_blank">字符串翻转
                  <span class="tag tag-school">真题</span>
                  <span class="tag tag-source">贵州大学机试题</span>
                </a></td>
                <td><span class="level-tag">简单</span></td><td>简单模拟</td>
              </tr>
              <tr>
                <td></td><td>1000</td>
                <td><a href="/DreamJudge/Issue/page/1000/">A+B问题<span class="tag tag-source">计算机考研机试入门题</span></a></td>
                <td><span class="level-tag">简单</span></td><td>简单模拟</td>
              </tr>
            </tbody></table><ul class="pagination">
              <li class="page-num"><a class="page-link">1</a></li>
              <li class="page-num" style="display:none"><a class="page-link">82</a></li>
            </ul></body></html>
            """.trimIndent(),
            page,
        )
        assertEquals(2, items.size)
        assertEquals("1006", items[0].problemId)
        assertEquals("字符串翻转", items[0].title)
        assertEquals("真题", items[0].schoolTag)
        assertEquals("贵州大学机试题", items[0].sourceTag)
        assertEquals("https://noobdream.com/DreamJudge/Issue/page/1006/", items[0].detailUrl)
        assertEquals("计算机考研机试入门题", items[1].sourceTag)
        assertEquals(null, items[1].schoolTag)
        assertEquals(82, NoobDreamListParser().parsePage(
            """
            <table><tbody><tr><td></td><td>1000</td><td><a href="/DreamJudge/Issue/page/1000/">A+B问题</a></td><td><span class="level-tag">简单</span></td><td>简单模拟</td></tr></tbody></table>
            <ul class="pagination"><li class="page-num"><a class="page-link">1</a></li><li class="page-num"><a class="page-link">82</a></li></ul>
            """.trimIndent(),
            page,
        ).totalPages)
    }

    /** 输出固定表头和带引号转义的单个 CSV 文件。 */
    @Test
    fun writesCsv() {
        val target = Files.createTempFile("noobdream-list-", ".csv")
        try {
            NoobDreamListCsvWriter().write(
                listOf(
                    NoobDreamListItem(
                        problemId = "1006",
                        title = "字符串翻转",
                        difficulty = "简单",
                        problemType = "简单模拟",
                        schoolTag = "真题",
                        sourceTag = "贵州大学机试题",
                        detailUrl = "https://noobdream.com/DreamJudge/Issue/page/1006/",
                        sourcePageUrl = "https://noobdream.com/DreamJudge/Issue/page/0/",
                    ),
                ),
                target,
            )
            val csv = Files.readString(target)
            assertTrue(csv.startsWith("problemId,title,difficulty,problemType,schoolTag,sourceTag,detailUrl,sourcePageUrl"))
            assertTrue(csv.contains("1006"))
            assertTrue(csv.contains("贵州大学机试题"))
        } finally {
            Files.deleteIfExists(target)
        }
    }
}
