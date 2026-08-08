package cn.gzuoj.worker

import cn.gzuoj.shared.JudgeCaseLease
import cn.gzuoj.shared.JudgeCaseResult
import cn.gzuoj.shared.JudgeCompletion
import cn.gzuoj.shared.JudgeExecutionMode
import cn.gzuoj.shared.JudgeLanguage
import cn.gzuoj.shared.JudgeLease
import cn.gzuoj.shared.JudgeStatus
import cn.gzuoj.shared.OutputComparator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 编译失败及可向提交者展示的编译器输出。 */
private class CompilationFailure(
    /** 已截断的编译器输出。 */
    val compilerMessage: String,
) : RuntimeException(compilerMessage)

/** go-judge 或制品服务发生的基础设施异常。 */
private class JudgeInfrastructureFailure(
    /** 只供运维审计使用的摘要。 */
    override val message: String,
) : RuntimeException(message)

/** 编译后在后续测试点复用的沙箱缓存制品。 */
private data class CompiledProgram(
    /** 缓存制品放入执行沙箱时的文件名。 */
    val fileName: String,
    /** go-judge 返回的缓存标识。 */
    val fileId: String,
    /** 执行程序和参数。 */
    val runArgs: List<String>,
    /** 运行时允许的进程或线程数量。 */
    val processLimit: Int,
    /** 是否同步限制虚拟地址空间。 */
    val addressSpaceLimit: Boolean,
)

