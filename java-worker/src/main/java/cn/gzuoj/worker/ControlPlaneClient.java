package cn.gzuoj.worker;

import cn.gzuoj.shared.AiSandboxCompletion;
import cn.gzuoj.shared.AiSandboxLease;
import cn.gzuoj.shared.JudgeCompletion;
import cn.gzuoj.shared.JudgeLanguage;
import cn.gzuoj.shared.JudgeLease;
import cn.gzuoj.shared.JudgeStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** HTTP 调用失败且不能作为正常空响应处理。 */
class RemoteCallException extends RuntimeException {
    private final String target;
    private final Integer statusCode;

    RemoteCallException(String target, Integer statusCode, String message) {
        super(message);
        this.target = target;
        this.statusCode = statusCode;
    }

    String getTarget() {
        return target;
    }

    Integer getStatusCode() {
        return statusCode;
    }
}

/** 控制端心跳请求。 */
record ControlHeartbeat(
    boolean cpuController,
    boolean memoryController,
    boolean pidsController,
    boolean noFallback,
    Set<JudgeLanguage> languages,
    int judgeSlots,
    int aiSlots
) {}

/** Worker 进度请求。 */
record ControlProgress(UUID attemptId, String leaseToken, JudgeStatus status) {}

/** Worker 与控制端之间的 HTTPS 客户端。 */
@Component
public class ControlPlaneClient {
    private final WorkerProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public ControlPlaneClient(WorkerProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 上报安全预检和存活状态。 */
    public void heartbeat(ControlHeartbeat heartbeat) {
        sendJson("/internal/worker/v1/heartbeat", heartbeat, Void.class, Set.of(200));
    }

    /** 长轮询领取任务；204 表示当前无任务。 */
    public JudgeLease claim() {
        return sendJson("/internal/worker/v1/jobs/claim", Map.of(), JudgeLease.class, Set.of(200, 204));
    }

    /** AI 槽长轮询领取测试生成和差分任务；204 表示当前无任务。 */
    public AiSandboxLease claimAi() {
        return sendJson("/internal/worker/v1/ai-jobs/claim", Map.of(), AiSandboxLease.class, Set.of(200, 204));
    }

    /** 上报编译或判题进度。 */
    public void progress(JudgeLease lease, JudgeStatus status) {
        sendJson(
            "/internal/worker/v1/jobs/" + lease.getJobId() + "/progress",
            new ControlProgress(lease.getAttemptId(), lease.getLeaseToken(), status),
            Void.class,
            Set.of(204)
        );
    }

    /** 延长当前租约。 */
    public void renew(JudgeLease lease) {
        String token = URLEncoder.encode(lease.getLeaseToken(), StandardCharsets.UTF_8);
        sendJson(
            "/internal/worker/v1/jobs/" + lease.getJobId() + "/renew?attemptId="
                + lease.getAttemptId() + "&leaseToken=" + token,
            Map.of(),
            Void.class,
            Set.of(200)
        );
    }

    /** 延长当前 AI 生成和差分任务租约。 */
    public void renewAi(AiSandboxLease lease) {
        String token = URLEncoder.encode(lease.getLeaseToken(), StandardCharsets.UTF_8);
        sendJson(
            "/internal/worker/v1/ai-jobs/" + lease.getJobId() + "/renew?attemptId="
                + lease.getAttemptId() + "&leaseToken=" + token,
            Map.of(),
            Void.class,
            Set.of(200)
        );
    }

    /** 幂等结算当前租约。 */
    public void complete(JudgeLease lease, JudgeCompletion completion) {
        sendJson("/internal/worker/v1/jobs/" + lease.getJobId() + "/complete", completion, Void.class, Set.of(200));
    }

    /** 幂等结算当前 AI 生成和差分任务。 */
    public void completeAi(AiSandboxLease lease, AiSandboxCompletion completion) {
        sendJson("/internal/worker/v1/ai-jobs/" + lease.getJobId() + "/complete", completion, Void.class, Set.of(200));
    }

    /** 下载租约绑定制品，禁止跨域重定向并校验响应状态。 */
    public byte[] download(String url) {
        URI uri = URI.create(url);
        URI control = URI.create(properties.controlBaseUrl());
        if (!sameOrigin(control, uri)) {
            throw new RemoteCallException("control-artifact", null, "控制端返回了跨域制品地址");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
            .header("Authorization", "Bearer " + properties.controlToken())
            .GET()
            .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RemoteCallException("control-artifact", response.statusCode(), "下载判题制品失败");
            }
            return response.body();
        } catch (RemoteCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RemoteCallException("control-artifact", null, messageOr(exception, "控制端网络错误"));
        }
    }

    /** 发送 JSON POST 并按目标类型解析成功响应。 */
    private <T> T sendJson(String path, Object body, Class<T> responseType, Set<Integer> acceptedStatuses) {
        if (properties.controlToken().length() < 40) {
            throw new IllegalStateException("GZU_OJ_WORKER_TOKEN 未配置或强度不足");
        }
        HttpRequest request = HttpRequest.newBuilder(resolve(properties.controlBaseUrl(), path))
            .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
            .header("Authorization", "Bearer " + properties.controlToken())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
            .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception exception) {
            throw new RemoteCallException("control", null, messageOr(exception, "控制端网络错误"));
        }
        if (!acceptedStatuses.contains(response.statusCode())) {
            throw new RemoteCallException("control", response.statusCode(), "控制端请求失败");
        }
        if (response.statusCode() == 204 || responseType == Void.class || response.body().length == 0) {
            return null;
        }
        byte[] responseBody = response.body();
        try {
            return mapper.readValue(responseBody, responseType);
        } catch (Exception exception) {
            String detail = exception.getMessage() == null ? "null" : take(exception.getMessage(), 300);
            throw new RemoteCallException(
                "control",
                response.statusCode(),
                "解析控制端响应失败（" + path + "）：" + detail + "；原始响应："
                    + take(new String(responseBody, StandardCharsets.UTF_8), 500)
            );
        }
    }

    private URI resolve(String base, String path) {
        return URI.create(base.replaceFirst("/+$", "") + path);
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
            && left.getHost().equalsIgnoreCase(right.getHost())
            && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static String messageOr(Exception exception, String fallback) {
        return exception.getMessage() == null ? fallback : exception.getMessage();
    }

    private static String take(String value, int length) {
        return value.substring(0, Math.min(value.length(), length));
    }
}
