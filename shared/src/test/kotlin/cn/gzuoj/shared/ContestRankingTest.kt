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
}