/** 四种首版语言的编译一次、逐点运行判题引擎。 */
@Component
class JudgeEngine(
    /** go-judge 内部客户端。 */
    private val goJudge: GoJudgeClient,
    /** 控制端制品下载客户端。 */
    private val control: ControlPlaneClient,
) {
    /** 执行一份完整提交并形成幂等结算内容。 */
    fun judge(lease: JudgeLease, leaseStillValid: () -> Boolean): JudgeCompletion {
        return try {
            ensureLease(leaseStillValid)
            val program = compile(lease)
            ensureLease(leaseStillValid)
            control.progress(lease, JudgeStatus.JUDGING)
            val caseResults = lease.testCases.sortedBy(JudgeCaseLease::ordinal).map { testCase ->
                ensureLease(leaseStillValid)
                judgeCase(lease, program, testCase)
            }
            val score = if (lease.executionMode == JudgeExecutionMode.RUN) 0 else caseResults.sumOf(JudgeCaseResult::score)
            JudgeCompletion(
                attemptId = lease.attemptId,
                leaseToken = lease.leaseToken,
                status = aggregateStatus(lease.executionMode, score, caseResults),
                score = score,
                testCases = caseResults,
            )
        } catch (failure: CompilationFailure) {
            JudgeCompletion(
                attemptId = lease.attemptId,
                leaseToken = lease.leaseToken,
                status = JudgeStatus.CE,
                score = 0,
                compileMessage = failure.compilerMessage,
            )
        } catch (failure: JudgeInfrastructureFailure) {
            systemFailure(lease, failure.message)
        } catch (failure: RemoteCallException) {
            systemFailure(lease, failure.message)
        }
    }

    /** 形成基础设施异常结算；详细原因只写 Worker 日志，不冒充编译结果返回给用户。 */
    private fun systemFailure(lease: JudgeLease, message: String): JudgeCompletion {
        logger.error("判题基础设施失败，jobId={}，原因={}", lease.jobId, message)
        return JudgeCompletion(
            attemptId = lease.attemptId,
            leaseToken = lease.leaseToken,
            status = JudgeStatus.SYSTEM_ERROR,
            score = 0,
            systemMessage = message.take(SYSTEM_MESSAGE_LIMIT),
        )
    }

    /** 按语言编译源代码并把产物留在 go-judge 缓存。 */
    private fun compile(lease: JudgeLease): CompiledProgram {
        control.progress(lease, JudgeStatus.COMPILING)
        val specification = languageSpecification(lease.language)
        val result = goJudge.run(
            GoJudgeCommand(
                args = specification.compileArgs,
                env = SAFE_ENVIRONMENT,
                files = outputFiles(COMPILER_OUTPUT_LIMIT),
                cpuLimit = COMPILE_CPU_SECONDS * SECOND_NS,
                clockLimit = COMPILE_CLOCK_SECONDS * SECOND_NS,
                memoryLimit = COMPILE_MEMORY_MIB * MIB,
                stackLimit = COMPILE_STACK_MIB * MIB,
                procLimit = COMPILE_PROCESS_LIMIT,
                copyIn = mapOf(specification.sourceName to GoJudgeFile(content = lease.sourceCode)),
                copyOut = listOf(STDOUT, STDERR),
                copyOutCached = listOf(specification.artifactName),
                copyOutMax = COMPILER_OUTPUT_LIMIT * 2 + MIB,
                strictMemoryLimit = true,
                addressSpaceLimit = false,
            ),
        )
        if (result.status != ACCEPTED) {
            if (isInfrastructureStatus(result.status)) {
                throw JudgeInfrastructureFailure("编译沙箱异常：" + result.status)
            }
            val compilerMessage = (result.files[STDERR].orEmpty() + result.files[STDOUT].orEmpty())
                .ifBlank { result.error ?: "编译失败" }
                .take(COMPILER_MESSAGE_LIMIT)
            throw CompilationFailure(compilerMessage)
        }
        val fileId = result.fileIds[specification.artifactName]
            ?: throw JudgeInfrastructureFailure("编译成功但未返回缓存制品")
        return CompiledProgram(
            fileName = specification.artifactName,
            fileId = fileId,
            runArgs = specification.runArgs,
            processLimit = specification.processLimit,
            addressSpaceLimit = specification.addressSpaceLimit,
        )
    }

    /** 执行单个测试点并只返回脱敏资源信息。 */
    private fun judgeCase(
        lease: JudgeLease,
        program: CompiledProgram,
        testCase: JudgeCaseLease,
    ): JudgeCaseResult {
        val input = if (lease.executionMode == JudgeExecutionMode.RUN) {
            testCase.inlineInput?.toByteArray(StandardCharsets.UTF_8)
                ?: throw JudgeInfrastructureFailure("公开运行输入缺失")
        } else {
            downloadAndVerify(
                testCase.inputUrl ?: throw JudgeInfrastructureFailure("隐藏输入地址缺失"),
                testCase.inputSha256 ?: throw JudgeInfrastructureFailure("隐藏输入哈希缺失"),
            )
        }
        val expected = if (lease.executionMode == JudgeExecutionMode.SUBMIT) {
            downloadAndVerify(
                testCase.expectedOutputUrl ?: throw JudgeInfrastructureFailure("标准输出地址缺失"),
                testCase.expectedOutputSha256 ?: throw JudgeInfrastructureFailure("标准输出哈希缺失"),
            )
        } else {
            null
        }
        val result = goJudge.run(
            GoJudgeCommand(
                args = program.runArgs,
                env = SAFE_ENVIRONMENT,
                files = listOf(
                    GoJudgeFile(content = input.toString(StandardCharsets.UTF_8)),
                    GoJudgeFile(name = STDOUT, max = USER_OUTPUT_LIMIT),
                    GoJudgeFile(name = STDERR, max = RUNTIME_ERROR_LIMIT),
                ),
                cpuLimit = lease.timeLimitMs * MILLISECOND_NS,
                clockLimit = (lease.timeLimitMs * CLOCK_MULTIPLIER + CLOCK_GRACE_MS) * MILLISECOND_NS,
                memoryLimit = lease.memoryLimitMiB * MIB,
                stackLimit = minOf(lease.memoryLimitMiB, DEFAULT_STACK_MIB) * MIB,
                procLimit = program.processLimit,
                copyIn = mapOf(program.fileName to GoJudgeFile(fileId = program.fileId)),
                copyOut = listOf(STDOUT, STDERR),
                copyOutMax = USER_OUTPUT_LIMIT + RUNTIME_ERROR_LIMIT,
                strictMemoryLimit = true,
                addressSpaceLimit = program.addressSpaceLimit,
            ),
        )
        val status = mapRunStatus(result, expected)
        return JudgeCaseResult(
            caseId = testCase.caseId,
            status = status,
            score = if (status == JudgeStatus.AC) testCase.score else 0,
            timeMs = result.time.coerceAtLeast(0) / MILLISECOND_NS,
            memoryKiB = result.memory.coerceAtLeast(0) / KIB,
            message = publicMessage(status),
            actualOutput = if (lease.executionMode == JudgeExecutionMode.RUN) result.files[STDOUT].orEmpty() else null,
        )
    }

    /** 将沙箱状态和默认文本比较规则映射为统一状态。 */
    private fun mapRunStatus(result: GoJudgeResult, expected: ByteArray?): JudgeStatus = when (result.status) {
        ACCEPTED -> {
            val actual = result.files[STDOUT].orEmpty()
            if (expected == null || OutputComparator.matches(expected.toString(StandardCharsets.UTF_8), actual)) {
                JudgeStatus.AC
            } else {
                JudgeStatus.WA
            }
        }
        "Time Limit Exceeded" -> JudgeStatus.TLE
        "Memory Limit Exceeded" -> JudgeStatus.MLE
        "Output Limit Exceeded" -> JudgeStatus.OLE
        "Nonzero Exit Status", "Signalled", "Dangerous Syscall" -> JudgeStatus.RE
        else -> throw JudgeInfrastructureFailure("运行沙箱异常：" + result.status)
    }

    /** 汇总固定一百分测试点的提交最终状态。 */
    private fun aggregateStatus(
        executionMode: JudgeExecutionMode,
        score: Int,
        results: List<JudgeCaseResult>,
    ): JudgeStatus = when {
        executionMode == JudgeExecutionMode.RUN && results.all { it.status == JudgeStatus.AC } -> JudgeStatus.AC
        executionMode == JudgeExecutionMode.RUN -> results.first { it.status != JudgeStatus.AC }.status
        score == 100 -> JudgeStatus.AC
        score > 0 -> JudgeStatus.PARTIAL
        else -> results.firstOrNull { it.status != JudgeStatus.AC }?.status ?: JudgeStatus.WA
    }

    /** 下载租约制品并在进入沙箱前校验 SHA-256。 */
    private fun downloadAndVerify(url: String, expectedSha256: String): ByteArray {
        val bytes = control.download(url)
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw JudgeInfrastructureFailure("判题制品哈希校验失败")
        }
        return bytes
    }

    /** 租约失效后停止继续下载和执行测试点。 */
    private fun ensureLease(leaseStillValid: () -> Boolean) {
        if (!leaseStillValid()) throw JudgeInfrastructureFailure("任务租约已失效")
    }

    /** 返回语言工具链和缓存产物配置。 */
    private fun languageSpecification(language: JudgeLanguage): LanguageSpecification = when (language) {
        JudgeLanguage.C17 -> LanguageSpecification(
            sourceName = "main.c",
            artifactName = "main",
            compileArgs = listOf("/usr/bin/gcc", "main.c", "-std=gnu17", "-O2", "-pipe", "-o", "main"),
            runArgs = listOf("./main"),
            processLimit = 1,
            addressSpaceLimit = true,
        )
        JudgeLanguage.CPP17 -> LanguageSpecification(
            sourceName = "main.cpp",
            artifactName = "main",
            compileArgs = listOf("/usr/bin/g++", "main.cpp", "-std=gnu++17", "-O2", "-pipe", "-o", "main"),
            runArgs = listOf("./main"),
            processLimit = 1,
            addressSpaceLimit = true,
        )
        JudgeLanguage.JAVA21 -> LanguageSpecification(
            sourceName = "Main.java",
            artifactName = "app.jar",
            compileArgs = listOf(
                "/bin/sh",
                "-c",
                "/usr/bin/javac -encoding UTF-8 Main.java && /usr/bin/jar --create --file app.jar *.class",
            ),
            runArgs = listOf("/usr/bin/java", "-Dfile.encoding=UTF-8", "-cp", "app.jar", "Main"),
            processLimit = 64,
            addressSpaceLimit = false,
        )
        JudgeLanguage.PYTHON3 -> LanguageSpecification(
            sourceName = "main.py",
            artifactName = "main.py",
            compileArgs = listOf("/usr/bin/python3", "-m", "py_compile", "main.py"),
            runArgs = listOf("/usr/bin/python3", "main.py"),
            processLimit = 1,
            addressSpaceLimit = true,
        )
    }

    /** 编译器输出文件收集器。 */
    private fun outputFiles(limit: Long): List<GoJudgeFile> = listOf(
        GoJudgeFile(content = ""),
        GoJudgeFile(name = STDOUT, max = limit),
        GoJudgeFile(name = STDERR, max = limit),
    )

    /** 判断 go-judge 自身而非用户程序是否失败。 */
    private fun isInfrastructureStatus(status: String): Boolean =
        status in setOf("Internal Error", "File Error")

    /** 返回不含隐藏输入和实际输出的用户可见测点说明。 */
    private fun publicMessage(status: JudgeStatus): String? = when (status) {
        JudgeStatus.AC -> null
        JudgeStatus.WA -> "答案错误"
        JudgeStatus.TLE -> "超过时间限制"
        JudgeStatus.MLE -> "超过内存限制"
        JudgeStatus.RE -> "运行时错误"
        JudgeStatus.OLE -> "超过输出限制"
        else -> null
    }

    /** 单语言的工具链配置。 */
    private data class LanguageSpecification(
        /** 源文件名。 */
        val sourceName: String,
        /** 编译后缓存制品名。 */
        val artifactName: String,
        /** 编译命令。 */
        val compileArgs: List<String>,
        /** 执行命令。 */
        val runArgs: List<String>,
        /** 运行时进程数量限制。 */
        val processLimit: Int,
        /** 是否限制地址空间。 */
        val addressSpaceLimit: Boolean,
    )

    private companion object {
        /** 判题基础设施日志。 */
        val logger = LoggerFactory.getLogger(JudgeEngine::class.java)

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
        const val MIB: Long = 1024L * 1024L

        /** 编译 CPU 时间上限。 */
        const val COMPILE_CPU_SECONDS: Long = 10

        /** 编译墙钟时间上限。 */
        const val COMPILE_CLOCK_SECONDS: Long = 20

        /** 编译内存上限。 */
        const val COMPILE_MEMORY_MIB: Long = 512

        /** 编译栈空间上限。 */
        const val COMPILE_STACK_MIB: Long = 128

        /** 编译器进程和线程上限。 */
        const val COMPILE_PROCESS_LIMIT: Int = 64

        /** 单个编译输出流上限。 */
        const val COMPILER_OUTPUT_LIMIT: Long = 64L * 1024L

        /** 返回用户的编译信息字符上限。 */
        const val COMPILER_MESSAGE_LIMIT: Int = 16_384

        /** 单测试点用户输出上限。 */
        const val USER_OUTPUT_LIMIT: Long = 16L * MIB

        /** 单测试点标准错误收集上限。 */
        const val RUNTIME_ERROR_LIMIT: Long = 64L * 1024L

        /** 墙钟时间相对 CPU 时间的倍率。 */
        const val CLOCK_MULTIPLIER: Long = 3

        /** 墙钟启动宽限，单位毫秒。 */
        const val CLOCK_GRACE_MS: Long = 1_000

        /** 默认最大栈空间。 */
        const val DEFAULT_STACK_MIB: Long = 64

        /** 基础设施审计摘要字符上限。 */
        const val SYSTEM_MESSAGE_LIMIT: Int = 1_000

        /** 用户程序使用的固定环境变量。 */
        val SAFE_ENVIRONMENT: List<String> = listOf(
            "PATH=/usr/bin:/bin",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "HOME=/tmp",
            "PYTHONHASHSEED=0",
        )
    }
}
