package cn.gzuoj.api

import cn.gzuoj.shared.AiGeneratedCaseResult

/** 通过沙箱验证后可保存的标准答案候选。 */
data class SelectedReferenceSolution(
    /** 候选标识。 */
    val candidate: String,
    /** 候选源码。 */
    val sourceCode: String,
)

/** 按可复现资源证据确定标准答案，资源相同时稳定选择 A。 */
object ReferenceSolutionSelector {
    /** 比较两份标程的最坏 CPU 和内存占用。 */
    fun select(
        solutionA: String,
        solutionB: String,
        cases: List<AiGeneratedCaseResult>,
    ): SelectedReferenceSolution {
        require(cases.isNotEmpty()) { "标准答案候选必须至少通过一个测试点" }
        val a = cases.maxOf(AiGeneratedCaseResult::solutionATimeMs) to
            cases.maxOf(AiGeneratedCaseResult::solutionAMemoryKiB)
        val b = cases.maxOf(AiGeneratedCaseResult::solutionBTimeMs) to
            cases.maxOf(AiGeneratedCaseResult::solutionBMemoryKiB)
        return if (a.first < b.first || (a.first == b.first && a.second <= b.second)) {
            SelectedReferenceSolution("A", solutionA)
        } else {
            SelectedReferenceSolution("B", solutionB)
        }
    }
}
