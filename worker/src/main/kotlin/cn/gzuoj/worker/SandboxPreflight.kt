package cn.gzuoj.worker

import cn.gzuoj.shared.JudgeLanguage
import org.springframework.stereotype.Component
import java.nio.file.Files

/** Worker 启动时形成的沙箱安全能力。 */
data class SandboxCapabilities(
    /** cgroup v2 CPU 控制器是否存在且 CPU 限制探针通过。 */
    val cpu: Boolean,
    /** cgroup v2 memory 控制器是否存在且内存限制探针通过。 */
    val memory: Boolean,
    /** cgroup v2 pids 控制器是否存在且进程限制探针通过。 */
    val pids: Boolean,
    /** 部署是否声明并强制 no-fallback。 */
    val noFallback: Boolean,
) {
    /** 转换为控制端心跳请求。 */
    fun heartbeat(): ControlHeartbeat = ControlHeartbeat(
        cpuController = cpu,
        memoryController = memory,
        pidsController = pids,
        noFallback = noFallback,
        languages = JudgeLanguage.entries.toSet(),
    )
}

/** cgroup v2 和 go-judge 运行限制启动预检。 */
@Component
class SandboxPreflight(
    /** Worker 配置。 */
    private val properties: WorkerProperties,
    /** go-judge 客户端。 */
    private val goJudge: GoJudgeClient,
) {
    /** 执行真实资源探针；任一关键能力缺失时拒绝领取任务。 */
    fun verify(): SandboxCapabilities {
        goJudge.config()
        val controllersPath = properties.cgroupRoot.resolve("cgroup.controllers")
        val controllers = if (Files.isReadable(controllersPath)) {
            Files.readString(controllersPath).trim().split(Regex("\\s+")).toSet()
        } else {
            emptySet()
        }
        val cpu = "cpu" in controllers && cpuProbe()
        val memory = "memory" in controllers && memoryProbe()
        val pids = "pids" in controllers && pidsProbe()
        val capabilities = SandboxCapabilities(cpu, memory, pids, properties.requireNoFallback)
        check(capabilities.cpu && capabilities.memory && capabilities.pids && capabilities.noFallback) {
            "判题沙箱预检未通过：必须启用 cgroup v2 的 cpu/memory/pids 控制器和 go-judge -no-fallback"
        }
        return capabilities
    }

    /** 验证忙循环能够被 CPU 限制终止。 */
    private fun cpuProbe(): Boolean = goJudge.run(
        probeCommand(
            args = listOf("/bin/sh", "-c", "while :; do :; done"),
            cpuLimit = 50_000_000,
            clockLimit = 1_000_000_000,
            memoryLimit = 64L * MIB,
            procLimit = 1,
        ),
    ).status == "Time Limit Exceeded"

    /** 验证受限进程无法申请远超上限的内存。 */
    private fun memoryProbe(): Boolean = goJudge.run(
        probeCommand(
            args = listOf("/usr/bin/python3", "-c", "a=bytearray(256*1024*1024)"),
            cpuLimit = 1_000_000_000,
            clockLimit = 2_000_000_000,
            memoryLimit = 64L * MIB,
            procLimit = 1,
        ),
    ).status in setOf("Memory Limit Exceeded", "Nonzero Exit Status", "Signalled")

    /** 验证进程数量上限会阻止 shell 创建子进程。 */
    private fun pidsProbe(): Boolean = goJudge.run(
        probeCommand(
            args = listOf("/bin/sh", "-c", "sleep 1 & wait"),
            cpuLimit = 1_000_000_000,
            clockLimit = 2_000_000_000,
            memoryLimit = 64L * MIB,
            procLimit = 1,
        ),
    ).status != "Accepted"

    /** 构造不回传输出的安全探针命令。 */
    private fun probeCommand(
        args: List<String>,
        cpuLimit: Long,
        clockLimit: Long,
        memoryLimit: Long,
        procLimit: Int,
    ): GoJudgeCommand = GoJudgeCommand(
        args = args,
        env = SAFE_ENVIRONMENT,
        files = listOf(GoJudgeFile(content = ""), GoJudgeFile(name = "stdout", max = 4096), GoJudgeFile(name = "stderr", max = 4096)),
        cpuLimit = cpuLimit,
        clockLimit = clockLimit,
        memoryLimit = memoryLimit,
        stackLimit = 16L * MIB,
        procLimit = procLimit,
        copyOut = listOf("stdout", "stderr"),
        copyOutMax = 8192,
        strictMemoryLimit = true,
    )

    private companion object {
        /** 二进制 MiB。 */
        const val MIB: Long = 1024L * 1024L

        /** 探针使用的最小环境变量。 */
        val SAFE_ENVIRONMENT: List<String> = listOf("PATH=/usr/bin:/bin", "LANG=C.UTF-8", "HOME=/tmp")
    }
}
