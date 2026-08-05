package cn.gzuoj.shared

/** AI 录题流程状态。 */
enum class AiWorkflowState {
    /** 管理员草稿。 */
    DRAFT,
    /** 解析题意和歧义。 */
    ANALYZING,
    /** 并行生成两份独立标程。 */
    GENERATING_SOLUTIONS,
    /** 对抗审查标程与测试计划。 */
    REVIEWING,
    /** 生成确定性测试数据。 */
    GENERATING_TESTS,
    /** 执行暴力与双标程差分。 */
    DIFFERENTIAL_TESTING,
    /** 校验约束、分值、资源和复现性。 */
    VALIDATING,
    /** 已通过门禁并发布。 */
    PUBLISHED,
    /** 需要管理员处理歧义或反例。 */
    NEEDS_REVIEW,
    /** 流程不可恢复失败。 */
    FAILED,
    /** 管理员取消流程。 */
    CANCELED,
}

/** AI 自动发布所需的确定性校验结果。 */
data class AiPublicationGate(
    /** 两份独立标程是否在全部数据上输出一致。 */
    val solutionsAgree: Boolean,
    /** 小数据是否通过暴力解差分。 */
    val bruteForcePassed: Boolean,
    /** 测试点分值之和是否为 100。 */
    val scoreSumIsOneHundred: Boolean,
    /** 固定种子是否能复现全部输入。 */
    val deterministic: Boolean,
    /** 资源消耗是否留有规定余量。 */
    val resourceMarginPassed: Boolean,
    /** 是否不存在未解决题意歧义。 */
    val noUnresolvedAmbiguity: Boolean,
) {
    /** 返回是否允许自动发布。 */
    fun allowsPublication(): Boolean =
        solutionsAgree &&
            bruteForcePassed &&
            scoreSumIsOneHundred &&
            deterministic &&
            resourceMarginPassed &&
            noUnresolvedAmbiguity
}

/** AI 状态机的唯一转移规则来源。 */
object AiWorkflow {
    /** 正常主流程中每个状态的后继状态。 */
    private val normalTransitions = mapOf(
        AiWorkflowState.DRAFT to AiWorkflowState.ANALYZING,
        AiWorkflowState.ANALYZING to AiWorkflowState.GENERATING_SOLUTIONS,
        AiWorkflowState.GENERATING_SOLUTIONS to AiWorkflowState.REVIEWING,
        AiWorkflowState.REVIEWING to AiWorkflowState.GENERATING_TESTS,
        AiWorkflowState.GENERATING_TESTS to AiWorkflowState.DIFFERENTIAL_TESTING,
        AiWorkflowState.DIFFERENTIAL_TESTING to AiWorkflowState.VALIDATING,
        AiWorkflowState.VALIDATING to AiWorkflowState.PUBLISHED,
    )

    /** 判断一次状态转换是否合法。 */
    fun canTransition(from: AiWorkflowState, to: AiWorkflowState): Boolean {
        if (from in terminalStates) return false
        if (to in interruptionStates) return true
        return normalTransitions[from] == to
    }

    /** 校验状态转换，不合法时抛出包含上下文的异常。 */
    fun requireTransition(from: AiWorkflowState, to: AiWorkflowState) {
        require(canTransition(from, to)) { "非法 AI 状态转换：$from -> $to" }
    }

    /** 不允许继续流转的终态。 */
    private val terminalStates = setOf(
        AiWorkflowState.PUBLISHED,
        AiWorkflowState.FAILED,
        AiWorkflowState.CANCELED,
    )

    /** 任一非终态都可进入的人工或异常分支。 */
    private val interruptionStates = setOf(
        AiWorkflowState.NEEDS_REVIEW,
        AiWorkflowState.FAILED,
        AiWorkflowState.CANCELED,
    )
}
