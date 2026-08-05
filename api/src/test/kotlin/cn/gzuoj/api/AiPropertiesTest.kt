package cn.gzuoj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment

/** 验证环境变量中的聊天服务商名称可以绑定到受支持的 Spring AI 模型。 */
class AiPropertiesTest {
    @ParameterizedTest
    @CsvSource(
        "none,NONE",
        "openai,OPENAI",
        "deepseek,DEEPSEEK",
    )
    fun `binds supported chat provider`(value: String, expected: AiChatProvider) {
        val environment = MockEnvironment().withProperty("gzu-oj.ai.provider", value)

        val properties = Binder.get(environment)
            .bind("gzu-oj.ai", Bindable.of(AiProperties::class.java))
            .orElseThrow { IllegalStateException("AI 服务商配置绑定失败") }

        assertEquals(expected, properties.provider)
    }
}
