package cn.gzuoj.worker;

import cn.gzuoj.shared.JudgeLanguage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Worker 启动时形成的沙箱安全能力。 */
record SandboxCapabilities(boolean cpu, boolean memory, boolean pids, boolean noFallback) {
    /** 转换为控制端心跳请求。 */
    ControlHeartbeat heartbeat(int judgeSlots, int aiSlots) {
        return new ControlHeartbeat(
            cpu,
            memory,
            pids,
            noFallback,
            Set.of(JudgeLanguage.values()),
            judgeSlots,
            aiSlots
        );
    }
}

/** cgroup v2 和 go-judge 运行限制启动预检。 */
@Component
public class SandboxPreflight {
    private static final long MIB = 1024L * 1024L;
    private static final List<String> SAFE_ENVIRONMENT = List.of(
        "PATH=/usr/bin:/bin", "LANG=C.UTF-8", "HOME=/tmp"
    );

    private final WorkerProperties properties;
    private final GoJudgeClient goJudge;

    public SandboxPreflight(WorkerProperties properties, GoJudgeClient goJudge) {
        this.properties = properties;
        this.goJudge = goJudge;
    }

    /** 执行真实资源探针；任一关键能力缺失时拒绝领取任务。 */
    public SandboxCapabilities verify() {
        goJudge.config();
        Path controllersPath = properties.cgroupRoot().resolve("cgroup.controllers");
        Set<String> controllers = Set.of();
        if (Files.isReadable(controllersPath)) {
            try {
                controllers = Arrays.stream(Files.readString(controllersPath).trim().split("\\s+"))
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.toSet());
            } catch (IOException exception) {
                throw new IllegalStateException("读取 cgroup v2 控制器失败", exception);
            }
        }
        boolean cpu = controllers.contains("cpu") && cpuProbe();
        boolean memory = controllers.contains("memory") && memoryProbe();
        boolean pids = controllers.contains("pids") && pidsProbe();
        SandboxCapabilities capabilities = new SandboxCapabilities(
            cpu, memory, pids, properties.requireNoFallback()
        );
        if (!(capabilities.cpu() && capabilities.memory() && capabilities.pids() && capabilities.noFallback())) {
            throw new IllegalStateException(
                "判题沙箱预检未通过：必须启用 cgroup v2 的 cpu/memory/pids 控制器和 go-judge -no-fallback"
            );
        }
        return capabilities;
    }

    /** 验证忙循环能够被 CPU 限制终止。 */
    private boolean cpuProbe() {
        return goJudge.run(probeCommand(
            List.of("/bin/sh", "-c", "while :; do :; done"),
            50_000_000L,
            1_000_000_000L,
            64L * MIB,
            1
        )).status().equals("Time Limit Exceeded");
    }

    /** 验证受限进程无法申请远超上限的内存。 */
    private boolean memoryProbe() {
        String status = goJudge.run(probeCommand(
            List.of("/usr/bin/python3", "-c", "a=bytearray(256*1024*1024)"),
            1_000_000_000L,
            2_000_000_000L,
            64L * MIB,
            1
        )).status();
        return Set.of("Memory Limit Exceeded", "Nonzero Exit Status", "Signalled").contains(status);
    }

    /** 验证进程数量上限会阻止 shell 创建子进程。 */
    private boolean pidsProbe() {
        return !goJudge.run(probeCommand(
            List.of("/bin/sh", "-c", "sleep 1 & wait"),
            1_000_000_000L,
            2_000_000_000L,
            64L * MIB,
            1
        )).status().equals("Accepted");
    }

    /** 构造不回传输出的安全探针命令。 */
    private GoJudgeCommand probeCommand(
        List<String> args,
        long cpuLimit,
        long clockLimit,
        long memoryLimit,
        int procLimit
    ) {
        return new GoJudgeCommand(
            args,
            SAFE_ENVIRONMENT,
            List.of(
                new GoJudgeFile("", null, null, null),
                new GoJudgeFile(null, null, "stdout", 4096L),
                new GoJudgeFile(null, null, "stderr", 4096L)
            ),
            cpuLimit,
            clockLimit,
            memoryLimit,
            16L * MIB,
            procLimit,
            Map.of(),
            List.of("stdout", "stderr"),
            List.of(),
            8192L,
            false,
            true,
            false
        );
    }
}
