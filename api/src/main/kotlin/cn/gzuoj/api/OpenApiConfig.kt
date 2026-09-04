package cn.gzuoj.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 用户 API、管理员 API 和 Worker API 的 OpenAPI 元数据。 */
@Configuration
class OpenApiConfig {
    /** 创建用于前端类型校验和接口查阅的 OpenAPI 文档。 */
    @Bean
    fun gzuOjOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("GZU_OJ API")
            .description("题库、提交、训练赛、管理员导入和异地判题 Worker 接口")
            .version("v1"),
    )
}
