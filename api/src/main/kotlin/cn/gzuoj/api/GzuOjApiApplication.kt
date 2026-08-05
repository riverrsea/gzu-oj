package cn.gzuoj.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** GZU OJ 控制端应用入口。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class GzuOjApiApplication

/** 启动 GZU OJ 控制端。 */
fun main(args: Array<String>) {
    runApplication<GzuOjApiApplication>(*args)
}
