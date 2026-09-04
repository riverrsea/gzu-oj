package cn.gzuoj.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/** OI 排名规则测试。 */
class ContestRankingTest {
    /** 同分时应按达到最终总分的用时升序排列。 */
    @Test
    fun `breaks equal scores by time reaching final score`() {
        val ranking = ContestRanking.calculate(
            listOf(
                ContestScoreEvent("slow", "a", 100, 80),
                ContestScoreEvent("fast", "a", 40, 10),
                ContestScoreEvent("fast", "a", 100, 50),
            ),
        )

        assertEquals(listOf("fast", "slow"), ranking.map { it.userId })
    }

    /** 完成用时取所有题目达到最终分数的最晚时刻，而不是题目排列中的最后一道。 */
    @Test
    fun `uses the latest completed problem time`() {
        val ranking = ContestRanking.calculate(
            listOf(
                ContestScoreEvent("user", "a", 100, 80),
                ContestScoreEvent("user", "b", 100, 20),
            ),
        )

        assertEquals(80, ranking.single().reachedFinalScoreAtSeconds)
    }
}
