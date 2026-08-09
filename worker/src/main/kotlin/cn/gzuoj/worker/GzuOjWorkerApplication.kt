package cn.gzuoj.worker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.nio.file.Path

/** GZU OJ 独立判题 Worker 应用入口。 */
@SpringBootApplication
@ConfigurationPropertiesScan
class GzuOjWorkerApplication

/** Worker 与控制端、沙箱之间的运行参数。 */
@ConfigurationProperties("gzu-oj.worker")
data class WorkerProperties(
    /** 控制端 API 根地址。 */
    val controlBaseUrl: String,
    /** 控制端签发的高强度 Worker Bearer Token。 */
    val controlToken: String,
    /** go-judge 内部 REST 根地址。 */
    val goJudgeBaseUrl: String,
    /** go-judge 的独立 Bearer Token。 */
    val goJudgeToken: String,
    /** 可同时处理的完整提交数。 */
    val slots: Int = 4,
    /** 与普通提交隔离、可同时处理的完整 AI 测试生成任务数。 */
    val aiSlots: Int = 2,
    /** 租约续期周期，单位秒。 */
    val renewSeconds: Long = 20,
    /** 单次 HTTP 请求超时，单位秒。 */
    val requestTimeoutSeconds: Long = 90,
    /** cgroup v2 控制器文件所在目录。 */
    val cgroupRoot: Path = Path.of("/sys/fs/cgroup"),
    /** 部署是否强制 go-judge 使用 no-fallback。 */
    val requireNoFallback: Boolean = true,
)

/** 启动判题 Worker。 */
fun main(args: Array<String>) {
    runApplication<GzuOjWorkerApplication>(*args)
}
