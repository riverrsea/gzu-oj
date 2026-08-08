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

/** Spring AI 可选择的聊天模型服务商。 */
enum class AiChatProvider {
    /** 不创建聊天模型客户端。 */
    NONE,
    /** OpenAI 原生服务或 OpenAI 兼容网关。 */
    OPENAI,
    /** DeepSeek 原生 Spring AI Starter。 */
    DEEPSEEK,
}

/** 站点统一的 Spring AI 配置。 */
data class AiProperties(
    /** 是否允许自动调用模型。 */
    val enabled: Boolean = false,
    /** 当前启用的 Spring AI 聊天模型服务商。 */
    val provider: AiChatProvider = AiChatProvider.NONE,
    /** 当前 Spring AI 服务商的服务地址。 */
    val baseUrl: String = "http://127.0.0.1:11434/v1",
    /** 只从环境变量读取的 API 密钥。 */
    val apiKey: String = "",
    /** 默认模型名称。 */
    val model: String = "gpt-4.1-mini",
    /** 提示词版本，变更后用于审计。 */
    val promptVersion: String = "v1",
    /** 单次录题运行费用上限，单位微美元。 */
    val maxCostMicrounits: Long = 1_000_000,
    /** API 内协调器并发上限。 */
    val maxConcurrent: Int = 1,
    /** 模型调用超时秒数。 */
    val timeoutSeconds: Long = 120,
)
