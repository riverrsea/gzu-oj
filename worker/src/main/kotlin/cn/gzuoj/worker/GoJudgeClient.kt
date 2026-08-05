package cn.gzuoj.worker

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** go-judge 命令输入、输出或复制文件定义。 */
data class GoJudgeFile(
    /** 内联文件内容。 */
    val content: String? = null,
    /** go-judge 缓存文件标识。 */
    val fileId: String? = null,
    /** 输出收集器名称。 */
    val name: String? = null,
    /** 输出收集器允许的最大字节数。 */
    val max: Long? = null,
)

/** go-judge 单条命令及其资源边界。 */
data class GoJudgeCommand(
    /** 可执行文件及参数。 */
    val args: List<String>,
    /** 固定且最小化的环境变量。 */
    val env: List<String> = emptyList(),
    /** 标准输入、标准输出和标准错误定义。 */
    val files: List<GoJudgeFile> = emptyList(),
    /** CPU 时间限制，单位纳秒。 */
    val cpuLimit: Long,
    /** 墙钟时间限制，单位纳秒。 */
    val clockLimit: Long,
    /** 内存限制，单位字节。 */
    val memoryLimit: Long,
    /** 栈空间限制，单位字节。 */
    val stackLimit: Long? = null,
    /** 进程和线程数量限制。 */
    val procLimit: Int,
    /** 需要复制进沙箱的文件。 */
    val copyIn: Map<String, GoJudgeFile> = emptyMap(),
    /** 需要直接取回的小文件。 */
    val copyOut: List<String> = emptyList(),
    /** 需要保留在 go-judge 缓存中的文件。 */
    val copyOutCached: List<String> = emptyList(),
    /** 所有取回文件的合计上限。 */
    val copyOutMax: Long,
    /** 超限时是否截断；关闭后由沙箱报告输出超限。 */
    val copyOutTruncate: Boolean = false,
    /** 是否启用严格内存计量。 */
    val strictMemoryLimit: Boolean = true,
    /** 是否同步设置地址空间限制。 */
    val addressSpaceLimit: Boolean = false,
)

/** go-judge 批量命令请求。 */
data class GoJudgeRequest(
    /** 本次顺序执行的命令；判题流程每次只发送一条。 */
    val cmd: List<GoJudgeCommand>,
)

/** go-judge 单条命令执行结果。 */
data class GoJudgeResult(
    /** 沙箱状态文本。 */
    val status: String,
    /** 进程退出码。 */
    val exitStatus: Int = 0,
    /** 沙箱错误摘要。 */
    val error: String? = null,
    /** CPU 时间，单位纳秒。 */
    val time: Long = 0,
    /** 实际运行时间，单位纳秒。 */
    val runTime: Long = 0,
    /** 峰值内存，单位字节。 */
    val memory: Long = 0,
    /** 直接取回的文件内容。 */
    val files: Map<String, String> = emptyMap(),
    /** 缓存文件名到文件标识的映射。 */
    val fileIds: Map<String, String> = emptyMap(),
)

/** go-judge 内部 REST 客户端。 */
@Component
class GoJudgeClient(
    /** Worker 配置。 */
    private val properties: WorkerProperties,
    /** JSON 编解码器。 */
    private val mapper: ObjectMapper,
) {
    /** 复用本机内部网络连接。 */
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** 读取 go-judge 配置和运行能力。 */
    fun config(): JsonNode {
        val request = HttpRequest.newBuilder(resolve("/config"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = send(request)
        if (response.statusCode() != 200) {
            throw RemoteCallException("go-judge-config", response.statusCode(), "go-judge 配置探测失败")
        }
        return mapper.readTree(response.body())
    }

    /** 在沙箱内执行一条命令。 */
    fun run(command: GoJudgeCommand): GoJudgeResult {
        if (properties.goJudgeToken.length < 40) {
            throw IllegalStateException("GZU_OJ_GO_JUDGE_TOKEN 未配置或强度不足")
        }
        val request = HttpRequest.newBuilder(resolve("/run"))
            .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
            .header("Authorization", "Bearer ${properties.goJudgeToken}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(GoJudgeRequest(listOf(command)))))
            .build()
        val response = send(request)
        if (response.statusCode() != 200) {
            throw RemoteCallException("go-judge-run", response.statusCode(), "go-judge 执行请求失败")
        }
        val results = mapper.readerForListOf(GoJudgeResult::class.java)
            .readValue<List<GoJudgeResult>>(response.body())
        return results.singleOrNull()
            ?: throw RemoteCallException("go-judge-run", response.statusCode(), "go-judge 返回结果数量异常")
    }

    /** 发送内部 HTTP 请求并统一屏蔽网络异常细节。 */
    private fun send(request: HttpRequest): HttpResponse<ByteArray> = try {
        http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw exception
    } catch (exception: Exception) {
        throw RemoteCallException("go-judge", null, exception.message ?: "go-judge 网络错误")
    }

    /** 解析 go-judge 固定内部地址。 */
    private fun resolve(path: String): URI = URI.create(properties.goJudgeBaseUrl.trimEnd('/') + path)
}
