package cn.gzuoj.worker;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** go-judge 命令输入、输出或复制文件定义。 */
record GoJudgeFile(String content, String fileId, String name, Long max) {}

/** go-judge 单条命令及其资源边界。 */
record GoJudgeCommand(
    List<String> args,
    List<String> env,
    List<GoJudgeFile> files,
    long cpuLimit,
    long clockLimit,
    long memoryLimit,
    Long stackLimit,
    int procLimit,
    Map<String, GoJudgeFile> copyIn,
    List<String> copyOut,
    List<String> copyOutCached,
    long copyOutMax,
    boolean copyOutTruncate,
    boolean strictMemoryLimit,
    boolean addressSpaceLimit
) {
    GoJudgeCommand {
        env = env == null ? List.of() : env;
        files = files == null ? List.of() : files;
        copyIn = copyIn == null ? Map.of() : copyIn;
        copyOut = copyOut == null ? List.of() : copyOut;
        copyOutCached = copyOutCached == null ? List.of() : copyOutCached;
    }
}

/** go-judge 批量命令请求。 */
record GoJudgeRequest(List<GoJudgeCommand> cmd) {}

/** go-judge 单条命令执行结果。 */
record GoJudgeResult(
    String status,
    int exitStatus,
    String error,
    long time,
    long runTime,
    long memory,
    Map<String, String> files,
    Map<String, String> fileIds
) {
    GoJudgeResult {
        files = files == null ? Map.of() : files;
        fileIds = fileIds == null ? Map.of() : fileIds;
    }
}

/** go-judge 内部 REST 客户端。 */
@Component
public class GoJudgeClient {
    private final WorkerProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public GoJudgeClient(WorkerProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 读取 go-judge 配置和运行能力。 */
    public JsonNode config() {
        HttpRequest request = HttpRequest.newBuilder(resolve("/config"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() != 200) {
            throw new RemoteCallException("go-judge-config", response.statusCode(), "go-judge 配置探测失败");
        }
        return mapper.readTree(response.body());
    }

    /** 在沙箱内执行一条命令。 */
    public GoJudgeResult run(GoJudgeCommand command) {
        if (properties.goJudgeToken().length() < 40) {
            throw new IllegalStateException("GZU_OJ_GO_JUDGE_TOKEN 未配置或强度不足");
        }
        HttpRequest request = HttpRequest.newBuilder(resolve("/run"))
            .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
            .header("Authorization", "Bearer " + properties.goJudgeToken())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(new GoJudgeRequest(List.of(command)))))
            .build();
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() != 200) {
            throw new RemoteCallException("go-judge-run", response.statusCode(), "go-judge 执行请求失败");
        }
        List<GoJudgeResult> results = mapper.readerForListOf(GoJudgeResult.class).readValue(response.body());
        if (results.size() != 1) {
            throw new RemoteCallException("go-judge-run", response.statusCode(), "go-judge 返回结果数量异常");
        }
        return results.get(0);
    }

    /** 发送内部 HTTP 请求并统一屏蔽网络异常细节。 */
    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "go-judge 网络错误" : exception.getMessage();
            throw new RemoteCallException("go-judge", null, message);
        }
    }

    private URI resolve(String path) {
        return URI.create(properties.goJudgeBaseUrl().replaceFirst("/+$", "") + path);
    }
}
