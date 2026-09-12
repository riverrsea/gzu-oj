package cn.gzuoj.api

import cn.gzuoj.shared.AI_DIFFERENTIAL_SANDBOX_STAGE
import cn.gzuoj.shared.AiCompileTask
import cn.gzuoj.shared.AiGeneratedCaseResult
import cn.gzuoj.shared.AiSandboxCompletion
import cn.gzuoj.shared.AiSandboxCompletionStatus
import cn.gzuoj.shared.AiSandboxFailureStage
import cn.gzuoj.shared.AiSandboxLease
import cn.gzuoj.shared.AiSandboxTaskPayload
import cn.gzuoj.shared.AiWorkflowState
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** API 复核后的 AI 测试生成结果。 */
data class VerifiedAiSandboxResult(
    /** 已验证且顺序与固定种子一致的测试点。 */
    val testCases: List<AiGeneratedCaseResult>,
    /** API 根据测点执行数据重新计算的最大时间百分比。 */
    val maximumTimePercent: Int,
    /** API 根据测点执行数据重新计算的最大内存百分比。 */
    val maximumMemoryPercent: Int,
)

/** AI 沙箱结算的内容、哈希、顺序和资源证据校验器。 */
object AiSandboxCompletionVerifier {
    /** 校验编译门禁结算：既不产出测试点，也不能把失败归到别的阶段。 */
    fun verifyCompile(task: AiCompileTask, completion: AiSandboxCompletion) {
        if (task.units.isEmpty()) invalid("编译门禁任务必须包含至少一个产物")
        when (completion.status) {
            AiSandboxCompletionStatus.PASSED -> {
                if (completion.failedStage != null) invalid("通过的编译门禁结算不能携带失败归属")
                if (completion.testCases.isNotEmpty()) invalid("编译门禁不能携带测试点")
            }
            // 编译门禁失败必须自带原因与归属，且归属只能是本阶段，否则 Agent 会重生成错误产物。
            AiSandboxCompletionStatus.VALIDATION_FAILED -> {
                if (completion.failureReason.isNullOrBlank()) invalid("失败的编译门禁结算缺少原因")
                if (completion.failedStage != task.stage) invalid("编译门禁失败归属与任务阶段不一致")
            }
            // 基础设施异常由 Agent 与管理员排障，不能伪装成某个产物的编译错误。
            AiSandboxCompletionStatus.SYSTEM_ERROR -> {
                if (completion.failureReason.isNullOrBlank()) invalid("失败的编译门禁结算缺少原因")
                if (completion.failedStage != null) invalid("基础设施异常的编译门禁不能携带产物归属")
            }
        }
    }

