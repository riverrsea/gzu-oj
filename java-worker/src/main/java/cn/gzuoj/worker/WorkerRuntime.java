package cn.gzuoj.worker;

import cn.gzuoj.shared.AiSandboxLease;
import cn.gzuoj.shared.JudgeLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Worker 的多槽领取、续租、心跳和结算运行循环。 */
@Component
public class WorkerRuntime implements ApplicationRunner, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerRuntime.class);
    private static final long RETRY_DELAY_MS = 2_000L;

    private final WorkerProperties properties;
    private final SandboxPreflight preflight;
    private final ControlPlaneClient control;
    private final JudgeEngine engine;
    private final AiSandboxEngine aiEngine;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentHashMap<UUID, ActiveJudgeLease> activeJudgeLeases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ActiveAiLease> activeAiLeases = new ConcurrentHashMap<>();

    private ExecutorService judgeSlotExecutor;
    private ExecutorService aiSlotExecutor;
    private ScheduledExecutorService scheduler;
    private SandboxCapabilities capabilities;

    public WorkerRuntime(
        WorkerProperties properties,
        SandboxPreflight preflight,
        ControlPlaneClient control,
        JudgeEngine engine,
        AiSandboxEngine aiEngine
    ) {
        this.properties = properties;
        this.preflight = preflight;
        this.control = control;
        this.engine = engine;
        this.aiEngine = aiEngine;
    }

    /** 启动预检后创建固定数量的完整提交处理槽。 */
    @Override
    public void run(ApplicationArguments args) {
        if (properties.slots() < 1 || properties.slots() > 64) {
            throw new IllegalArgumentException("Worker 槽数必须位于 1 到 64");
        }
        if (properties.aiSlots() < 0 || properties.aiSlots() > 16) {
            throw new IllegalArgumentException("Worker AI 槽数必须位于 0 到 16");
        }
        if (properties.renewSeconds() < 5 || properties.renewSeconds() > 30) {
            throw new IllegalArgumentException("租约续期周期必须位于 5 到 30 秒");
        }

        capabilities = preflight.verify();
        control.heartbeat(capabilities.heartbeat(properties.slots(), properties.aiSlots()));
        judgeSlotExecutor = Executors.newFixedThreadPool(properties.slots(), runnable -> {
            Thread thread = new Thread(runnable, "judge-slot");
            thread.setDaemon(false);
            return thread;
        });
        if (properties.aiSlots() > 0) {
            aiSlotExecutor = Executors.newFixedThreadPool(properties.aiSlots(), runnable -> {
                Thread thread = new Thread(runnable, "ai-sandbox-slot");
                thread.setDaemon(false);
                return thread;
            });
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "worker-maintenance");
            thread.setDaemon(false);
            return thread;
        });
        for (int slot = 0; slot < properties.slots(); slot++) {
            judgeSlotExecutor.submit(this::judgeSlotLoop);
        }
        if (aiSlotExecutor != null) {
            for (int slot = 0; slot < properties.aiSlots(); slot++) {
                aiSlotExecutor.submit(this::aiSlotLoop);
            }
        }
        scheduler.scheduleAtFixedRate(
            this::maintenance,
            properties.renewSeconds(),
            properties.renewSeconds(),
            TimeUnit.SECONDS
        );
        LOGGER.info(
            "判题 Worker 已启动，普通槽数={}，AI 槽数={}，cgroup v2 预检通过",
            properties.slots(),
            properties.aiSlots()
        );
    }

    /** 单个槽持续长轮询，并保证一份提交内部测试点串行执行。 */
    private void judgeSlotLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                JudgeLease lease = control.claim();
                if (lease == null) {
                    continue;
                }
                ActiveJudgeLease active = new ActiveJudgeLease(lease, new AtomicBoolean(true));
                activeJudgeLeases.put(lease.getJobId(), active);
                try {
                    var completion = engine.judge(
                        lease,
                        () -> active.valid().get() && running.get()
                    );
                    if (active.valid().get()) {
                        control.complete(lease, completion);
                    }
                } finally {
                    activeJudgeLeases.remove(lease.getJobId());
                }
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) {
                    continue;
                }
                LOGGER.error("判题槽执行失败，将保留数据库任务等待租约过期", failure);
                pauseAfterFailure();
            }
        }
    }

    /** 单个 AI 槽持续处理完整生成任务，任务内部的种子和差分程序保持串行。 */
    private void aiSlotLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                AiSandboxLease lease = control.claimAi();
                if (lease == null) {
                    continue;
                }
                ActiveAiLease active = new ActiveAiLease(lease, new AtomicBoolean(true));
                activeAiLeases.put(lease.getJobId(), active);
                try {
                    var completion = aiEngine.execute(
                        lease,
                        () -> active.valid().get() && running.get()
                    );
                    if (active.valid().get()) {
                        control.completeAi(lease, completion);
                    }
                } finally {
                    activeAiLeases.remove(lease.getJobId());
                }
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) {
                    continue;
                }
                LOGGER.error("AI 沙箱槽执行失败，将保留数据库任务等待租约过期", failure);
                pauseAfterFailure();
            }
        }
    }

    /** 定期上报节点心跳并续期所有活跃租约。 */
    private void maintenance() {
        if (!running.get()) {
            return;
        }
        try {
            control.heartbeat(capabilities.heartbeat(properties.slots(), properties.aiSlots()));
        } catch (Exception failure) {
            LOGGER.warn("Worker 心跳上报失败", failure);
        }
        for (ActiveJudgeLease active : activeJudgeLeases.values()) {
            if (!active.valid().get()) {
                continue;
            }
            try {
                control.renew(active.lease());
            } catch (Exception failure) {
                active.valid().set(false);
                LOGGER.warn("任务 {} 续租失败，停止继续执行后续测试点", active.lease().getJobId(), failure);
            }
        }
        for (ActiveAiLease active : activeAiLeases.values()) {
            if (!active.valid().get()) {
                continue;
            }
            try {
                control.renewAi(active.lease());
            } catch (Exception failure) {
                active.valid().set(false);
                LOGGER.warn("AI 任务 {} 续租失败，停止继续执行后续生成和差分", active.lease().getJobId(), failure);
            }
        }
    }

    /** 控制端不可用时短暂退避，避免本机忙循环。 */
    private void pauseAfterFailure() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 停止领取并中断维护线程；未结算任务由租约超时自动回队。 */
    @Override
    public void close() {
        running.set(false);
        activeJudgeLeases.values().forEach(active -> active.valid().set(false));
        activeAiLeases.values().forEach(active -> active.valid().set(false));
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (judgeSlotExecutor != null) {
            judgeSlotExecutor.shutdownNow();
        }
        if (aiSlotExecutor != null) {
            aiSlotExecutor.shutdownNow();
        }
    }

    private record ActiveJudgeLease(JudgeLease lease, AtomicBoolean valid) {}

    private record ActiveAiLease(AiSandboxLease lease, AtomicBoolean valid) {}
}
