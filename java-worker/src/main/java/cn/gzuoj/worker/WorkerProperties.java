package cn.gzuoj.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** Worker 与控制端、沙箱之间的运行参数。 */
@ConfigurationProperties("gzu-oj.worker")
public record WorkerProperties(
    /** 控制端 API 根地址。 */
    String controlBaseUrl,
    /** 控制端签发的高强度 Worker Bearer Token。 */
    String controlToken,
    /** go-judge 内部 REST 根地址。 */
    String goJudgeBaseUrl,
    /** go-judge 的独立 Bearer Token。 */
    String goJudgeToken,
    /** 可同时处理的完整提交数。 */
    int slots,
    /** 与普通提交隔离、可同时处理的完整 AI 测试生成任务数。 */
    int aiSlots,
    /** 租约续期周期，单位秒。 */
    long renewSeconds,
    /** 单次 HTTP 请求超时，单位秒。 */
    long requestTimeoutSeconds,
    /** cgroup v2 控制器文件所在目录。 */
    Path cgroupRoot,
    /** 部署是否强制 go-judge 使用 no-fallback。 */
    boolean requireNoFallback
) {}