    /** 校验通过结算，防止 Worker 伪造种子、输出哈希或资源百分比。 */
    fun verify(task: AiSandboxTaskPayload, completion: AiSandboxCompletion): VerifiedAiSandboxResult {
        if (completion.status != AiSandboxCompletionStatus.PASSED) {
            if (completion.testCases.isNotEmpty()) invalid("失败的 AI 沙箱任务不能携带测试点")
            if (completion.failureReason.isNullOrBlank()) invalid("失败的 AI 沙箱任务缺少原因")
            return VerifiedAiSandboxResult(emptyList(), 0, 0)
        }
        if (!completion.deterministic || !completion.solutionsAgree || !completion.bruteForcePassed) {
            invalid("通过结算必须满足复现、双标程和暴力差分门禁")
        }
        if (completion.failedStage != null) invalid("通过的 AI 沙箱结算不能携带失败归属")
        if (task.seeds.isEmpty() || task.seeds.distinct().size != task.seeds.size) {
            invalid("AI 沙箱任务的固定种子必须互不重复")
        }
        if (completion.testCases.size != task.seeds.size) {
            invalid("AI 测试点数量与固定种子不一致")
        }
        if (task.bruteForceCaseCount !in 1..task.seeds.size) invalid("暴力差分测试点数量不合法")

        var totalBytes = 0L
        var maximumTimeMs = 0L
        var maximumMemoryKiB = 0L
        val inputHashes = mutableSetOf<String>()
        completion.testCases.forEachIndexed { index, testCase ->
            if (testCase.ordinal != index + 1 || testCase.seed != task.seeds[index]) {
                invalid("AI 测试点顺序或固定种子不一致")
            }
            val inputBytes = testCase.input.toByteArray(Charsets.UTF_8)
            val outputBytes = testCase.expectedOutput.toByteArray(Charsets.UTF_8)
            if (inputBytes.size > MAX_CASE_BYTES || outputBytes.size > MAX_CASE_BYTES) {
                invalid("单个 AI 测试点输入或输出超过 16 MiB")
            }
            totalBytes += inputBytes.size + outputBytes.size
            if (totalBytes > MAX_TOTAL_BYTES) invalid("AI 测试点总大小超过 64 MiB")

            val inputHash = SecureValues.sha256(inputBytes)
            val outputHash = SecureValues.sha256(outputBytes)
            if (!inputHash.equals(testCase.inputSha256, true) || !outputHash.equals(testCase.outputSha256, true)) {
                invalid("AI 测试点内容与 SHA-256 不一致")
            }
            if (!inputHashes.add(inputHash)) invalid("AI 生成了重复测试输入")
            if (!outputHash.equals(testCase.solutionAOutputSha256, true) ||
                !outputHash.equals(testCase.solutionBOutputSha256, true)
            ) {
                invalid("两份标程输出哈希与标准输出不一致")
            }
            if (index < task.bruteForceCaseCount) {
                if (!outputHash.equals(testCase.bruteForceOutputSha256, true)) {
                    invalid("小规模测试点的暴力解输出不一致")
                }
            } else if (testCase.bruteForceOutputSha256 != null) {
                invalid("大数据测试点不应伪造暴力差分证据")
            }
            val resources = listOf(
                testCase.solutionATimeMs,
                testCase.solutionBTimeMs,
                testCase.solutionAMemoryKiB,
                testCase.solutionBMemoryKiB,
            )
            if (resources.any { it < 0 }) invalid("AI 差分资源用量不能为负数")
            maximumTimeMs = maxOf(maximumTimeMs, testCase.solutionATimeMs, testCase.solutionBTimeMs)
            maximumMemoryKiB = maxOf(maximumMemoryKiB, testCase.solutionAMemoryKiB, testCase.solutionBMemoryKiB)
        }
        val timePercent = percentage(maximumTimeMs, task.timeLimitMs)
        val memoryPercent = percentage(maximumMemoryKiB, task.memoryLimitMiB * 1024L)
        if (completion.maximumTimePercent != timePercent || completion.maximumMemoryPercent != memoryPercent) {
            invalid("Worker 上报的资源余量百分比不一致")
        }
        return VerifiedAiSandboxResult(completion.testCases, timePercent, memoryPercent)
    }

    /** 向上取整计算资源占比，并避免零限制和整数溢出。 */
    private fun percentage(used: Long, limit: Long): Int {
        if (limit <= 0) invalid("AI 沙箱资源限制不合法")
        if (used == 0L) return 0
        return kotlin.math.ceil((used.toDouble() * 100.0) / limit.toDouble()).toInt().coerceAtMost(Int.MAX_VALUE)
    }

    /** 抛出稳定的 Worker 协议错误。 */
    private fun invalid(message: String): Nothing = throw ApiException(
        HttpStatus.BAD_REQUEST,
        "INVALID_AI_SANDBOX_COMPLETION",
        message,
    )

    /** 单个测试点输入或输出上限。 */
    private const val MAX_CASE_BYTES: Int = 16 * 1024 * 1024

    /** 单次 AI 任务所有内联测试数据的总上限。 */
    private const val MAX_TOTAL_BYTES: Long = 64L * 1024L * 1024L
}

