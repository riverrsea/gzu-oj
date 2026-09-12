package cn.gzuoj.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AI 状态机和发布门禁测试。 */
class AiWorkflowTest {
    /** 主流程只能逐步前进，但非终态可以进入人工处理。 */
    @Test
    fun `accepts only declared transitions`() {
        assertFalse(AiWorkflow.canTransition(AiWorkflowState.DRAFT, AiWorkflowState.ANALYZING))
        assertTrue(AiWorkflow.canTransition(AiWorkflowState.ANALYZING, AiWorkflowState.GENERATING_TESTS))
        assertTrue(AiWorkflow.canTransition(AiWorkflowState.GENERATING_TESTS, AiWorkflowState.GENERATING_SOLUTIONS))
        assertTrue(AiWorkflow.canTransition(AiWorkflowState.GENERATING_SOLUTIONS, AiWorkflowState.DIFFERENTIAL_TESTING))
        assertTrue(AiWorkflow.canTransition(AiWorkflowState.GENERATING_SOLUTIONS, AiWorkflowState.NEEDS_REVIEW))
        assertFalse(AiWorkflow.canTransition(AiWorkflowState.DRAFT, AiWorkflowState.PUBLISHED))
        assertFalse(AiWorkflow.canTransition(AiWorkflowState.PUBLISHED, AiWorkflowState.CANCELED))
    }

    /** 差分失败的前两轮允许定向回到测试或标程生成，普通流程转移仍保持单向。 */
    @Test
    fun `allows targeted repair transition`() {
        assertTrue(AiWorkflow.canRepair(AiWorkflowState.DIFFERENTIAL_TESTING, AiWorkflowState.GENERATING_TESTS))
        assertTrue(AiWorkflow.canRepair(AiWorkflowState.DIFFERENTIAL_TESTING, AiWorkflowState.GENERATING_SOLUTIONS))
        assertFalse(AiWorkflow.canRepair(AiWorkflowState.DIFFERENTIAL_TESTING, AiWorkflowState.ANALYZING))
        assertFalse(AiWorkflow.canRepair(AiWorkflowState.ANALYZING, AiWorkflowState.GENERATING_TESTS))
    }

    /** 任一确定性门禁失败都应阻止自动发布。 */
    @Test
    fun `requires every publication gate`() {
        val passed = AiPublicationGate(true, true, true, true, true)
        assertTrue(passed.allowsPublication())
        assertFalse(passed.copy(deterministic = false).allowsPublication())
    }
}
