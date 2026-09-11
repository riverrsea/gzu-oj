package cn.gzuoj.shared

import java.time.Instant
import java.util.UUID

/** AI 测试数据沙箱任务的不可变执行参数。 */
data class AiSandboxTaskPayload(
    /** 两份独立标程中的 A，固定使用 GNU C++17。 */
    val solutionASource: String,
    /** 两份独立标程中的 B，固定使用 GNU C++17。 */
    val solutionBSource: String,
    /** 小规模数据使用的独立暴力解，固定使用 GNU C++17。 */
    val bruteForceSource: String,
    /** 接收一个命令行种子并向标准输出写入完整输入的生成器。 */
    val generatorSource: String,
    /** 从标准输入读取生成数据并以退出码表示是否合法的校验器。 */
    val validatorSource: String,
    /** 生成测试点使用的互异固定种子。 */
    val seeds: List<Long>,
    /** 从种子列表头部选择、额外执行暴力差分的测试点数量。 */
    val bruteForceCaseCount: Int,
    /** 标程的 CPU 时间限制，单位毫秒。 */
    val timeLimitMs: Long,
    /** 标程的内存限制，单位 MiB。 */
    val memoryLimitMiB: Long,
)

/** Worker 领取到的一份完整 AI 测试生成与差分任务。 */
data class AiSandboxLease(
    /** AI 沙箱队列任务标识。 */
    val jobId: UUID,
    /** AI 录题运行标识。 */
    val runId: UUID,
    /** 需要写入测试点的题目草稿版本。 */
    val problemVersionId: UUID,
    /** 本轮执行尝试标识。 */
    val attemptId: UUID,
    /** 仅本轮租约可使用的明文令牌。 */
    val leaseToken: String,
    /** 当前租约过期时间。 */
    val leaseExpiresAt: Instant,
    /** 本次生成和差分使用的不可变参数。 */
    val task: AiSandboxTaskPayload,
)

/** AI 沙箱任务的结算状态。 */
enum class AiSandboxCompletionStatus {
    /** 全部测试生成、校验和差分步骤通过。 */
    PASSED,

    /** 模型生成的源码、数据或输出未通过确定性门禁。 */
    VALIDATION_FAILED,

    /** go-judge、网络或其他判题基础设施异常。 */
    SYSTEM_ERROR,
}

/** 沙箱校验失败时出错的产物归属，供 Agent 只重生成对应节点。 */
enum class AiSandboxFailureStage {
    /** 输入生成器或输入校验器。 */
    TEST_DATA,

    /** 两份标程之一。 */
    SOLUTIONS,

    /** 小数据暴力解。 */
    BRUTE_FORCE,
}

/** 单个 AI 生成测试点的完整差分证据。 */
data class AiGeneratedCaseResult(
    /** 测试点顺序，从 1 开始。 */
    val ordinal: Int,
    /** 生成该测试点的固定随机种子。 */
    val seed: Long,
    /** 已由校验器接受的完整输入。 */
    val input: String,
    /** 由两份标程和可用暴力解共同确认的规范标准输出。 */
    val expectedOutput: String,
    /** 输入内容的 SHA-256。 */
    val inputSha256: String,
    /** 规范标准输出的 SHA-256。 */
    val outputSha256: String,
    /** 标程 A 规范输出的 SHA-256。 */
    val solutionAOutputSha256: String,
    /** 标程 B 规范输出的 SHA-256。 */
    val solutionBOutputSha256: String,
    /** 小规模测试点暴力解规范输出的 SHA-256；大数据测试点为空。 */
    val bruteForceOutputSha256: String? = null,
    /** 标程 A 的 CPU 时间，单位毫秒。 */
    val solutionATimeMs: Long,
    /** 标程 A 的峰值内存，单位 KiB。 */
    val solutionAMemoryKiB: Long,
    /** 标程 B 的 CPU 时间，单位毫秒。 */
    val solutionBTimeMs: Long,
    /** 标程 B 的峰值内存，单位 KiB。 */
    val solutionBMemoryKiB: Long,
)

/** Worker 对 AI 测试生成租约的幂等结算请求。 */
data class AiSandboxCompletion(
    /** 本轮执行尝试标识。 */
    val attemptId: UUID,
    /** 本轮租约明文令牌。 */
    val leaseToken: String,
    /** 本次任务的最终状态。 */
    val status: AiSandboxCompletionStatus,
    /** 通过全部差分门禁的候选测试点。 */
    val testCases: List<AiGeneratedCaseResult> = emptyList(),
    /** 相同种子重复运行生成器是否得到完全相同的输入。 */
    val deterministic: Boolean = false,
    /** 两份标程在全部生成输入上的规范输出是否一致。 */
    val solutionsAgree: Boolean = false,
    /** 小规模测试点是否全部通过暴力解差分。 */
    val bruteForcePassed: Boolean = false,
    /** 标程最坏 CPU 时间占题目限制的百分比。 */
    val maximumTimePercent: Int = 0,
    /** 标程最坏内存占题目限制的百分比。 */
    val maximumMemoryPercent: Int = 0,
    /** 失败原因；通过时为空。 */
    val failureReason: String? = null,
    /** 失败产物归属；仅校验失败时可能非空，用于 Agent 定向重生成。 */
    val failedStage: AiSandboxFailureStage? = null,
)
