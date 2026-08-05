package cn.gzuoj.shared

import java.time.Instant
import java.util.UUID

/** 判题支持的编程语言。 */
enum class JudgeLanguage {
    /** GNU C17。 */
    C17,

    /** GNU C++17。 */
    CPP17,

    /** OpenJDK 21。 */
    JAVA21,

    /** CPython 3。 */
    PYTHON3,
}

/** 判题任务的执行模式。 */
enum class JudgeExecutionMode {
    /** 正式提交，执行锁定版本的全部隐藏测试点并计分。 */
    SUBMIT,

    /** 公开运行，仅执行用户可见且可编辑的输入，不计分。 */
    RUN,
}

/** 提交与判题任务共享的状态集合。 */
enum class JudgeStatus {
    /** 已入持久化队列。 */
    QUEUED,

    /** 正在编译。 */
    COMPILING,

    /** 正在逐点判题。 */
    JUDGING,

    /** 满分通过。 */
    AC,

    /** 部分测试点通过。 */
    PARTIAL,

    /** 答案错误。 */
    WA,

    /** 编译错误。 */
    CE,

    /** 超出时间限制。 */
    TLE,

    /** 超出内存限制。 */
    MLE,

    /** 运行时错误。 */
    RE,

    /** 超出输出限制。 */
    OLE,

    /** 判题基础设施异常。 */
    SYSTEM_ERROR,

    /** 提交或任务已取消。 */
    CANCELED,
}

/** 判题任务的业务优先级。 */
object JudgePriority {
    /** 公开训练赛提交优先级。 */
    const val PUBLIC_CONTEST: Int = 100

    /** 普通练习和个人计时提交优先级。 */
    const val PRACTICE: Int = 50

    /** AI 生成和差分任务优先级。 */
    const val AI_SANDBOX: Int = 10
}

/** 单个隐藏测试点的租约数据。 */
data class JudgeCaseLease(
    /** 测试点数据库标识。 */
    val caseId: UUID,
    /** 测试点显示序号。 */
    val ordinal: Int,
    /** 该测试点分值。 */
    val score: Int,
    /** 输入制品的租约绑定下载地址。 */
    val inputUrl: String? = null,
    /** 输入制品 SHA-256。 */
    val inputSha256: String? = null,
    /** 标准输出制品的租约绑定下载地址。 */
    val expectedOutputUrl: String? = null,
    /** 标准输出制品 SHA-256。 */
    val expectedOutputSha256: String? = null,
    /** 公开运行模式使用的用户输入；正式提交时为空。 */
    val inlineInput: String? = null,
)

/** Worker 领取到的一份完整提交任务。 */
data class JudgeLease(
    /** 队列任务标识。 */
    val jobId: UUID,
    /** 提交标识。 */
    val submissionId: UUID,
    /** 本轮执行尝试标识。 */
    val attemptId: UUID,
    /** 仅本轮租约可使用的明文令牌。 */
    val leaseToken: String,
    /** 租约过期时间。 */
    val leaseExpiresAt: Instant,
    /** 正式提交或公开运行。 */
    val executionMode: JudgeExecutionMode = JudgeExecutionMode.SUBMIT,
    /** 提交语言。 */
    val language: JudgeLanguage,
    /** 用户源代码。 */
    val sourceCode: String,
    /** 该语言实际时间限制，单位毫秒。 */
    val timeLimitMs: Long,
    /** 该语言实际内存限制，单位 MiB。 */
    val memoryLimitMiB: Long,
    /** 按序执行的隐藏测试点。 */
    val testCases: List<JudgeCaseLease>,
)

/** 单个测试点的 Worker 结算结果。 */
data class JudgeCaseResult(
    /** 测试点标识。 */
    val caseId: UUID,
    /** 测点状态。 */
    val status: JudgeStatus,
    /** 获得分值。 */
    val score: Int,
    /** 实际 CPU 时间，单位毫秒。 */
    val timeMs: Long,
    /** 峰值内存，单位 KiB。 */
    val memoryKiB: Long,
    /** 对用户安全的简短错误说明。 */
    val message: String? = null,
    /** 公开运行时返回的实际标准输出；正式提交永远为空。 */
    val actualOutput: String? = null,
)

/** Worker 对一次租约的幂等结算请求。 */
data class JudgeCompletion(
    /** 本轮执行尝试标识。 */
    val attemptId: UUID,
    /** 本轮租约明文令牌。 */
    val leaseToken: String,
    /** 最终提交状态。 */
    val status: JudgeStatus,
    /** 最终得分，范围为 0 到 100。 */
    val score: Int,
    /** 编译器输出，仅在编译失败时对用户可见。 */
    val compileMessage: String? = null,
    /** 各隐藏测试点的脱敏结果。 */
    val testCases: List<JudgeCaseResult> = emptyList(),
    /** 基础设施异常摘要，用于审计和重试判定。 */
    val systemMessage: String? = null,
)

/** 各语言相对于题目基准限制的倍率。 */
data class LanguageMultiplier(
    /** 时间倍率。 */
    val time: Int,
    /** 内存倍率。 */
    val memory: Int,
)

/** 固定的首版语言倍率表。 */
object LanguageLimits {
    /** 返回指定语言的时间与内存倍率。 */
    fun multiplier(language: JudgeLanguage): LanguageMultiplier =
        when (language) {
            JudgeLanguage.C17, JudgeLanguage.CPP17 -> LanguageMultiplier(time = 1, memory = 1)
            JudgeLanguage.JAVA21 -> LanguageMultiplier(time = 2, memory = 2)
            JudgeLanguage.PYTHON3 -> LanguageMultiplier(time = 3, memory = 2)
        }
}
