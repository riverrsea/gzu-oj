package cn.gzuoj.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.module.kotlin.KotlinModule;

/** Worker 使用的 Jackson 3 JSON 配置。 */
@Configuration
public class JsonSupport {
    /** 注册 Kotlin 模块，确保 shared 中的不可变 Kotlin 数据类能够序列化和反序列化。 */
    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
            .addModule(new KotlinModule.Builder().build())
            .build();
    }
}
