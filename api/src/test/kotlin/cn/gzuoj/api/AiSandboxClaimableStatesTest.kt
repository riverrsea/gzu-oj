package cn.gzuoj.api

import cn.gzuoj.shared.AiWorkflowState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 沙箱作业入队状态守卫测试。
 *
 * 实际踩过的坑：运行因为"题意存在未解决歧义"被交给人工复核（NEEDS_REVIEW）之后，
 * Agent 仍在继续产出并提交沙箱任务。作业被正常插成 QUEUED，但领取查询只认三个生成阶段，
 * 于是这条作业永远没有 Worker 领取，一直挂在队列里。
 */
class AiSandboxClaimableStatesTest {
    /** 三个生成阶段允许继续入队沙箱作业。 */
    @Test
    fun `生成阶段允许入队`() {
        listOf(
            AiWorkflowState.GENERATING_TESTS,
            AiWorkflowState.GENERATING_SOLUTIONS,
            AiWorkflowState.DIFFERENTIAL_TESTING,
        ).forEach { state ->
            assertTrue(canEnqueueSandboxJob(state), "$state 应当允许入队沙箱作业")
        }
    }

    /** 人工复核、校验与各类终态都不再接受新作业。 */
    @Test
    fun `非生成阶段拒绝入队`() {
        listOf(
            AiWorkflowState.DRAFT,
            AiWorkflowState.ANALYZING,
            AiWorkflowState.VALIDATING,
            AiWorkflowState.NEEDS_REVIEW,
            AiWorkflowState.PUBLISHED,
            AiWorkflowState.FAILED,
            AiWorkflowState.CANCELED,
        ).forEach { state ->
            assertFalse(canEnqueueSandboxJob(state), "$state 不应再接受沙箱作业")
        }
    }

    /** 领取查询内联的状态列表必须与守卫使用同一份定义，否则两边会悄悄漂移。 */
    @Test
    fun `领取查询复用同一份状态定义`() {
        val fromSql = AI_SANDBOX_CLAIMABLE_STATES_SQL.split(",").map { it.trim().trim('\'') }.sorted()
        val fromGuard = AI_SANDBOX_CLAIMABLE_STATES.map { it.name }.sorted()
        assertEquals(fromGuard, fromSql)
    }
}
