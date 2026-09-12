package cn.gzuoj.api

import cn.gzuoj.shared.AiCompileTask
import cn.gzuoj.shared.AiCompileUnit
import cn.gzuoj.shared.AiGeneratedCaseResult
import cn.gzuoj.shared.AiSandboxCompletion
import cn.gzuoj.shared.AiSandboxCompletionStatus
import cn.gzuoj.shared.AiSandboxFailureStage
import cn.gzuoj.shared.AiSandboxTaskPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/** AI 测试点结算协议的安全边界测试。 */
class AiSandboxCompletionVerifierTest {
    /** 正常结果必须按固定种子顺序通过，并保留资源百分比。 */
    @Test
    fun acceptsCompleteDeterministicResult() {
        val task = task(listOf(11L, 22L))
        val completion = completion(task)

        val verified = AiSandboxCompletionVerifier.verify(task, completion)

        assertEquals(2, verified.testCases.size)
        assertEquals(10, verified.maximumTimePercent)
        assertEquals(1, verified.maximumMemoryPercent)
    }

    /** 少一个测试点时不能绕过管理员要求的生成数量。 */
    @Test
    fun rejectsMissingRequestedCase() {
        val task = task(listOf(11L, 22L))
        val completion = completion(task).copy(testCases = completion(task).testCases.dropLast(1))

        assertThrows(ApiException::class.java) {
            AiSandboxCompletionVerifier.verify(task, completion)
        }
    }

    /** Worker 伪造输入或标准输出哈希时必须拒绝结算。 */
    @Test
    fun rejectsMismatchedHash() {
        val task = task(listOf(11L))
        val completion = completion(task).copy(
            testCases = completion(task).testCases.map { it.copy(outputSha256 = "0".repeat(64)) },
        )

        assertThrows(ApiException::class.java) {
            AiSandboxCompletionVerifier.verify(task, completion)
        }
    }

    /** 通过结算不能携带失败归属，否则管理员会看到矛盾的定向修复提示。 */
    @Test
    fun rejectsFailureStageOnPassedCompletion() {
        val task = task(listOf(11L))
        val completion = completion(task).copy(failedStage = AiSandboxFailureStage.SOLUTIONS)

        assertThrows(ApiException::class.java) {
            AiSandboxCompletionVerifier.verify(task, completion)
        }
    }

    /** 编译门禁通过时不产出测试点，也不能携带失败归属。 */
    @Test
    fun acceptsCompileGateWithoutFailureStage() {
        val compile = compileTask()

        AiSandboxCompletionVerifier.verifyCompile(
            compile,
            AiSandboxCompletion(attemptId = UUID.randomUUID(), leaseToken = "lease", status = AiSandboxCompletionStatus.PASSED),
        )
    }

    /** 编译失败必须携带原因和本阶段归属，避免 Agent 重生成错误产物。 */
    @Test
    fun rejectsCompileFailureWithWrongStage() {
        val compile = compileTask()
        val mismatched = AiSandboxCompletion(
            attemptId = UUID.randomUUID(),
            leaseToken = "lease",
            status = AiSandboxCompletionStatus.VALIDATION_FAILED,
            failureReason = "标程 A 编译失败",
            failedStage = AiSandboxFailureStage.BRUTE_FORCE,
        )

        assertThrows(ApiException::class.java) {
            AiSandboxCompletionVerifier.verifyCompile(compile, mismatched)
        }
    }

    /** 编译门禁不能伪造测试点，也不能空手报告失败。 */
    @Test
    fun rejectsCompileCompletionWithoutEvidence() {
        val compile = compileTask()
        val withCases = AiSandboxCompletion(
            attemptId = UUID.randomUUID(),
            leaseToken = "lease",
            status = AiSandboxCompletionStatus.PASSED,
            testCases = completion(task(listOf(11L))).testCases,
        )
        val withoutReason = AiSandboxCompletion(
            attemptId = UUID.randomUUID(),
            leaseToken = "lease",
            status = AiSandboxCompletionStatus.VALIDATION_FAILED,
            failedStage = AiSandboxFailureStage.TEST_DATA,
        )

        assertThrows(ApiException::class.java) {
            AiSandboxCompletionVerifier.verifyCompile(compile, withCases)
        }
        assertThrows(ApiException::class.java) {
            AiSandboxCompletionVerifier.verifyCompile(compile, withoutReason)
        }
    }

    /** 基础设施异常必须被接受且不能伪装成某个产物的编译失败。 */
    @Test
    fun acceptsCompileGateSystemErrorWithoutStage() {
        val compile = compileTask()
        AiSandboxCompletionVerifier.verifyCompile(
            compile,
            AiSandboxCompletion(
                attemptId = UUID.randomUUID(),
                leaseToken = "lease",
                status = AiSandboxCompletionStatus.SYSTEM_ERROR,
                failureReason = "go-judge 不可用",
            ),
        )
        assertThrows(ApiException::class.java) {
            AiSandboxCompletionVerifier.verifyCompile(
                compile,
                AiSandboxCompletion(
                    attemptId = UUID.randomUUID(),
                    leaseToken = "lease",
                    status = AiSandboxCompletionStatus.SYSTEM_ERROR,
                    failureReason = "go-judge 不可用",
                    failedStage = AiSandboxFailureStage.TEST_DATA,
                ),
            )
        }
    }

    /** 构造测试使用的编译门禁参数。 */
    private fun compileTask() = AiCompileTask(
        stage = AiSandboxFailureStage.TEST_DATA,
        units = listOf(
            AiCompileUnit(label = "测试生成器", source = "int main(){}"),
            AiCompileUnit(label = "输入校验器", source = "int main(){}"),
        ),
    )

    /** 构造测试使用的固定沙箱参数。 */
    private fun task(seeds: List<Long>) = AiSandboxTaskPayload(
        solutionASource = "int main(){}",
        solutionBSource = "int main(){}",
        bruteForceSource = "int main(){}",
        generatorSource = "int main(){}",
        validatorSource = "int main(){}",
        seeds = seeds,
        bruteForceCaseCount = minOf(1, seeds.size),
        timeLimitMs = 1_000,
        memoryLimitMiB = 256,
    )

    /** 生成与哈希、资源证据一致的通过结果。 */
    private fun completion(task: AiSandboxTaskPayload): AiSandboxCompletion {
        val cases = task.seeds.mapIndexed { index, seed ->
            val input = "input-$seed\n"
            val output = "output-$seed\n"
            val inputHash = SecureValues.sha256(input)
            val outputHash = SecureValues.sha256(output)
            AiGeneratedCaseResult(
                ordinal = index + 1,
                seed = seed,
                input = input,
                expectedOutput = output,
                inputSha256 = inputHash,
                outputSha256 = outputHash,
                solutionAOutputSha256 = outputHash,
                solutionBOutputSha256 = outputHash,
                bruteForceOutputSha256 = if (index < task.bruteForceCaseCount) outputHash else null,
                solutionATimeMs = 100,
                solutionAMemoryKiB = 512,
                solutionBTimeMs = 100,
                solutionBMemoryKiB = 512,
            )
        }
        return AiSandboxCompletion(
            attemptId = UUID.randomUUID(),
            leaseToken = "lease",
            status = AiSandboxCompletionStatus.PASSED,
            testCases = cases,
            deterministic = true,
            solutionsAgree = true,
            bruteForcePassed = true,
            maximumTimePercent = 10,
            maximumMemoryPercent = 1,
        )
    }
}