/**
 * 沙箱作业可被 Worker 领取的运行状态。
 *
 * 领取查询和入队校验共用这一处定义：一旦运行离开这些状态（例如交给人工复核、进入校验或终态），
 * 再入队的作业永远不会有人领取，只会以 `QUEUED` 永远留在队列里。
 */
internal val AI_SANDBOX_CLAIMABLE_STATES: Set<AiWorkflowState> = setOf(
    AiWorkflowState.GENERATING_TESTS,
    AiWorkflowState.GENERATING_SOLUTIONS,
    AiWorkflowState.DIFFERENTIAL_TESTING,
)

/** 把可领取状态拼成 SQL 的 `IN` 列表；取值是枚举名而不是用户输入，直接内联即可。 */
internal val AI_SANDBOX_CLAIMABLE_STATES_SQL: String =
    AI_SANDBOX_CLAIMABLE_STATES.joinToString(",") { "'${it.name}'" }

/** 运行处于可领取状态时才允许入队新的沙箱作业。 */
internal fun canEnqueueSandboxJob(state: AiWorkflowState): Boolean = state in AI_SANDBOX_CLAIMABLE_STATES

/**
 * 取消某个运行尚未结算的沙箱作业。
 *
 * 运行离开可领取状态时必须调用，否则已经入队的作业会永远停在 `QUEUED`。
 * 管理员取消运行和交给人工复核都走这里，避免两处各写一遍 SQL。
 */
internal fun cancelPendingSandboxJobs(jdbc: JdbcTemplate, runId: UUID) {
    jdbc.update(
        "UPDATE ai_sandbox_job SET status = 'CANCELED', completed_at = now() WHERE run_id = ? AND status IN ('QUEUED', 'LEASED')",
        runId,
    )
}

