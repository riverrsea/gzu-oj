package cn.gzuoj.shared

/**
 * 提交得分的派生规则。
 *
 * 测试点不再携带分值，得分统一由"通过点数 / 总点数"折算成 0..100，
 * 且与"通过了哪几个测试点"无关。Worker 只回报每个测试点的状态，
 * 得分由服务端在这一处计算，避免多端各算一套。
 */
object SubmissionScoring {
    /** 满分。 */
    const val FULL_SCORE: Int = 100

    /**
     * 按通过率折算提交得分，四舍五入到整数。
     *
     * @param passedCases 通过的测试点数量。
     * @param totalCases 测试点总数；为 0（例如编译失败、无测试点）时返回 0 分。
     */
    fun scoreOf(passedCases: Int, totalCases: Int): Int {
        if (totalCases <= 0 || passedCases <= 0) return 0
        val bounded = passedCases.coerceAtMost(totalCases)
        // 加半个分母实现四舍五入，避免引入浮点误差。
        return (bounded * FULL_SCORE + totalCases / 2) / totalCases
    }

    /** 统计结算结果中状态为 AC 的测试点数量。 */
    fun passedCount(results: List<JudgeCaseResult>): Int = results.count { it.status == JudgeStatus.AC }
}
