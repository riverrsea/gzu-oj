package cn.gzuoj.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AI 状态机和发布门禁测试。 */
class AiWorkflowTest {
    /** 主流程只能逐步前进，但非终态可以进入人工处理。 */
    @Test
    fun `accepts only declared transitions`() {
        assertFalse(AiWorkflow.canTransition(AiWorkflowState.DRAFT, AiWorkflowState.ANALYZING))
        assertTrue(AiWorkflow.canTransition(AiWorkflowState.ANALYZING, AiWorkflowState.GENERATING_SOLUTIONS))
        assertTrue(AiWorkflow.canTransition(AiWorkflowState.GENERATING_SOLUTIONS, AiWorkflowState.REVIEWING))
        assertTrue(AiWorkflow.canTransition(AiWorkflowState.REVIEWING, AiWorkflowState.NEEDS_REVIEW))
        assertFalse(AiWorkflow.canTransition(AiWorkflowState.DRAFT, AiWorkflowState.PUBLISHED))
        assertFalse(AiWorkflow.canTransition(AiWorkflowState.PUBLISHED, AiWorkflowState.CANCELED))
    }

    /** 差分失败的前两轮允许回到测试生成，普通流程转移仍保持单向。 */
    @Test
    fun `allows targeted repair transition`() {
        assertTrue(AiWorkflow.canRepair(AiWorkflowState.DIFFERENTIAL_TESTING, AiWorkflowState.GENERATING_TESTS))
        assertFalse(AiWorkflow.canRepair(AiWorkflowState.ANALYZING, AiWorkflowState.GENERATING_TESTS))
    }

    /** 任一确定性门禁失败都应阻止自动发布。 */
    @Test
    fun `requires every publication gate`() {
        val passed = AiPublicationGate(true, true, true, true, true, true)
        assertTrue(passed.allowsPublication())
        assertFalse(passed.copy(deterministic = false).allowsPublication())
    }

    /** 聚合大状态必须兼容旧的小状态，并只在门禁全部通过时显示 PASSING。 */
    @Test
    fun `maps legacy states to major states`() {
        assertEquals(AiMajorState.GENERATING_SOLUTIONS, AiWorkflow.majorState(AiWorkflowState.GENERATING_SOLUTIONS))
        assertEquals(AiMajorState.REVIEWING, AiWorkflow.majorState(AiWorkflowState.REVIEWING))
        assertEquals(AiMajorState.TESTS_GENERATING, AiWorkflow.majorState(AiWorkflowState.DIFFERENTIAL_TESTING))
        assertEquals(AiMajorState.VALIDATING, AiWorkflow.majorState(AiWorkflowState.VALIDATING))
        assertEquals(AiMajorState.PASSING, AiWorkflow.majorState(AiWorkflowState.VALIDATING, publicationGatePassed = true))
        assertEquals(AiMajorState.PUBLISHED, AiWorkflow.majorState(AiWorkflowState.PUBLISHED))
    }
}
