package cn.gzuoj.worker

import cn.gzuoj.shared.AiGeneratedCaseResult
import cn.gzuoj.shared.AiSandboxCompletion
import cn.gzuoj.shared.AiSandboxCompletionStatus
import cn.gzuoj.shared.AiSandboxFailureStage
import cn.gzuoj.shared.AiSandboxLease
import cn.gzuoj.shared.OutputComparator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest
import kotlin.math.ceil

/** 模型生成的源码、输入或答案未通过确定性校验。 */
private class AiValidationFailure(
    /** 管理员可见的失败原因。 */
    override val message: String,
    /** 出错产物归属，供 Agent 只重生成对应节点。 */
    val stage: AiSandboxFailureStage,
) : RuntimeException(message)

/** go-judge、网络、租约或返回协议发生基础设施异常。 */
private class AiSandboxInfrastructureFailure(
    /** 仅用于 Worker 和管理员排障的摘要。 */
    override val message: String,
) : RuntimeException(message)

/** AI 源码编译后保留在 go-judge 缓存中的程序。 */
private data class AiCompiledProgram(
    /** 程序角色名称。 */
    val label: String,
    /** 放入执行沙箱的文件名。 */
    val fileName: String,
    /** go-judge 缓存文件标识。 */
    val fileId: String,
    /** 该程序出错时对应的产物归属。 */
    val stage: AiSandboxFailureStage,
)

/** 一次标程或暴力解运行的规范输出与资源证据。 */
private data class AiProgramOutput(
    /** 按 OJ 文本比较规则规范化后的输出。 */
    val normalized: String,
    /** 规范输出 SHA-256。 */
    val sha256: String,
    /** CPU 时间，单位毫秒。 */
    val timeMs: Long,
    /** 峰值内存，单位 KiB。 */
    val memoryKiB: Long,
)

