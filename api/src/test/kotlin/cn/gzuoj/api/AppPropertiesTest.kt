package cn.gzuoj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** 验证 Worker 制品地址默认沿用站点公开地址，避免公网部署出现隐式 localhost。 */
class AppPropertiesTest {
    @Test
    fun `worker artifact address defaults to public address`() {
        val properties = AppProperties(
            publicBaseUrl = "https://oj.example.com",
            artifactRoot = Path.of("artifacts"),
        )

        assertEquals("https://oj.example.com", properties.workerArtifactBaseUrl)
    }

    @Test
    fun `worker artifact address can differ from browser public address`() {
        val properties = AppProperties(
            publicBaseUrl = "http://localhost:8080",
            workerArtifactBaseUrl = "http://host.docker.internal:8080",
            artifactRoot = Path.of("artifacts"),
        )

        assertEquals("http://host.docker.internal:8080", properties.workerArtifactBaseUrl)
    }
}
