package cn.gzuoj.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AI 状态机和发布门禁测试。 */
class AiWorkflowTest {
    /** 主流程只能逐步前进，但非终态可以进入人工处理。 */
    @Test
    fun `accepts only declared transitions`() {
        assertTrue(AiWorkflow.canTransition(AiWorkflowState.DRAFT, AiWorkflowState.ANALYZING))
        assertTrue(AiWorkflow.canTransition(AiWorkflowState.REVIEWING, AiWorkflowState.NEEDS_REVIEW))
        assertFalse(AiWorkflow.canTransition(AiWorkflowState.DRAFT, AiWorkflowState.PUBLISHED))
        assertFalse(AiWorkflow.canTransition(AiWorkflowState.PUBLISHED, AiWorkflowState.CANCELED))
    }

    /** 任一确定性门禁失败都应阻止自动发布。 */
    @Test
    fun `requires every publication gate`() {
        val passed = AiPublicationGate(true, true, true, true, true, true)
        assertTrue(passed.allowsPublication())
        assertFalse(passed.copy(deterministic = false).allowsPublication())
    }
}
