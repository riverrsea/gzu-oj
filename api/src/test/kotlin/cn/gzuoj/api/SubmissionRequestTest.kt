package cn.gzuoj.api

import cn.gzuoj.shared.JudgeLanguage
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** 提交和公开运行请求上下文的幂等边界测试。 */
class SubmissionRequestTest {
    /** 相同代码在不同个人计时作答中不能复用同一份运行幂等记录。 */
    @Test
    fun `run hash includes timed attempt id`() {
        val base = CreateRunRequest(
            problemId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
            problemVersionId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
            language = JudgeLanguage.CPP17,
            sourceCode = "int main() { return 0; }",
            inputs = listOf("1 2\n"),
            expectedOutputs = listOf("3\n"),
            timedPaperAttemptId = UUID.fromString("33333333-3333-4333-8333-333333333333"),
        )

        val anotherAttempt = base.copy(
            timedPaperAttemptId = UUID.fromString("44444444-4444-4444-8444-444444444444"),
        )

        assertNotEquals(runRequestHash(base), runRequestHash(anotherAttempt))
    }
}
