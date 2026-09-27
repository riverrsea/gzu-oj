package cn.gzuoj.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** GZU OJ 独立判题 Worker 应用入口。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GzuOjWorkerApplication {
    /** 启动判题 Worker。 */
    public static void main(String[] args) {
        SpringApplication.run(GzuOjWorkerApplication.class, args);
    }
}