/** 在 go-judge 中执行确定性生成、输入校验和多解差分。 */
@Component
class AiSandboxEngine(
    /** go-judge 内部 REST 客户端。 */
    private val goJudge: GoJudgeClient,
) {
    /** 执行一份完整 AI 沙箱租约，并把模型问题与基础设施问题分开结算。 */
    fun execute(lease: AiSandboxLease, leaseStillValid: () -> Boolean): AiSandboxCompletion = try {
        ensureLease(leaseStillValid)
        val solutionA = compile("标程 A", lease.task.solutionASource, AiSandboxFailureStage.SOLUTIONS)
        ensureLease(leaseStillValid)
        val solutionB = compile("标程 B", lease.task.solutionBSource, AiSandboxFailureStage.SOLUTIONS)
        ensureLease(leaseStillValid)
        val bruteForce = compile("暴力解", lease.task.bruteForceSource, AiSandboxFailureStage.BRUTE_FORCE)
        ensureLease(leaseStillValid)
        val generator = compile("测试生成器", lease.task.generatorSource, AiSandboxFailureStage.TEST_DATA)
        ensureLease(leaseStillValid)
        val validator = compile("输入校验器", lease.task.validatorSource, AiSandboxFailureStage.TEST_DATA)

        var totalDataBytes = 0L
        val inputHashes = mutableSetOf<String>()
        val cases = lease.task.seeds.mapIndexed { index, seed ->
            ensureLease(leaseStillValid)
            val firstInput = generate(generator, seed)
            ensureLease(leaseStillValid)
            val secondInput = generate(generator, seed)
            if (firstInput != secondInput) {
                throw AiValidationFailure("测试生成器在种子 $seed 下不能复现完全相同的输入", AiSandboxFailureStage.TEST_DATA)
            }
            val inputHash = sha256(firstInput)
            if (!inputHashes.add(inputHash)) {
                throw AiValidationFailure("不同固定种子生成了重复测试输入，种子：$seed", AiSandboxFailureStage.TEST_DATA)
            }
            validateInput(validator, firstInput, seed)

            ensureLease(leaseStillValid)
            val outputA = runAnswer(solutionA, firstInput, lease.task.timeLimitMs, lease.task.memoryLimitMiB)
            ensureLease(leaseStillValid)
            val outputB = runAnswer(solutionB, firstInput, lease.task.timeLimitMs, lease.task.memoryLimitMiB)
            if (outputA.normalized != outputB.normalized) {
                throw AiValidationFailure("两份标程在第 ${index + 1} 个测试点（种子 $seed）输出不一致", AiSandboxFailureStage.SOLUTIONS)
            }

            val bruteOutput = if (index < lease.task.bruteForceCaseCount) {
                ensureLease(leaseStillValid)
                runAnswer(
                    bruteForce,
                    firstInput,
                    bruteForceTimeLimit(lease.task.timeLimitMs),
                    lease.task.memoryLimitMiB,
                ).also { output ->
                    if (output.normalized != outputA.normalized) {
                        throw AiValidationFailure("暴力解在第 ${index + 1} 个小数据测试点（种子 $seed）与标程不一致", AiSandboxFailureStage.BRUTE_FORCE)
                    }
                }
            } else {
                null
            }
            totalDataBytes += firstInput.toByteArray(Charsets.UTF_8).size
            totalDataBytes += outputA.normalized.toByteArray(Charsets.UTF_8).size
            if (totalDataBytes > MAX_TOTAL_DATA_BYTES) {
                throw AiValidationFailure("生成的测试点输入输出总大小超过 64 MiB", AiSandboxFailureStage.TEST_DATA)
            }
            AiGeneratedCaseResult(
                ordinal = index + 1,
                seed = seed,
                input = firstInput,
                expectedOutput = outputA.normalized,
                inputSha256 = inputHash,
                outputSha256 = outputA.sha256,
                solutionAOutputSha256 = outputA.sha256,
                solutionBOutputSha256 = outputB.sha256,
                bruteForceOutputSha256 = bruteOutput?.sha256,
                solutionATimeMs = outputA.timeMs,
                solutionAMemoryKiB = outputA.memoryKiB,
                solutionBTimeMs = outputB.timeMs,
                solutionBMemoryKiB = outputB.memoryKiB,
            )
        }
        val maximumTimeMs = cases.maxOfOrNull { maxOf(it.solutionATimeMs, it.solutionBTimeMs) } ?: 0
        val maximumMemoryKiB = cases.maxOfOrNull { maxOf(it.solutionAMemoryKiB, it.solutionBMemoryKiB) } ?: 0
        AiSandboxCompletion(
            attemptId = lease.attemptId,
            leaseToken = lease.leaseToken,
            status = AiSandboxCompletionStatus.PASSED,
            testCases = cases,
            deterministic = true,
            solutionsAgree = true,
            bruteForcePassed = true,
            maximumTimePercent = percentage(maximumTimeMs, lease.task.timeLimitMs),
            maximumMemoryPercent = percentage(maximumMemoryKiB, lease.task.memoryLimitMiB * KIB),
        )
    } catch (failure: AiValidationFailure) {
        logger.info("AI 测试生成未通过，jobId={}，原因={}", lease.jobId, failure.message)
        failed(lease, AiSandboxCompletionStatus.VALIDATION_FAILED, failure.message, failure.stage)
    } catch (failure: AiSandboxInfrastructureFailure) {
        logger.error("AI 沙箱基础设施失败，jobId={}，原因={}", lease.jobId, failure.message)
        failed(lease, AiSandboxCompletionStatus.SYSTEM_ERROR, failure.message)
    } catch (failure: RemoteCallException) {
        logger.error("AI 沙箱远程调用失败，jobId={}，原因={}", lease.jobId, failure.message)
        failed(lease, AiSandboxCompletionStatus.SYSTEM_ERROR, failure.message)
    } catch (failure: Exception) {
        logger.error("AI 沙箱发生未预期异常，jobId={}", lease.jobId, failure)
        failed(lease, AiSandboxCompletionStatus.SYSTEM_ERROR, failure.message ?: "AI 沙箱发生未预期异常")
    }

    /** 编译一份 GNU C++17 源码，并区分模型编译错误和 go-judge 异常。 */
    private fun compile(label: String, source: String, stage: AiSandboxFailureStage): AiCompiledProgram {
        val sourceName = "main.cpp"
        val artifactName = "program"
        val result = goJudge.run(
            GoJudgeCommand(
                args = listOf("/usr/bin/g++", sourceName, "-std=gnu++17", "-O2", "-pipe", "-o", artifactName),
                env = SAFE_ENVIRONMENT,
                files = outputFiles(COMPILER_OUTPUT_LIMIT),
                cpuLimit = COMPILE_CPU_SECONDS * SECOND_NS,
                clockLimit = COMPILE_CLOCK_SECONDS * SECOND_NS,
                memoryLimit = COMPILE_MEMORY_MIB * MIB,
                stackLimit = COMPILE_STACK_MIB * MIB,
                procLimit = COMPILE_PROCESS_LIMIT,
                copyIn = mapOf(sourceName to GoJudgeFile(content = source)),
                copyOut = listOf(STDOUT, STDERR),
                copyOutCached = listOf(artifactName),
                copyOutMax = COMPILER_OUTPUT_LIMIT * 2 + MIB,
                strictMemoryLimit = true,
            ),
        )
        if (result.status != ACCEPTED) {
            if (isInfrastructureStatus(result.status)) {
                throw AiSandboxInfrastructureFailure("$label 编译沙箱异常：${result.status}")
            }
            val message = (result.files[STDERR].orEmpty() + result.files[STDOUT].orEmpty())
                .ifBlank { result.error ?: result.status }
                .take(ERROR_MESSAGE_LIMIT)
            throw AiValidationFailure("$label 编译失败：$message", stage)
        }
        val fileId = result.fileIds[artifactName]
            ?: throw AiSandboxInfrastructureFailure("$label 编译成功但 go-judge 未返回缓存制品")
        return AiCompiledProgram(label, artifactName, fileId, stage)
    }

    /** 使用十进制固定种子运行生成器并返回原始输入，确定性比较不做文本规范化。 */
    private fun generate(generator: AiCompiledProgram, seed: Long): String {
        val result = runProgram(
            program = generator,
            input = "",
            arguments = listOf(seed.toString()),
            timeLimitMs = GENERATOR_TIME_LIMIT_MS,
            memoryLimitMiB = GENERATOR_MEMORY_LIMIT_MIB,
            outputLimit = CASE_DATA_LIMIT,
        )
        requireAccepted(result, generator, "种子 $seed")
        return result.files[STDOUT].orEmpty()
    }

    /** 由独立校验器确认生成输入满足题面约束。 */
    private fun validateInput(validator: AiCompiledProgram, input: String, seed: Long) {
        val result = runProgram(
            program = validator,
            input = input,
            arguments = emptyList(),
            timeLimitMs = VALIDATOR_TIME_LIMIT_MS,
            memoryLimitMiB = VALIDATOR_MEMORY_LIMIT_MIB,
            outputLimit = VALIDATOR_OUTPUT_LIMIT,
        )
        requireAccepted(result, validator, "种子 $seed 生成的输入")
    }

    /** 运行标程或暴力解，并按平台规则规范化输出。 */
    private fun runAnswer(
        program: AiCompiledProgram,
        input: String,
        timeLimitMs: Long,
        memoryLimitMiB: Long,
    ): AiProgramOutput {
        val result = runProgram(
            program = program,
            input = input,
            arguments = emptyList(),
            timeLimitMs = timeLimitMs,
            memoryLimitMiB = memoryLimitMiB,
            outputLimit = CASE_DATA_LIMIT,
        )
        requireAccepted(result, program, "差分输入")
        val normalized = OutputComparator.normalize(result.files[STDOUT].orEmpty())
        return AiProgramOutput(
            normalized = normalized,
            sha256 = sha256(normalized),
            timeMs = result.time.coerceAtLeast(0) / MILLISECOND_NS,
            memoryKiB = result.memory.coerceAtLeast(0) / KIB,
        )
    }

    /** 在固定网络、文件和进程限制下执行一个已缓存程序。 */
    private fun runProgram(
        program: AiCompiledProgram,
        input: String,
        arguments: List<String>,
        timeLimitMs: Long,
        memoryLimitMiB: Long,
        outputLimit: Long,
    ): GoJudgeResult = goJudge.run(
        GoJudgeCommand(
            args = listOf("./${program.fileName}") + arguments,
            env = SAFE_ENVIRONMENT,
            files = listOf(
                GoJudgeFile(content = input),
                GoJudgeFile(name = STDOUT, max = outputLimit),
                GoJudgeFile(name = STDERR, max = RUNTIME_ERROR_LIMIT),
            ),
            cpuLimit = timeLimitMs * MILLISECOND_NS,
            clockLimit = (timeLimitMs * CLOCK_MULTIPLIER + CLOCK_GRACE_MS) * MILLISECOND_NS,
            memoryLimit = memoryLimitMiB * MIB,
            stackLimit = minOf(memoryLimitMiB, DEFAULT_STACK_MIB) * MIB,
            procLimit = 1,
            copyIn = mapOf(program.fileName to GoJudgeFile(fileId = program.fileId)),
            copyOut = listOf(STDOUT, STDERR),
            copyOutMax = outputLimit + RUNTIME_ERROR_LIMIT,
            strictMemoryLimit = true,
            addressSpaceLimit = true,
        ),
    )

    /** 检查用户生成程序状态，go-judge 内部错误进入基础设施重试，其余进入人工审查。 */
    private fun requireAccepted(result: GoJudgeResult, program: AiCompiledProgram, context: String) {
        if (result.status == ACCEPTED) return
        if (isInfrastructureStatus(result.status)) {
            throw AiSandboxInfrastructureFailure("${program.label} 在${context}执行时沙箱异常：${result.status}")
        }
        val detail = result.files[STDERR].orEmpty().ifBlank { result.error ?: result.status }.take(ERROR_MESSAGE_LIMIT)
        throw AiValidationFailure("${program.label} 在${context}执行失败：${result.status}；$detail", program.stage)
    }

    /** 形成失败结算，不携带任何未完整校验的候选测试点。 */
    private fun failed(
        lease: AiSandboxLease,
        status: AiSandboxCompletionStatus,
        reason: String,
        stage: AiSandboxFailureStage? = null,
    ): AiSandboxCompletion = AiSandboxCompletion(
        attemptId = lease.attemptId,
        leaseToken = lease.leaseToken,
        status = status,
        failureReason = reason.take(ERROR_MESSAGE_LIMIT),
        failedStage = stage,
    )

    /** 租约失效后停止继续调用沙箱。 */
    private fun ensureLease(leaseStillValid: () -> Boolean) {
        if (!leaseStillValid()) throw AiSandboxInfrastructureFailure("AI 任务租约已失效")
    }

    /** 暴力解仅运行前三个小数据，可使用更宽松但有硬上限的时间。 */
    private fun bruteForceTimeLimit(problemTimeLimitMs: Long): Long =
        maxOf(problemTimeLimitMs * 5, MIN_BRUTE_FORCE_TIME_MS).coerceAtMost(MAX_BRUTE_FORCE_TIME_MS)

    /** 计算资源用量相对限制的向上取整百分比。 */
    private fun percentage(used: Long, limit: Long): Int =
        if (used <= 0 || limit <= 0) 0 else ceil(used.toDouble() * 100.0 / limit.toDouble()).toInt()

    /** 计算 UTF-8 文本的 SHA-256。 */
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** 编译器标准输入、输出和错误收集器。 */
    private fun outputFiles(limit: Long): List<GoJudgeFile> = listOf(
        GoJudgeFile(content = ""),
        GoJudgeFile(name = STDOUT, max = limit),
        GoJudgeFile(name = STDERR, max = limit),
    )

    /** 判断 go-judge 自身而非模型程序是否失败。 */
    private fun isInfrastructureStatus(status: String): Boolean = status in setOf("Internal Error", "File Error")

    private companion object {
        /** 日志记录器。 */
        val logger = LoggerFactory.getLogger(AiSandboxEngine::class.java)

        /** go-judge 成功状态。 */
        const val ACCEPTED: String = "Accepted"

        /** 标准输出收集器名称。 */
        const val STDOUT: String = "stdout"

        /** 标准错误收集器名称。 */
        const val STDERR: String = "stderr"

        /** 纳秒到毫秒换算。 */
        const val MILLISECOND_NS: Long = 1_000_000L

        /** 一秒包含的纳秒。 */
        const val SECOND_NS: Long = 1_000_000_000L

        /** 二进制 KiB。 */
        const val KIB: Long = 1024L

        /** 二进制 MiB。 */
        const val MIB: Long = KIB * KIB

        /** 单个测试输入或输出的上限。 */
        const val CASE_DATA_LIMIT: Long = 16L * MIB

        /** 一份 AI 任务返回的全部测试输入输出上限。 */
        const val MAX_TOTAL_DATA_BYTES: Long = 64L * MIB

        /** 编译 CPU 时间上限。 */
        const val COMPILE_CPU_SECONDS: Long = 10

        /** 编译墙钟时间上限。 */
        const val COMPILE_CLOCK_SECONDS: Long = 20

        /** 编译内存上限。 */
        const val COMPILE_MEMORY_MIB: Long = 512

        /** 编译栈上限。 */
        const val COMPILE_STACK_MIB: Long = 128

        /** 编译器进程和线程上限。 */
        const val COMPILE_PROCESS_LIMIT: Int = 64

        /** 单个编译器输出流上限。 */
        const val COMPILER_OUTPUT_LIMIT: Long = 64L * KIB

        /** 生成器 CPU 时间上限。 */
        const val GENERATOR_TIME_LIMIT_MS: Long = 5_000

        /** 生成器内存上限。 */
        const val GENERATOR_MEMORY_LIMIT_MIB: Long = 256

        /** 输入校验器 CPU 时间上限。 */
        const val VALIDATOR_TIME_LIMIT_MS: Long = 2_000

        /** 输入校验器内存上限。 */
        const val VALIDATOR_MEMORY_LIMIT_MIB: Long = 256

        /** 输入校验器输出上限。 */
        const val VALIDATOR_OUTPUT_LIMIT: Long = 4L * KIB

        /** 暴力解最短 CPU 时间。 */
        const val MIN_BRUTE_FORCE_TIME_MS: Long = 5_000

        /** 暴力解最长 CPU 时间。 */
        const val MAX_BRUTE_FORCE_TIME_MS: Long = 30_000

        /** 墙钟时间相对 CPU 时间的倍率。 */
        const val CLOCK_MULTIPLIER: Long = 3

        /** 墙钟启动宽限，单位毫秒。 */
        const val CLOCK_GRACE_MS: Long = 1_000

        /** 默认最大栈空间。 */
        const val DEFAULT_STACK_MIB: Long = 64

        /** 单测试点标准错误收集上限。 */
        const val RUNTIME_ERROR_LIMIT: Long = 64L * KIB

        /** 失败原因最大字符数。 */
        const val ERROR_MESSAGE_LIMIT: Int = 2_000

        /** 沙箱程序使用的固定最小环境。 */
        val SAFE_ENVIRONMENT: List<String> = listOf(
            "PATH=/usr/bin:/bin",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "HOME=/tmp",
        )
    }
}
