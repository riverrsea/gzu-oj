package cn.gzuoj.api

import cn.gzuoj.shared.AiGeneratedCaseResult
import kotlin.test.Test
import kotlin.test.assertEquals

/** 标准答案候选选择顺序测试。 */
class ReferenceSolutionSelectorTest {
    /** CPU 更低的候选优先于内存。 */
    @Test
    fun `selects candidate with lower worst cpu time`() {
        assertEquals("B", ReferenceSolutionSelector.select("a", "b", listOf(case(20, 10, 100, 200))).candidate)
    }

    /** CPU 和内存相同时稳定选择 A。 */
    @Test
    fun `selects A as final tie breaker`() {
        assertEquals("A", ReferenceSolutionSelector.select("a", "b", listOf(case(10, 10, 100, 100))).candidate)
    }

    /** 构造只关注资源字段的完整 Worker 证据。 */
    private fun case(aTime: Long, bTime: Long, aMemory: Long, bMemory: Long) = AiGeneratedCaseResult(
        ordinal = 1,
        seed = 1,
        input = "1",
        expectedOutput = "1",
        inputSha256 = "0".repeat(64),
        outputSha256 = "0".repeat(64),
        solutionAOutputSha256 = "0".repeat(64),
        solutionBOutputSha256 = "0".repeat(64),
        bruteForceOutputSha256 = "0".repeat(64),
        solutionATimeMs = aTime,
        solutionAMemoryKiB = aMemory,
        solutionBTimeMs = bTime,
        solutionBMemoryKiB = bMemory,
    )
}
