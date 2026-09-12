package cn.gzuoj.worker

import cn.gzuoj.shared.AiSandboxCompletion
import cn.gzuoj.shared.AiSandboxLease
import cn.gzuoj.shared.JudgeCompletion
import cn.gzuoj.shared.JudgeLanguage
import cn.gzuoj.shared.JudgeLease
import cn.gzuoj.shared.JudgeStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** HTTP 调用失败且不能作为正常空响应处理。 */
class RemoteCallException(
    /** 调用目标。 */
    val target: String,
    /** HTTP 状态码；网络错误时为空。 */
    val statusCode: Int?,
    /** 对日志安全的错误说明。 */
    override val message: String,
) : RuntimeException(message)

/** 控制端心跳请求。 */
data class ControlHeartbeat(
    /** cgroup v2 CPU 控制器是否通过探针。 */
    val cpuController: Boolean,
    /** cgroup v2 memory 控制器是否通过探针。 */
    val memoryController: Boolean,
    /** cgroup v2 pids 控制器是否通过探针。 */
    val pidsController: Boolean,
    /** go-judge 是否启用 no-fallback。 */
    val noFallback: Boolean,
    /** Worker 支持的语言。 */
    val languages: Set<JudgeLanguage>,
    /** 当前启动的普通提交槽数。 */
    val judgeSlots: Int,
    /** 当前启动的独立 AI 生成和差分槽数。 */
    val aiSlots: Int,
)

/** Worker 进度请求。 */
data class ControlProgress(
    /** 当前任务执行尝试标识。 */
    val attemptId: UUID,
    /** 当前租约令牌。 */
    val leaseToken: String,
    /** 编译中或判题中状态。 */
    val status: JudgeStatus,
)

/** Worker 与控制端之间的 HTTPS 客户端。 */
@Component
class ControlPlaneClient(
    /** Worker 配置。 */
    private val properties: WorkerProperties,
    /** JSON 编解码器。 */
    private val mapper: ObjectMapper,
) {
    /** 复用连接的 JDK HTTP 客户端。 */
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** 上报安全预检和存活状态。 */
    fun heartbeat(heartbeat: ControlHeartbeat) {
        sendJson("/internal/worker/v1/heartbeat", heartbeat, Void::class.java, setOf(200))
    }

    /** 长轮询领取任务；204 表示当前无任务。 */
    fun claim(): JudgeLease? = sendJson(
        "/internal/worker/v1/jobs/claim",
        emptyMap<String, String>(),
        JudgeLease::class.java,
        setOf(200, 204),
    )

    /** AI 槽长轮询领取测试生成和差分任务；204 表示当前无任务。 */
    fun claimAi(): AiSandboxLease? = sendJson(
        "/internal/worker/v1/ai-jobs/claim",
        emptyMap<String, String>(),
        AiSandboxLease::class.java,
        setOf(200, 204),
    )

    /** 上报编译或判题进度。 */
    fun progress(lease: JudgeLease, status: JudgeStatus) {
        sendJson(
            "/internal/worker/v1/jobs/${lease.jobId}/progress",
            ControlProgress(lease.attemptId, lease.leaseToken, status),
            Void::class.java,
            setOf(204),
        )
    }

    /** 延长当前租约。 */
    fun renew(lease: JudgeLease) {
        val token = java.net.URLEncoder.encode(lease.leaseToken, Charsets.UTF_8)
        sendJson(
            "/internal/worker/v1/jobs/${lease.jobId}/renew?attemptId=${lease.attemptId}&leaseToken=$token",
            emptyMap<String, String>(),
            Void::class.java,
            setOf(200),
        )
    }

    /** 延长当前 AI 生成和差分任务租约。 */
    fun renewAi(lease: AiSandboxLease) {
        val token = java.net.URLEncoder.encode(lease.leaseToken, Charsets.UTF_8)
        sendJson(
            "/internal/worker/v1/ai-jobs/${lease.jobId}/renew?attemptId=${lease.attemptId}&leaseToken=$token",
            emptyMap<String, String>(),
            Void::class.java,
            setOf(200),
        )
    }

    /** 幂等结算当前租约。 */
    fun complete(lease: JudgeLease, completion: JudgeCompletion) {
        sendJson(
            "/internal/worker/v1/jobs/${lease.jobId}/complete",
            completion,
            Void::class.java,
            setOf(200),
        )
    }

    /** 幂等结算当前 AI 生成和差分任务。 */
    fun completeAi(lease: AiSandboxLease, completion: AiSandboxCompletion) {
        sendJson(
            "/internal/worker/v1/ai-jobs/${lease.jobId}/complete",
            completion,
            Void::class.java,
            setOf(200),
        )
    }

    /** 下载租约绑定制品，禁止跨域重定向并校验响应状态。 */
    fun download(url: String): ByteArray {
        val uri = URI.create(url)
        val control = URI.create(properties.controlBaseUrl)
        if (!sameOrigin(control, uri)) {
            throw RemoteCallException("control-artifact", null, "控制端返回了跨域制品地址")
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
            .header("Authorization", "Bearer ${properties.controlToken}")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) {
            throw RemoteCallException("control-artifact", response.statusCode(), "下载判题制品失败")
        }
        return response.body()
    }

    /** 发送 JSON POST 并按目标类型解析成功响应。 */
    private fun <T : Any> sendJson(
        path: String,
        body: Any,
        responseType: Class<T>,
        acceptedStatuses: Set<Int>,
    ): T? {
        if (properties.controlToken.length < 40) {
            throw IllegalStateException("GZU_OJ_WORKER_TOKEN 未配置或强度不足")
        }
        val request = HttpRequest.newBuilder(resolve(properties.controlBaseUrl, path))
            .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
            .header("Authorization", "Bearer ${properties.controlToken}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (exception: Exception) {
            throw RemoteCallException("control", null, exception.message ?: "控制端网络错误")
        }
        if (response.statusCode() !in acceptedStatuses) {
            throw RemoteCallException("control", response.statusCode(), "控制端请求失败")
        }
        if (response.statusCode() == 204 || responseType == Void::class.java || response.body().isEmpty()) return null
        val body = response.body()
        return try {
            mapper.readValue(body, responseType)
        } catch (exception: Exception) {
            // 控制端与 Worker 版本不一致时 Jackson 只报字段名，无法定位是哪个响应，必须带上原始报文。
            throw RemoteCallException(
                "control",
                response.statusCode(),
                "解析控制端响应失败（$path）：${exception.message?.take(300)}；原始响应：${String(body).take(500)}",
            )
        }
    }

    /** 解析根地址下的固定路径。 */
    private fun resolve(base: String, path: String): URI = URI.create(base.trimEnd('/') + path)

    /** 判断两个 URI 是否同源。 */
    private fun sameOrigin(left: URI, right: URI): Boolean =
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    /** 返回 URI 的显式或默认端口。 */
    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }
}
