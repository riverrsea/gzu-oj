package cn.gzuoj.worker

import cn.gzuoj.shared.AiSandboxLease
import cn.gzuoj.shared.JudgeLease
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Worker 的多槽领取、续租、心跳和结算运行循环。 */
@Component
class WorkerRuntime(
    /** Worker 配置。 */
    private val properties: WorkerProperties,
    /** 沙箱启动预检。 */
    private val preflight: SandboxPreflight,
    /** 控制端客户端。 */
    private val control: ControlPlaneClient,
    /** 完整提交判题引擎。 */
    private val engine: JudgeEngine,
    /** AI 测试生成和差分引擎。 */
    private val aiEngine: AiSandboxEngine,
) : ApplicationRunner, AutoCloseable {
    /** 当前进程是否继续领取任务。 */
    private val running = AtomicBoolean(true)

    /** 各并发槽使用非守护线程，保证非 Web Worker 进程持续运行。 */
    private lateinit var judgeSlotExecutor: java.util.concurrent.ExecutorService

    /** AI 任务使用独立线程池，不占用普通提交槽。 */
    private lateinit var aiSlotExecutor: java.util.concurrent.ExecutorService

    /** 单独处理心跳和所有活跃任务续租。 */
    private lateinit var scheduler: ScheduledExecutorService

    /** 当前正在执行且需要续租的任务。 */
    private val activeJudgeLeases = ConcurrentHashMap<UUID, ActiveJudgeLease>()

    /** 当前正在执行且需要续租的 AI 沙箱任务。 */
    private val activeAiLeases = ConcurrentHashMap<UUID, ActiveAiLease>()

    /** 已通过的沙箱能力。 */
    private lateinit var capabilities: SandboxCapabilities

    /** 启动预检后创建固定数量的完整提交处理槽。 */
    override fun run(args: ApplicationArguments) {
        require(properties.slots in 1..64) { "Worker 槽数必须位于 1 到 64" }
        require(properties.aiSlots in 0..16) { "Worker AI 槽数必须位于 0 到 16" }
        require(properties.renewSeconds in 5..30) { "租约续期周期必须位于 5 到 30 秒" }
        capabilities = preflight.verify()
        control.heartbeat(capabilities.heartbeat(properties.slots, properties.aiSlots))
        judgeSlotExecutor = Executors.newFixedThreadPool(properties.slots) { runnable ->
            Thread(runnable, "judge-slot").apply { isDaemon = false }
        }
        if (properties.aiSlots > 0) {
            aiSlotExecutor = Executors.newFixedThreadPool(properties.aiSlots) { runnable ->
                Thread(runnable, "ai-sandbox-slot").apply { isDaemon = false }
            }
        }
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "worker-maintenance").apply { isDaemon = false }
        }
        repeat(properties.slots) { judgeSlotExecutor.submit(::judgeSlotLoop) }
        if (::aiSlotExecutor.isInitialized) {
            repeat(properties.aiSlots) { aiSlotExecutor.submit(::aiSlotLoop) }
        }
        scheduler.scheduleAtFixedRate(::maintenance, properties.renewSeconds, properties.renewSeconds, TimeUnit.SECONDS)
        logger.info("判题 Worker 已启动，普通槽数={}，AI 槽数={}，cgroup v2 预检通过", properties.slots, properties.aiSlots)
    }

    /** 单个槽持续长轮询，并保证一份提交内部测试点串行执行。 */
    private fun judgeSlotLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val lease = control.claim() ?: continue
                val active = ActiveJudgeLease(lease)
                activeJudgeLeases[lease.jobId] = active
                try {
                    val completion = engine.judge(lease) { active.valid.get() && running.get() }
                    if (active.valid.get()) control.complete(lease, completion)
                } finally {
                    activeJudgeLeases.remove(lease.jobId)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                logger.error("判题槽执行失败，将保留数据库任务等待租约过期", failure)
                pauseAfterFailure()
            }
        }
    }

    /** 单个 AI 槽持续处理完整生成任务，任务内部的种子和差分程序保持串行。 */
    private fun aiSlotLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val lease = control.claimAi() ?: continue
                val active = ActiveAiLease(lease)
                activeAiLeases[lease.jobId] = active
                try {
                    val completion = aiEngine.execute(lease) { active.valid.get() && running.get() }
                    if (active.valid.get()) control.completeAi(lease, completion)
                } finally {
                    activeAiLeases.remove(lease.jobId)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                logger.error("AI 沙箱槽执行失败，将保留数据库任务等待租约过期", failure)
                pauseAfterFailure()
            }
        }
    }

    /** 定期上报节点心跳并续期所有活跃租约。 */
    private fun maintenance() {
        if (!running.get()) return
        try {
            control.heartbeat(capabilities.heartbeat(properties.slots, properties.aiSlots))
        } catch (failure: Exception) {
            logger.warn("Worker 心跳上报失败", failure)
        }
        activeJudgeLeases.values.forEach { active ->
            if (!active.valid.get()) return@forEach
            try {
                control.renew(active.lease)
            } catch (failure: Exception) {
                active.valid.set(false)
                logger.warn("任务 {} 续租失败，停止继续执行后续测试点", active.lease.jobId, failure)
            }
        }
        activeAiLeases.values.forEach { active ->
            if (!active.valid.get()) return@forEach
            try {
                control.renewAi(active.lease)
            } catch (failure: Exception) {
                active.valid.set(false)
                logger.warn("AI 任务 {} 续租失败，停止继续执行后续生成和差分", active.lease.jobId, failure)
            }
        }
    }

    /** 控制端不可用时短暂退避，避免本机忙循环。 */
    private fun pauseAfterFailure() {
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 停止领取并中断维护线程；未结算任务由租约超时自动回队。 */
    override fun close() {
        running.set(false)
        activeJudgeLeases.values.forEach { it.valid.set(false) }
        activeAiLeases.values.forEach { it.valid.set(false) }
        if (::scheduler.isInitialized) scheduler.shutdownNow()
        if (::judgeSlotExecutor.isInitialized) judgeSlotExecutor.shutdownNow()
        if (::aiSlotExecutor.isInitialized) aiSlotExecutor.shutdownNow()
    }

    /** 活跃租约及其本机有效标记。 */
    private data class ActiveJudgeLease(
        /** 控制端签发的租约。 */
        val lease: JudgeLease,
        /** 续租失败后立即置为假。 */
        val valid: AtomicBoolean = AtomicBoolean(true),
    )

    /** 活跃 AI 租约及其本机有效标记。 */
    private data class ActiveAiLease(
        /** 控制端签发的 AI 沙箱租约。 */
        val lease: AiSandboxLease,
        /** 续租失败后立即置为假。 */
        val valid: AtomicBoolean = AtomicBoolean(true),
    )

    private companion object {
        /** 日志记录器。 */
        val logger = LoggerFactory.getLogger(WorkerRuntime::class.java)

        /** 网络异常后的重试退避。 */
        const val RETRY_DELAY_MS: Long = 2_000
    }
}
