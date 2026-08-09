package cn.gzuoj.api

import cn.gzuoj.shared.AiGeneratedCaseResult
import cn.gzuoj.shared.AiSandboxCompletion
import cn.gzuoj.shared.AiSandboxCompletionStatus
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
