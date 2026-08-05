package cn.gzuoj.shared

/** 一次比赛提交对排名的必要投影。 */
data class ContestScoreEvent(
    /** 用户标识。 */
    val userId: String,
    /** 题目标识。 */
    val problemId: String,
    /** 本次提交得分。 */
    val score: Int,
    /** 从比赛开始到提交的秒数。 */
    val elapsedSeconds: Long,
)

/** 公开训练赛的一行 OI 排名。 */
data class ContestRankRow(
    /** 用户标识。 */
    val userId: String,
    /** 每题比赛期间最高分。 */
    val problemScores: Map<String, Int>,
    /** 所有题最高分之和。 */
    val totalScore: Int,
    /** 达到最终总分时的比赛用时。 */
    val reachedFinalScoreAtSeconds: Long,
)

/** OI 计分和同分排序计算器。 */
object ContestRanking {
    /** 根据比赛期间提交事件计算稳定排名。 */
    fun calculate(events: List<ContestScoreEvent>): List<ContestRankRow> =
        events
            .groupBy { it.userId }
            .map { (userId, userEvents) -> toRow(userId, userEvents) }
            .sortedWith(
                compareByDescending<ContestRankRow> { it.totalScore }
                    .thenBy { it.reachedFinalScoreAtSeconds }
                    .thenBy { it.userId },
            )

    /** 汇总单个用户的最终分数和首次达到该分数的时刻。 */
    private fun toRow(userId: String, events: List<ContestScoreEvent>): ContestRankRow {
        val finalScores = events
            .groupBy { it.problemId }
            .mapValues { (_, problemEvents) -> problemEvents.maxOf { it.score } }
        val total = finalScores.values.sum()
        val runningBest = mutableMapOf<String, Int>()
        var reachedAt = 0L

        for (event in events.sortedBy { it.elapsedSeconds }) {
            runningBest[event.problemId] = maxOf(runningBest[event.problemId] ?: 0, event.score)
            if (runningBest.values.sum() == total) {
                reachedAt = event.elapsedSeconds
                break
            }
        }

        return ContestRankRow(
            userId = userId,
            problemScores = finalScores,
            totalScore = total,
            reachedFinalScoreAtSeconds = reachedAt,
        )
    }
}
