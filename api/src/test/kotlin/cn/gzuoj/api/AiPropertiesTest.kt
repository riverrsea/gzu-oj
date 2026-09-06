package cn.gzuoj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment

/** 验证独立 Python Agent 连接配置可以从环境变量绑定。 */
class AiPropertiesTest {
    @ParameterizedTest
    @CsvSource("http://agent:8090,secret-token")
    fun `binds agent endpoint`(url: String, token: String) {
        val environment = MockEnvironment()
            .withProperty("gzu-oj.ai.agent-base-url", url)
            .withProperty("gzu-oj.ai.agent-internal-token", token)

        val properties = Binder.get(environment)
            .bind("gzu-oj.ai", Bindable.of(AiProperties::class.java))
            .orElseThrow { IllegalStateException("AI 服务商配置绑定失败") }

        assertEquals(url, properties.agentBaseUrl)
        assertEquals(token, properties.agentInternalToken)
    }
}