/** PostgreSQL 持久化 AI 生成与差分任务队列。 */
@Service
class AiSandboxQueue(
    /** 数据库访问入口。 */
    private val jdbc: JdbcTemplate,
    /** AI 任务参数和结果 JSON 编解码器。 */
    private val mapper: ObjectMapper,
    /** Worker 租约参数。 */
    private val properties: AppProperties,
    /** AI 状态机和测试点落库服务。 */
    private val runs: AiRunService,
) {
    /** 领取一份低优先级 AI 沙箱任务并立即提交租约事务。 */
    @Transactional
    fun claim(worker: WorkerIdentity): AiSandboxLease? {
        if (worker.aiSlots <= 0) return null
        val row = jdbc.query(
            """
            SELECT j.id, j.run_id, r.problem_version_id, j.stage, j.payload::text
            FROM ai_sandbox_job j JOIN ai_problem_run r ON r.id = j.run_id
            WHERE j.available_at <= now()
              AND r.state IN ($AI_SANDBOX_CLAIMABLE_STATES_SQL)
              AND (j.status = 'QUEUED' OR (j.status = 'LEASED' AND j.lease_expires_at < now()))
            ORDER BY j.priority DESC, j.created_at
            FOR UPDATE OF j SKIP LOCKED
            LIMIT 1
            """.trimIndent(),
            { result, _ ->
                val parsed = parseTask(result.getString("stage"), result.getString("payload"))
                ClaimRow(
                    jobId = result.getObject("id", UUID::class.java),
                    runId = result.getObject("run_id", UUID::class.java),
                    problemVersionId = result.getObject("problem_version_id", UUID::class.java),
                    task = parsed.task,
                    compile = parsed.compile,
                )
            },
        ).firstOrNull() ?: return null
        val attemptId = UUID.randomUUID()
        val leaseToken = SecureValues.randomToken(48)
        val expiresAt = Instant.now().plus(properties.worker.leaseSeconds, ChronoUnit.SECONDS)
        jdbc.update(
            """
            UPDATE ai_sandbox_job
            SET status = 'LEASED', attempt_id = ?, lease_token_hash = ?, leased_by = ?, lease_expires_at = ?
            WHERE id = ?
            """.trimIndent(),
            attemptId,
            SecureValues.sha256(leaseToken),
            worker.id,
            Timestamp.from(expiresAt),
            row.jobId,
        )
        return AiSandboxLease(
            jobId = row.jobId,
            runId = row.runId,
            problemVersionId = row.problemVersionId,
            attemptId = attemptId,
            leaseToken = leaseToken,
            leaseExpiresAt = expiresAt,
            task = row.task,
            compile = row.compile,
        )
    }

    /** 按数据库 stage 解析任务参数；DIFFERENTIAL 之外都是编译门禁。 */
    private fun parseTask(stage: String, json: String): ParsedAiSandboxTask =
        if (stage == AI_DIFFERENTIAL_SANDBOX_STAGE) {
            ParsedAiSandboxTask(task = mapper.readValue(json, AiSandboxTaskPayload::class.java))
        } else {
            // 数据库阶段名必须能对回枚举，否则说明写入端出现了未知阶段。
            AiSandboxFailureStage.valueOf(stage)
            ParsedAiSandboxTask(compile = mapper.readValue(json, AiCompileTask::class.java))
        }

    /** 延长一份当前有效的 AI 沙箱租约。 */
    @Transactional
    fun renew(worker: WorkerIdentity, jobId: UUID, attemptId: UUID, leaseToken: String): RenewLeaseResponse {
        validateLease(worker, jobId, attemptId, leaseToken)
        val expiresAt = Instant.now().plus(properties.worker.leaseSeconds, ChronoUnit.SECONDS)
        jdbc.update("UPDATE ai_sandbox_job SET lease_expires_at = ? WHERE id = ?", Timestamp.from(expiresAt), jobId)
        return RenewLeaseResponse(expiresAt)
    }

    /** 幂等结算 AI 沙箱任务，基础设施异常最多重新排队三次。 */
    @Transactional
    fun complete(worker: WorkerIdentity, jobId: UUID, completion: AiSandboxCompletion): CompleteLeaseResponse {
        val job = jdbc.query(
            """
            SELECT run_id, repair_round, status, attempt_id, lease_token_hash, leased_by, infrastructure_attempts,
                   stage, payload::text
            FROM ai_sandbox_job WHERE id = ? FOR UPDATE
            """.trimIndent(),
            { result, _ ->
                val parsed = parseTask(result.getString("stage"), result.getString("payload"))
                CompletionRow(
                    runId = result.getObject("run_id", UUID::class.java),
                    repairRound = result.getInt("repair_round"),
                    status = result.getString("status"),
                    attemptId = result.getObject("attempt_id", UUID::class.java),
                    leaseTokenHash = result.getString("lease_token_hash"),
                    leasedBy = result.getObject("leased_by", UUID::class.java),
                    infrastructureAttempts = result.getInt("infrastructure_attempts"),
                    task = parsed.task,
                    compile = parsed.compile,
                )
            },
            jobId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_SANDBOX_JOB_NOT_FOUND", "AI 沙箱任务不存在")
        val sameAttempt = job.attemptId == completion.attemptId && job.leaseTokenHash != null &&
            SecureValues.constantTimeEquals(job.leaseTokenHash, SecureValues.sha256(completion.leaseToken))
        if (job.status in setOf("COMPLETED", "FAILED") && sameAttempt) {
            return CompleteLeaseResponse(accepted = true, requeued = false)
        }
        if (job.status != "LEASED" || job.leasedBy != worker.id || !sameAttempt) {
            throw ApiException(HttpStatus.CONFLICT, "STALE_AI_SANDBOX_LEASE", "AI 沙箱租约已失效，结果未写入")
        }
        // 编译门禁不产出测试点，因此不参与差分证据校验和运行时状态推进。
        val differential = job.task != null
        val verified = if (differential) {
            AiSandboxCompletionVerifier.verify(job.requireTask(), completion)
        } else {
            AiSandboxCompletionVerifier.verifyCompile(job.requireCompile(), completion)
            null
        }
        val resultJson = mapper.writeValueAsString(completion)
        if (completion.status == AiSandboxCompletionStatus.SYSTEM_ERROR && job.infrastructureAttempts < 3) {
            jdbc.update(
                """
                UPDATE ai_sandbox_job SET status = 'QUEUED', available_at = now(),
                    infrastructure_attempts = infrastructure_attempts + 1,
                    attempt_id = NULL, lease_token_hash = NULL, leased_by = NULL, lease_expires_at = NULL,
                    result_json = ?::jsonb, failure_reason = ?
                WHERE id = ?
                """.trimIndent(),
                resultJson,
                completion.failureReason?.take(2_000),
                jobId,
            )
            return CompleteLeaseResponse(accepted = true, requeued = true)
        }
        when (completion.status) {
            AiSandboxCompletionStatus.PASSED -> if (differential) {
                runs.acceptSandboxResult(
                    jobId,
                    job.runId,
                    job.requireTask(),
                    completion,
                    requireNotNull(verified) { "差分任务缺少已验证的测试点" },
                )
            }
            AiSandboxCompletionStatus.VALIDATION_FAILED -> if (differential) {
                val reason = completion.failureReason ?: "AI 生成数据未通过差分门禁"
                if (job.repairRound < 2) runs.prepareSandboxRepair(job.runId, reason, completion.failedStage)
                else runs.failSandboxValidation(job.runId, reason)
            }
            AiSandboxCompletionStatus.SYSTEM_ERROR -> if (differential) {
                runs.failSandboxValidation(
                    job.runId,
                    "AI 沙箱基础设施连续失败：${completion.failureReason ?: "未知错误"}",
                )
            }
        }
        jdbc.update(
            """
            UPDATE ai_sandbox_job SET status = ?, result_json = ?::jsonb, failure_reason = ?, completed_at = now()
            WHERE id = ?
            """.trimIndent(),
            if (completion.status == AiSandboxCompletionStatus.PASSED) "COMPLETED" else "FAILED",
            resultJson,
            completion.failureReason?.take(2_000),
            jobId,
        )
        // 结果通知写入 outbox，Agent 不可用时由调度器重试，避免丢失 interrupt 恢复信号。
        jdbc.update(
            """
            INSERT INTO ai_agent_outbox(idempotency_key, event_type, run_id, payload)
            VALUES (?, 'SANDBOX_RESULT', ?, ?::jsonb)
            ON CONFLICT(idempotency_key) DO NOTHING
            """.trimIndent(),
            "sandbox-result:$jobId:${completion.status.name}",
            job.runId,
            mapper.writeValueAsString(
                AiAgentSandboxResultRequest(
                    eventId = UUID.nameUUIDFromBytes("sandbox:$jobId:${job.repairRound}:${completion.status.name}".toByteArray()),
                    runId = job.runId,
                    sandboxJobId = jobId,
                    repairRound = job.repairRound,
                    status = completion.status.name,
                    failureReason = completion.failureReason,
                    failedStage = completion.failedStage?.name,
                )
            ),
        )
        return CompleteLeaseResponse(accepted = true, requeued = false)
    }

    /** 校验租约归属、令牌和有效期。 */
    private fun validateLease(worker: WorkerIdentity, jobId: UUID, attemptId: UUID, leaseToken: String) {
        val valid = jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM ai_sandbox_job
                WHERE id = ? AND status = 'LEASED' AND attempt_id = ? AND leased_by = ?
                  AND lease_token_hash = ? AND lease_expires_at > now()
            )
            """.trimIndent(),
            Boolean::class.java,
            jobId,
            attemptId,
            worker.id,
            SecureValues.sha256(leaseToken),
        ) ?: false
        if (!valid) throw ApiException(HttpStatus.CONFLICT, "STALE_AI_SANDBOX_LEASE", "AI 沙箱租约已失效")
    }

    /** AI 任务领取时的数据库投影。 */
    private data class ClaimRow(
        /** 队列任务标识。 */
        val jobId: UUID,
        /** AI 运行标识。 */
        val runId: UUID,
        /** 草稿版本标识。 */
        val problemVersionId: UUID,
        /** 全量差分参数；编译门禁任务为空。 */
        val task: AiSandboxTaskPayload?,
        /** 编译门禁参数；全量差分任务为空。 */
        val compile: AiCompileTask?,
    )

    /** 按数据库阶段解析出的任务参数，两个字段互斥。 */
    private data class ParsedAiSandboxTask(
        /** 全量差分参数。 */
        val task: AiSandboxTaskPayload? = null,
        /** 编译门禁参数。 */
        val compile: AiCompileTask? = null,
    )

    /** AI 任务结算时的数据库投影。 */
    private data class CompletionRow(
        /** AI 运行标识。 */
        val runId: UUID,
        /** Python Agent 修复轮次。 */
        val repairRound: Int,
        /** 当前队列状态。 */
        val status: String,
        /** 当前执行尝试。 */
        val attemptId: UUID?,
        /** 当前租约令牌哈希。 */
        val leaseTokenHash: String?,
        /** 当前租约 Worker。 */
        val leasedBy: UUID?,
        /** 已发生的基础设施重试次数。 */
        val infrastructureAttempts: Int,
        /** 全量差分参数；编译门禁任务为空。 */
        val task: AiSandboxTaskPayload?,
        /** 编译门禁参数；全量差分任务为空。 */
        val compile: AiCompileTask?,
    ) {
        /** 取出差分参数；编译门禁任务调用即抛出。 */
        fun requireTask(): AiSandboxTaskPayload = requireNotNull(task) { "该 AI 沙箱任务不是差分任务" }

        /** 取出编译门禁参数；差分任务调用即抛出。 */
        fun requireCompile(): AiCompileTask = requireNotNull(compile) { "该 AI 沙箱任务不是编译门禁任务" }
    }
}

/** Worker 独立 AI 槽使用的内部租约接口。 */
@RestController
@RequestMapping("/internal/worker/v1/ai-jobs")
class AiSandboxWorkerController(
    /** Worker Bearer Token 认证服务。 */
    private val credentials: WorkerCredentialService,
    /** AI 沙箱持久化队列。 */
    private val queue: AiSandboxQueue,
    /** Worker 长轮询和租约配置。 */
    private val properties: AppProperties,
) {
    /** 长轮询领取一份 AI 生成与差分任务。 */
    @PostMapping("/claim")
    fun claim(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): ResponseEntity<AiSandboxLease> {
        val worker = credentials.authenticate(authorization)
        val deadline = System.nanoTime() + properties.worker.longPollSeconds * 1_000_000_000L
        do {
            val lease = queue.claim(worker)
            if (lease != null) return ResponseEntity.ok(lease)
            Thread.sleep(500)
        } while (System.nanoTime() < deadline)
        return ResponseEntity.noContent().build()
    }

    /** 续租当前 AI 沙箱任务。 */
    @PostMapping("/{jobId}/renew")
    fun renew(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable jobId: UUID,
        @RequestParam attemptId: UUID,
        @RequestParam leaseToken: String,
    ): RenewLeaseResponse = queue.renew(credentials.authenticate(authorization), jobId, attemptId, leaseToken)

    /** 幂等结算当前 AI 沙箱任务。 */
    @PostMapping("/{jobId}/complete")
    fun complete(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable jobId: UUID,
        @Valid @RequestBody body: AiSandboxCompletion,
    ): CompleteLeaseResponse = queue.complete(credentials.authenticate(authorization), jobId, body)
}
