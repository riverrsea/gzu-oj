package cn.gzuoj.api

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/** 应用级可调参数。 */
@ConfigurationProperties("gzu-oj")
data class AppProperties(
    /** 浏览器访问站点、生成邮件链接使用的控制端公开根地址。 */
    val publicBaseUrl: String,
    /**
     * API 向 Worker 签发隐藏测试制品下载链接时使用的根地址。
     *
     * 公网部署通常与 [publicBaseUrl] 相同；宿主机运行 API、Docker 运行 Worker 时，
     * 可配置为容器可访问的宿主机地址，避免把开发拓扑写死到 Worker 程序中。
     */
    val workerArtifactBaseUrl: String = publicBaseUrl,
    /** 本地制品存储根目录。 */
    val artifactRoot: Path,
    /** 是否开放用户注册。 */
    val registrationEnabled: Boolean = true,
    /** 是否实际投递验证邮件；关闭时仅写入日志。 */
    val mailDeliveryEnabled: Boolean = false,
    /** 邮件 From 头使用的发件人地址；为空时由邮件服务器决定。 */
    val mailFrom: String = "",
    /** Worker 租约参数。 */
    val worker: WorkerProperties = WorkerProperties(),
    /** 公开训练赛限制。 */
    val contest: ContestProperties = ContestProperties(),
    /** 同一用户运行或提交之间的服务端冷却时间，单位毫秒。设为 0 可关闭短窗口限制。 */
    val submissionCooldownMs: Long = 1_000,
    /** AI 供应商、模型和费用门禁。 */
    val ai: AiProperties = AiProperties(),
)

/** Worker 长轮询、续租和过期参数。 */
data class WorkerProperties(
    /** 领取接口最长等待秒数。 */
    val longPollSeconds: Long = 15,
    /** 单次任务租约秒数。 */
    val leaseSeconds: Long = 60,
    /** Worker 推荐心跳间隔秒数。 */
    val heartbeatSeconds: Long = 20,
)

/** 用户创建公开训练赛的限制参数。 */
data class ContestProperties(
    /** 单场默认最大参与人数。 */
    val maxParticipants: Int = 5,
    /** 单个用户最多拥有的待开始或进行中比赛数。 */
    val maxActiveOwned: Int = 3,
    /** 两次创建比赛之间的最短分钟数。 */
    val minimumCreateIntervalMinutes: Long = 10,
)

/** Kotlin 到独立 Python Agent 的连接配置。 */
data class AiProperties(
    /** 独立 Python Agent 的 HTTP 地址。 */
    val agentBaseUrl: String = "http://127.0.0.1:8090",
    /** Kotlin 与 Python Agent 间的内部 Bearer Token。 */
    val agentInternalToken: String = "",
    /** Agent 派发和回调 HTTP 超时秒数。 */
    val agentTimeoutSeconds: Long = 30,
)
