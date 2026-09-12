package cn.gzuoj.worker

import cn.gzuoj.shared.AiCompileUnit
import cn.gzuoj.shared.AiCompileTask
import cn.gzuoj.shared.AiSandboxFailureStage
import cn.gzuoj.shared.AiSandboxLease
import cn.gzuoj.shared.AiSandboxTaskPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** AI 沙箱租约的跨进程 JSON 契约测试：控制端省略空字段时 Worker 仍必须能解析。 */
class AiSandboxLeaseJsonTest {
    /** 使用 Worker 生产环境的同一个 Jackson 配置，避免测试与运行期解析行为分叉。 */
    private val mapper = JsonSupport().objectMapper()

    /** 编译门禁租约不带差分参数；控制端省略 task 时不得要求该字段存在。 */
    @Test
    fun parsesCompileLeaseWithoutTaskProperty() {
        val json = leaseJson(
            """
            "compile": {
              "stage": "TEST_DATA",
              "units": [
                {"label": "测试生成器", "source": "int main(){}"},
                {"label": "输入校验器", "source": "int main(){}"}
              ]
            }
            """.trimIndent(),
        )

        val lease = mapper.readValue(json, AiSandboxLease::class.java)

        assertNull(lease.task)
        assertNotNull(lease.compile)
        assertEquals(AiSandboxFailureStage.TEST_DATA, lease.compile?.stage)
        assertEquals(2, lease.compile?.units?.size)
    }

    /** 显式 null 与省略字段都必须被当成"没有该类型任务"，不能抛非空异常。 */
    @Test
    fun parsesCompileLeaseWithExplicitNullTask() {
        val json = leaseJson(
            """
            "task": null,
            "compile": {"stage": "SOLUTIONS", "units": [{"label": "标程 A", "source": "int main(){}"}]}
            """.trimIndent(),
        )

        val lease = mapper.readValue(json, AiSandboxLease::class.java)

        assertNull(lease.task)
        assertEquals(AiSandboxFailureStage.SOLUTIONS, lease.compile?.stage)
    }

    /** 差分租约不带编译门禁参数；控制端省略 compile 时同样必须能解析。 */
    @Test
    fun parsesDifferentialLeaseWithoutCompileProperty() {
        val json = leaseJson(
            """
            "task": {
              "solutionASource": "int main(){}",
              "solutionBSource": "int main(){}",
              "bruteForceSource": "int main(){}",
              "generatorSource": "int main(){}",
              "validatorSource": "int main(){}",
              "seeds": [1, 2],
              "bruteForceCaseCount": 2,
              "timeLimitMs": 1000,
              "memoryLimitMiB": 256
            }
            """.trimIndent(),
        )

        val lease = mapper.readValue(json, AiSandboxLease::class.java)

        assertNull(lease.compile)
        assertNotNull(lease.task)
        assertEquals(listOf(1L, 2L), lease.task?.seeds)
        assertEquals("int main(){}", lease.requireTask().solutionASource)
    }

    /** 两种租约都必须能由 Worker 侧配置完整序列化并原样解析回来。 */
    @Test
    fun roundTripsBothLeaseShapes() {
        val compileLease = lease(compile = compileTask())
        val differentialLease = lease(task = taskPayload())

        val compileBack = mapper.readValue(mapper.writeValueAsString(compileLease), AiSandboxLease::class.java)
        val differentialBack = mapper.readValue(
            mapper.writeValueAsString(differentialLease),
            AiSandboxLease::class.java,
        )

        assertEquals("测试生成器", compileBack.compile?.units?.first()?.label)
        assertEquals(AiSandboxFailureStage.TEST_DATA, compileBack.compile?.stage)
        assertNull(compileBack.task)
        assertNull(differentialBack.compile)
        assertEquals("int main(){}", differentialBack.task?.generatorSource)
        assertEquals(listOf(1L), differentialBack.task?.seeds)
        // 编译租约调用 requireTask() 必须给出明确错误，而不是静默当成差分任务。
        val failure = runCatching { compileBack.requireTask() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    /** 拼接一份包含公共租约字段的 JSON，空字段由调用方决定是否出现。 */
    private fun leaseJson(fields: String) = """
        {
          "jobId": "${UUID.randomUUID()}",
          "runId": "${UUID.randomUUID()}",
          "problemVersionId": "${UUID.randomUUID()}",
          "attemptId": "${UUID.randomUUID()}",
          "leaseToken": "token",
          "leaseExpiresAt": "2026-09-12T02:21:54Z",
          $fields
        }
    """.trimIndent()

    /** 构造一份固定标识的租约。 */
    private fun lease(
        task: AiSandboxTaskPayload? = null,
        compile: AiCompileTask? = null,
    ) = AiSandboxLease(
        jobId = UUID.randomUUID(),
        runId = UUID.randomUUID(),
        problemVersionId = UUID.randomUUID(),
        attemptId = UUID.randomUUID(),
        leaseToken = "token",
        leaseExpiresAt = Instant.parse("2026-09-12T02:21:54Z"),
        task = task,
        compile = compile,
    )

    /** 构造编译门禁参数。 */
    private fun compileTask() = AiCompileTask(
        stage = AiSandboxFailureStage.TEST_DATA,
        units = listOf(AiCompileUnit(label = "测试生成器", source = "int main(){}")),
    )

    /** 构造差分参数。 */
    private fun taskPayload() = AiSandboxTaskPayload(
        solutionASource = "int main(){}",
        solutionBSource = "int main(){}",
        bruteForceSource = "int main(){}",
        generatorSource = "int main(){}",
        validatorSource = "int main(){}",
        seeds = listOf(1L),
        bruteForceCaseCount = 1,
        timeLimitMs = 1_000,
        memoryLimitMiB = 256,
    )
}
