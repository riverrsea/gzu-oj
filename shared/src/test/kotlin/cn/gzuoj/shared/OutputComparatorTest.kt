package cn.gzuoj.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 默认文本比较器测试。 */
class OutputComparatorTest {
    /** 换行、行尾空白和末尾空行应被忽略。 */
    @Test
    fun `normalizes permitted whitespace differences`() {
        assertTrue(OutputComparator.matches("a  b\r\n42\n", "a  b   \n42\n\n"))
    }

    /** 行内空格和大小写必须保持敏感。 */
    @Test
    fun `preserves internal spaces and case`() {
        assertFalse(OutputComparator.matches("A B", "a B"))
        assertFalse(OutputComparator.matches("a  b", "a b"))
    }
}
