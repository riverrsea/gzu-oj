package cn.gzuoj.api

import cn.gzuoj.shared.AiWorkflowState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 沙箱作业入队判定测试。
 *
 * 实际踩过的两个坑：
 * 1. 运行因为"题意存在未解决歧义"被交给人工复核（NEEDS_REVIEW）之后，Agent 仍在继续产出并
 *    提交沙箱任务。作业被正常插成 QUEUED，但领取查询只认三个生成阶段，于是这条作业永远没有
 *    Worker 领取，一直挂在队列里——所以新作业键必须校验状态。
 * 2. 差分作业完成会把运行推进到 VALIDATING，而 Agent 恢复中断时会用同一个幂等键重放一次
 *    提交。若把状态校验放在幂等查询之前，已经跑通的流程会在最后一步被自己的守卫拦下——
 *    所以复用既有作业不能受状态限制。
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

    /** 全新作业键在非生成阶段才拒绝。 */
    @Test
    fun `全新作业键在非生成阶段被拒绝`() {
        assertNull(sandboxSubmitAction(null, AiWorkflowState.VALIDATING), "校验阶段不应接受全新沙箱作业")
        assertNull(sandboxSubmitAction(null, AiWorkflowState.NEEDS_REVIEW), "人工复核不应接受全新沙箱作业")
        assertNull(sandboxSubmitAction(null, AiWorkflowState.CANCELED), "已取消不应接受全新沙箱作业")
        assertEquals(SandboxSubmitAction.INSERT, sandboxSubmitAction(null, AiWorkflowState.GENERATING_TESTS))
    }

    /** 已取消的作业行只有在可领取状态下才能被复活，否则又会变成没人领取的孤儿。 */
    @Test
    fun `取消的作业行只在生成阶段复活`() {
        assertEquals(SandboxSubmitAction.REVIVE, sandboxSubmitAction("CANCELED", AiWorkflowState.DIFFERENTIAL_TESTING))
        assertNull(sandboxSubmitAction("CANCELED", AiWorkflowState.VALIDATING), "校验阶段不应复活沙箱作业")
    }

    /**
     * 复用既有作业不受状态限制——这是 LangGraph 重放差分节点的唯一出路。
     *
     * 实际踩过的坑：差分作业一完成，运行就被推进到 VALIDATING。Agent 恢复中断时会从头重跑
     * submit 节点并用同一个幂等键再提交一次，若先校验状态就会被判成"运行已离开生成阶段"，
     * 一条已经跑完生成、标程和差分的流程会在最后一步 409 崩掉。
     */
    @Test
    fun `既有作业在任意状态下都可复用`() {
        AiWorkflowState.entries.forEach { state ->
            listOf("QUEUED", "LEASED", "COMPLETED", "FAILED").forEach { status ->
                assertEquals(
                    SandboxSubmitAction.REUSE,
                    sandboxSubmitAction(status, state),
                    "$status 的既有作业在 $state 下应当直接复用",
                )
            }
        }
    }
}
