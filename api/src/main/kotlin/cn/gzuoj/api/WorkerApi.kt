package cn.gzuoj.api

import cn.gzuoj.shared.JudgeCaseLease
import cn.gzuoj.shared.JudgeCompletion
import cn.gzuoj.shared.JudgeExecutionMode
import cn.gzuoj.shared.JudgeLanguage
import cn.gzuoj.shared.JudgeLease
import cn.gzuoj.shared.JudgeStatus
import cn.gzuoj.shared.LanguageLimits
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.sql.Timestamp
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Worker 节点经过 Bearer Token 认证后的身份。 */
data class WorkerIdentity(
    /** 节点标识。 */
    val id: UUID,
    /** 节点名称。 */
    val name: String,
    /** 节点声明的并发槽数。 */
    val slots: Int,
    /** 节点声明的独立 AI 沙箱槽数。 */
    val aiSlots: Int,
)

/** 管理员创建 Worker 凭据的请求。 */
data class CreateWorkerRequest(
    /** 唯一节点名称。 */
    @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{1,99}$", message = "Worker 名称格式不正确")
    val name: String,
    /** 节点并发槽数。 */
    @field:Min(1)
    @field:Max(64)
    val slots: Int = 4,
    /** 与普通提交隔离的 AI 生成和差分槽数。 */
    @field:Min(0)
    @field:Max(16)
    val aiSlots: Int = 2,
)

/** 只在创建时返回一次的 Worker 明文令牌。 */
data class CreatedWorkerResponse(
    /** 节点标识。 */
    val id: UUID,
    /** 节点名称。 */
    val name: String,
    /** 只返回一次、数据库不保存明文的高强度令牌。 */
    val token: String,
    /** 节点并发槽数。 */
    val slots: Int,
    /** 节点独立 AI 沙箱槽数。 */
    val aiSlots: Int,
)

/** Worker 注册或心跳上报的安全能力。 */
data class WorkerHeartbeatRequest(
    /** cgroup v2 CPU 控制器是否生效。 */
    val cpuController: Boolean,
    /** cgroup v2 memory 控制器是否生效。 */
    val memoryController: Boolean,
    /** cgroup v2 pids 控制器是否生效。 */
    val pidsController: Boolean,
    /** go-judge 是否启用 no-fallback。 */
    val noFallback: Boolean,
    /** 节点可用语言。 */
    val languages: Set<JudgeLanguage>,
    /** Worker 当前启动的普通提交槽数。 */
    @field:Min(1)
    @field:Max(64)
    val judgeSlots: Int,
    /** Worker 当前启动的独立 AI 沙箱槽数。 */
    @field:Min(0)
    @field:Max(16)
    val aiSlots: Int,
)

/** Worker 进度更新请求。 */
data class WorkerProgressRequest(
    /** 当前租约尝试标识。 */
    val attemptId: UUID,
    /** 当前租约明文令牌。 */
    @field:NotBlank
    val leaseToken: String,
    /** 只允许编译中或判题中。 */
    val status: JudgeStatus,
)

/** 续租成功后的新过期时间。 */
data class RenewLeaseResponse(
    /** 新租约过期时间。 */
    val leaseExpiresAt: Instant,
)

/** 幂等结算结果。 */
data class CompleteLeaseResponse(
    /** 是否接受了本次结果。 */
    val accepted: Boolean,
    /** 任务是否因基础设施错误重新排队。 */
    val requeued: Boolean,
)

/** Worker 节点认证和凭据管理。 */
@Service
class WorkerCredentialService(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
) {
    /** 创建节点并返回仅展示一次的明文令牌。 */
    @Transactional
    fun create(request: CreateWorkerRequest): CreatedWorkerResponse {
        val id = UUID.randomUUID()
        val token = SecureValues.randomToken(48)
        jdbc.update(
            "INSERT INTO worker_node(id, name, token_hash, slots, ai_slots) VALUES (?, ?, ?, ?, ?)",
            id,
            request.name,
            SecureValues.sha256(token),
            request.slots,
            request.aiSlots,
        )
        return CreatedWorkerResponse(id, request.name, token, request.slots, request.aiSlots)
    }

    /** 使用 Authorization Bearer Token 认证节点。 */
    fun authenticate(authorization: String?): WorkerIdentity {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "WORKER_UNAUTHENTICATED", "Worker 凭据缺失")
        }
        val token = authorization.removePrefix("Bearer ").trim()
        if (token.length < 40) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "WORKER_UNAUTHENTICATED", "Worker 凭据无效")
        }
        return jdbc.query(
            "SELECT id, name, slots, ai_slots FROM worker_node WHERE token_hash = ? AND active = TRUE",
            { result, _ ->
                WorkerIdentity(
                    id = result.getObject("id", UUID::class.java),
                    name = result.getString("name"),
                    slots = result.getInt("slots"),
                    aiSlots = result.getInt("ai_slots"),
                )
            },
            SecureValues.sha256(token),
        ).firstOrNull() ?: throw ApiException(
            HttpStatus.UNAUTHORIZED,
            "WORKER_UNAUTHENTICATED",
            "Worker 凭据无效或已撤销",
        )
    }

    /** 保存节点心跳和安全预检结果。 */
    fun heartbeat(worker: WorkerIdentity, request: WorkerHeartbeatRequest) {
        val capabilities = """{"cpuController":${request.cpuController},"memoryController":${request.memoryController},"pidsController":${request.pidsController},"noFallback":${request.noFallback},"languages":[${request.languages.joinToString(",") { "\"$it\"" }}]}"""
        jdbc.update(
            "UPDATE worker_node SET capabilities = ?::jsonb, slots = ?, ai_slots = ?, last_heartbeat_at = now() WHERE id = ?",
            capabilities,
            request.judgeSlots,
            request.aiSlots,
            worker.id,
        )
    }
}

/** PostgreSQL 判题队列的实现边界。 */
interface JudgeQueue {
    /** 尝试领取一份可用任务。 */
    fun claim(worker: WorkerIdentity): JudgeLease?

    /** 更新当前提交的公开状态。 */
    fun progress(worker: WorkerIdentity, jobId: UUID, request: WorkerProgressRequest): UUID

    /** 延长当前有效租约。 */
    fun renew(worker: WorkerIdentity, jobId: UUID, attemptId: UUID, leaseToken: String): RenewLeaseResponse

    /** 结算或按基础设施重试规则重新入队。 */
    fun complete(worker: WorkerIdentity, jobId: UUID, completion: JudgeCompletion): Pair<CompleteLeaseResponse, UUID?>
}

/** 基于 PostgreSQL 行锁和租约的持久化判题队列。 */
@Service
class PostgresJudgeQueue(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
    /** 应用配置。 */
    private val properties: AppProperties,
) : JudgeQueue {
    /**
     * 领取事务只持有到租约写入为止；判题执行期间不保留数据库事务或行锁。
     */
    @Transactional
    override fun claim(worker: WorkerIdentity): JudgeLease? {
        val claimed = jdbc.query(
            """
            SELECT j.id, j.submission_id, s.execution_mode, s.language, s.source_code,
                   pv.time_limit_ms, pv.memory_limit_mib
            FROM judge_job j
            JOIN submission s ON s.id = j.submission_id
            JOIN problem_version pv ON pv.id = s.problem_version_id
            WHERE j.available_at <= now()
              AND (j.status = 'QUEUED' OR (j.status = 'LEASED' AND j.lease_expires_at < now()))
            ORDER BY j.priority DESC, j.created_at ASC
            FOR UPDATE OF j SKIP LOCKED
            LIMIT 1
            """.trimIndent(),
            { result, _ ->
                ClaimRow(
                    jobId = result.getObject("id", UUID::class.java),
                    submissionId = result.getObject("submission_id", UUID::class.java),
                    executionMode = JudgeExecutionMode.valueOf(result.getString("execution_mode")),
                    language = JudgeLanguage.valueOf(result.getString("language")),
                    sourceCode = result.getString("source_code"),
                    baseTimeLimitMs = result.getLong("time_limit_ms"),
                    baseMemoryLimitMiB = result.getLong("memory_limit_mib"),
                )
            },
        ).firstOrNull() ?: return null

        val attemptId = UUID.randomUUID()
        val leaseToken = SecureValues.randomToken(48)
        val expiresAt = Instant.now().plus(properties.worker.leaseSeconds, ChronoUnit.SECONDS)
        jdbc.update(
            """
            UPDATE judge_job
            SET status = 'LEASED', attempt_id = ?, lease_token_hash = ?, leased_by = ?, lease_expires_at = ?
            WHERE id = ?
            """.trimIndent(),
            attemptId,
            SecureValues.sha256(leaseToken),
            worker.id,
            Timestamp.from(expiresAt),
            claimed.jobId,
        )
        val multiplier = LanguageLimits.multiplier(claimed.language)
        return JudgeLease(
            jobId = claimed.jobId,
            submissionId = claimed.submissionId,
            attemptId = attemptId,
            leaseToken = leaseToken,
            leaseExpiresAt = expiresAt,
            executionMode = claimed.executionMode,
            language = claimed.language,
            sourceCode = claimed.sourceCode,
            timeLimitMs = claimed.baseTimeLimitMs * multiplier.time,
            memoryLimitMiB = claimed.baseMemoryLimitMiB * multiplier.memory,
            testCases = loadCases(claimed, attemptId, leaseToken),
        )
    }

    /** 更新编译或判题进度，旧租约不能推进状态。 */
    @Transactional
    override fun progress(worker: WorkerIdentity, jobId: UUID, request: WorkerProgressRequest): UUID {
        if (request.status !in setOf(JudgeStatus.COMPILING, JudgeStatus.JUDGING)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROGRESS_STATUS", "Worker 进度状态不合法")
        }
        val submissionId = validateLease(worker, jobId, request.attemptId, request.leaseToken, requireUnexpired = true)
        jdbc.update("UPDATE submission SET status = ? WHERE id = ?", request.status.name, submissionId)
        return submissionId
    }

    /** 续租当前未过期租约。 */
    @Transactional
    override fun renew(
        worker: WorkerIdentity,
        jobId: UUID,
        attemptId: UUID,
        leaseToken: String,
    ): RenewLeaseResponse {
        validateLease(worker, jobId, attemptId, leaseToken, requireUnexpired = true)
        val expiresAt = Instant.now().plus(properties.worker.leaseSeconds, ChronoUnit.SECONDS)
        jdbc.update("UPDATE judge_job SET lease_expires_at = ? WHERE id = ?", Timestamp.from(expiresAt), jobId)
        return RenewLeaseResponse(expiresAt)
    }

    /** 幂等结算；只接受当前 attemptId 与租约令牌。 */
    @Transactional
    override fun complete(
        worker: WorkerIdentity,
        jobId: UUID,
        completion: JudgeCompletion,
    ): Pair<CompleteLeaseResponse, UUID?> {
        val job = jdbc.query(
            """
            SELECT j.submission_id, j.status, j.attempt_id, j.lease_token_hash, j.leased_by,
                   j.infrastructure_attempts, s.execution_mode
            FROM judge_job j JOIN submission s ON s.id = j.submission_id
            WHERE j.id = ? FOR UPDATE OF j
            """.trimIndent(),
            { result, _ ->
                CompletionRow(
                    submissionId = result.getObject("submission_id", UUID::class.java),
                    status = result.getString("status"),
                    attemptId = result.getObject("attempt_id", UUID::class.java),
                    leaseTokenHash = result.getString("lease_token_hash"),
                    leasedBy = result.getObject("leased_by", UUID::class.java),
                    infrastructureAttempts = result.getInt("infrastructure_attempts"),
                    executionMode = JudgeExecutionMode.valueOf(result.getString("execution_mode")),
                )
            },
            jobId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "JUDGE_JOB_NOT_FOUND", "判题任务不存在")
        if (job.status == "COMPLETED" && job.attemptId == completion.attemptId &&
            job.leaseTokenHash != null && SecureValues.constantTimeEquals(job.leaseTokenHash, SecureValues.sha256(completion.leaseToken))) {
            return CompleteLeaseResponse(accepted = true, requeued = false) to job.submissionId
        }
        if (job.status != "LEASED" || job.leasedBy != worker.id || job.attemptId != completion.attemptId ||
            job.leaseTokenHash == null || !SecureValues.constantTimeEquals(job.leaseTokenHash, SecureValues.sha256(completion.leaseToken))) {
            throw ApiException(HttpStatus.CONFLICT, "STALE_LEASE", "租约已失效，结果未写入")
        }
        validateCompletion(job.submissionId, job.executionMode, completion)

        if (completion.status == JudgeStatus.SYSTEM_ERROR && job.infrastructureAttempts < 3) {
            jdbc.update(
                """
                UPDATE judge_job SET status = 'QUEUED', available_at = now(),
                    infrastructure_attempts = infrastructure_attempts + 1,
                    attempt_id = NULL, lease_token_hash = NULL, leased_by = NULL, lease_expires_at = NULL
                WHERE id = ?
                """.trimIndent(),
                jobId,
            )
            jdbc.update("UPDATE submission SET status = 'QUEUED' WHERE id = ?", job.submissionId)
            return CompleteLeaseResponse(accepted = true, requeued = true) to job.submissionId
        }

        if (job.executionMode == JudgeExecutionMode.RUN) {
            saveRunResults(job.submissionId, completion)
        } else {
            saveSubmissionResults(job.submissionId, completion)
        }
        jdbc.update(
            "UPDATE submission SET status = ?, score = ?, compile_message = ?, finished_at = now() WHERE id = ?",
            completion.status.name,
            completion.score,
            completion.compileMessage?.take(16_384),
            job.submissionId,
        )
        jdbc.update("UPDATE judge_job SET status = 'COMPLETED', completed_at = now() WHERE id = ?", jobId)
        if (job.executionMode == JudgeExecutionMode.SUBMIT) {
            updateWrongBook(job.submissionId, completion.score)
        }
        return CompleteLeaseResponse(accepted = true, requeued = false) to job.submissionId
    }

    /** 验证当前租约归属和令牌。 */
    private fun validateLease(
        worker: WorkerIdentity,
        jobId: UUID,
        attemptId: UUID,
        leaseToken: String,
        requireUnexpired: Boolean,
    ): UUID {
        val row = jdbc.query(
            """
            SELECT submission_id, attempt_id, lease_token_hash, leased_by, lease_expires_at
            FROM judge_job WHERE id = ? AND status = 'LEASED' FOR UPDATE
            """.trimIndent(),
            { result, _ ->
                LeaseValidationRow(
                    submissionId = result.getObject("submission_id", UUID::class.java),
                    attemptId = result.getObject("attempt_id", UUID::class.java),
                    leaseTokenHash = result.getString("lease_token_hash"),
                    leasedBy = result.getObject("leased_by", UUID::class.java),
                    expiresAt = result.getTimestamp("lease_expires_at").toInstant(),
                )
            },
            jobId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.CONFLICT, "STALE_LEASE", "租约已失效")
        val tokenMatches = SecureValues.constantTimeEquals(row.leaseTokenHash, SecureValues.sha256(leaseToken))
        if (row.attemptId != attemptId || row.leasedBy != worker.id || !tokenMatches ||
            (requireUnexpired && !row.expiresAt.isAfter(Instant.now()))) {
            throw ApiException(HttpStatus.CONFLICT, "STALE_LEASE", "租约已失效")
        }
        return row.submissionId
    }

    /** 根据执行模式构建隐藏测试制品租约或公开内联输入。 */
    private fun loadCases(claimed: ClaimRow, attemptId: UUID, leaseToken: String): List<JudgeCaseLease> {
        if (claimed.executionMode == JudgeExecutionMode.RUN) {
            return jdbc.query(
                "SELECT id, ordinal, input_text FROM submission_run_case WHERE submission_id = ? ORDER BY ordinal",
                { result, _ ->
                    JudgeCaseLease(
                        caseId = result.getObject("id", UUID::class.java),
                        ordinal = result.getInt("ordinal"),
                        score = 0,
                        inlineInput = result.getString("input_text"),
                    )
                },
                claimed.submissionId,
            )
        }
        val jobId = claimed.jobId
        return jdbc.query(
        """
        SELECT tc.id, tc.ordinal, tc.score,
               ia.id AS input_id, ia.sha256 AS input_sha,
               oa.id AS output_id, oa.sha256 AS output_sha
        FROM judge_job j
        JOIN submission s ON s.id = j.submission_id
        JOIN problem_test_case tc ON tc.problem_version_id = s.problem_version_id
        JOIN artifact ia ON ia.id = tc.input_artifact_id
        JOIN artifact oa ON oa.id = tc.output_artifact_id
        WHERE j.id = ? ORDER BY tc.ordinal
        """.trimIndent(),
        { result, _ ->
            // 隐藏制品下载地址必须从 Worker 可达的部署配置生成，不能假定为 localhost。
            val base = properties.workerArtifactBaseUrl.trimEnd('/') + "/internal/worker/v1/jobs/$jobId/artifacts"
            val query = "attemptId=$attemptId&leaseToken=${java.net.URLEncoder.encode(leaseToken, Charsets.UTF_8)}"
            JudgeCaseLease(
                caseId = result.getObject("id", UUID::class.java),
                ordinal = result.getInt("ordinal"),
                score = result.getInt("score"),
                inputUrl = "$base/${result.getObject("input_id", UUID::class.java)}?$query",
                inputSha256 = result.getString("input_sha"),
                expectedOutputUrl = "$base/${result.getObject("output_id", UUID::class.java)}?$query",
                expectedOutputSha256 = result.getString("output_sha"),
            )
        },
        jobId,
        )
    }

    /** 检查 Worker 结果不伪造测试点、不越界计分。 */
    private fun validateCompletion(
        submissionId: UUID,
        executionMode: JudgeExecutionMode,
        completion: JudgeCompletion,
    ) {
        if (completion.score !in 0..100) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "提交得分不合法")
        }
        val terminal = JudgeStatus.entries - setOf(JudgeStatus.QUEUED, JudgeStatus.COMPILING, JudgeStatus.JUDGING)
        if (completion.status !in terminal) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "提交终态不合法")
        }
        if (completion.testCases.any { it.score !in 0..100 || it.timeMs < 0 || it.memoryKiB < 0 }) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "测点结果不合法")
        }
        if (completion.status in setOf(JudgeStatus.CE, JudgeStatus.SYSTEM_ERROR, JudgeStatus.CANCELED)) {
            if (completion.score != 0 || completion.testCases.isNotEmpty()) {
                throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "未执行测点的结果必须为零分")
            }
            return
        }
        if (executionMode == JudgeExecutionMode.RUN) {
            validateRunCompletion(submissionId, completion)
            return
        }
        if (completion.testCases.any { it.actualOutput != null }) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "正式提交不能返回实际输出")
        }
        val expectedScores = jdbc.query(
            """
            SELECT tc.id, tc.score
            FROM submission s
            JOIN problem_test_case tc ON tc.problem_version_id = s.problem_version_id
            WHERE s.id = ?
            """.trimIndent(),
            { result, _ -> result.getObject("id", UUID::class.java) to result.getInt("score") },
            submissionId,
        ).toMap()
        val reportedIds = completion.testCases.map { it.caseId }
        if (reportedIds.size != reportedIds.distinct().size || reportedIds.toSet() != expectedScores.keys) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "测点结果与题目版本不一致")
        }
        completion.testCases.forEach { result ->
            val maximum = expectedScores.getValue(result.caseId)
            if (result.score !in 0..maximum) {
                throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "测点得分超过配置分值")
            }
            if ((result.status == JudgeStatus.AC && result.score != maximum) ||
                (result.status != JudgeStatus.AC && result.score != 0)) {
                throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "测点状态与得分不一致")
            }
        }
        if (completion.testCases.sumOf { it.score } != completion.score) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "逐点得分与提交总分不一致")
        }
        val expectedStatus = when {
            completion.score == 100 -> JudgeStatus.AC
            completion.score > 0 -> JudgeStatus.PARTIAL
            completion.status in setOf(
                JudgeStatus.WA,
                JudgeStatus.TLE,
                JudgeStatus.MLE,
                JudgeStatus.RE,
                JudgeStatus.OLE,
            ) -> completion.status
            else -> null
        }
        if (expectedStatus == null || completion.status != expectedStatus) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "提交状态与得分不一致")
        }
    }

    /** 校验公开运行结果只对应用户输入，且不携带分值。 */
    private fun validateRunCompletion(submissionId: UUID, completion: JudgeCompletion) {
        val expectedIds = jdbc.query(
            "SELECT id FROM submission_run_case WHERE submission_id = ?",
            { result, _ -> result.getObject("id", UUID::class.java) },
            submissionId,
        ).toSet()
        val reportedIds = completion.testCases.map { it.caseId }
        if (reportedIds.size != reportedIds.distinct().size || reportedIds.toSet() != expectedIds) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "公开运行结果与输入不一致")
        }
        if (completion.score != 0 || completion.testCases.any { it.score != 0 }) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "公开运行不能产生分值")
        }
        if (completion.testCases.any { (it.actualOutput ?: "").toByteArray(Charsets.UTF_8).size > 16 * 1024 * 1024 }) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "公开运行输出超过限制")
        }
        val expectedStatus = if (completion.testCases.all { it.status == JudgeStatus.AC }) {
            JudgeStatus.AC
        } else {
            completion.testCases.first { it.status != JudgeStatus.AC }.status
        }
        if (completion.status != expectedStatus) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_JUDGE_RESULT", "公开运行状态与用例结果不一致")
        }
    }

    /** 保存正式提交的脱敏逐点结果。 */
    private fun saveSubmissionResults(submissionId: UUID, completion: JudgeCompletion) {
        completion.testCases.forEach { result ->
            jdbc.update(
                """
                INSERT INTO submission_case_result(
                    id, submission_id, test_case_id, status, score, time_ms, memory_kib, message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(submission_id, test_case_id) DO UPDATE SET
                    status = EXCLUDED.status, score = EXCLUDED.score, time_ms = EXCLUDED.time_ms,
                    memory_kib = EXCLUDED.memory_kib, message = EXCLUDED.message
                """.trimIndent(),
                UUID.randomUUID(),
                submissionId,
                result.caseId,
                result.status.name,
                result.score,
                result.timeMs,
                result.memoryKiB,
                result.message?.take(500),
            )
        }
    }

    /** 保存公开运行的实际输出；编译失败时把所有用例同步标记为失败。 */
    private fun saveRunResults(submissionId: UUID, completion: JudgeCompletion) {
        if (completion.testCases.isEmpty()) {
            jdbc.update(
                "UPDATE submission_run_case SET status = ?, message = ? WHERE submission_id = ?",
                completion.status.name,
                completion.compileMessage?.take(500) ?: completion.systemMessage?.take(500),
                submissionId,
            )
            return
        }
        completion.testCases.forEach { result ->
            jdbc.update(
                """
                UPDATE submission_run_case
                SET status = ?, actual_output = ?, time_ms = ?, memory_kib = ?, message = ?
                WHERE id = ? AND submission_id = ?
                """.trimIndent(),
                result.status.name,
                result.actualOutput,
                result.timeMs,
                result.memoryKiB,
                result.message?.take(500),
                result.caseId,
                submissionId,
            )
        }
    }

    /** 按最终得分维护错题本，保留首次错误记录。 */
    private fun updateWrongBook(submissionId: UUID, score: Int) {
        if (score == 100) {
            jdbc.update(
                """
                UPDATE wrong_problem wp SET best_score = 100, solved_at = coalesce(wp.solved_at, now())
                FROM submission s
                JOIN problem_version pv ON pv.id = s.problem_version_id
                WHERE s.id = ? AND wp.user_id = s.user_id AND wp.problem_id = pv.problem_id
                """.trimIndent(),
                submissionId,
            )
            return
        }
        jdbc.update(
            """
            INSERT INTO wrong_problem(user_id, problem_id, best_score)
            SELECT s.user_id, pv.problem_id, ?
            FROM submission s JOIN problem_version pv ON pv.id = s.problem_version_id WHERE s.id = ?
            ON CONFLICT(user_id, problem_id) DO UPDATE SET
                best_score = greatest(wrong_problem.best_score, EXCLUDED.best_score),
                last_wrong_at = now(),
                solved_at = NULL
            """.trimIndent(),
            score,
            submissionId,
        )
    }

    /** 领取查询的最小投影。 */
    private data class ClaimRow(
        val jobId: UUID,
        val submissionId: UUID,
        val executionMode: JudgeExecutionMode,
        val language: JudgeLanguage,
        val sourceCode: String,
        val baseTimeLimitMs: Long,
        val baseMemoryLimitMiB: Long,
    )

    /** 结算查询的最小投影。 */
    private data class CompletionRow(
        val submissionId: UUID,
        val status: String,
        val attemptId: UUID?,
        val leaseTokenHash: String?,
        val leasedBy: UUID?,
        val infrastructureAttempts: Int,
        val executionMode: JudgeExecutionMode,
    )

    /** 租约校验查询的最小投影。 */
    private data class LeaseValidationRow(
        val submissionId: UUID,
        val attemptId: UUID,
        val leaseTokenHash: String,
        val leasedBy: UUID,
        val expiresAt: Instant,
    )
}

/** 管理员 Worker 凭据接口。 */
@RestController
@RequestMapping("/api/v1/admin/workers")
@PreAuthorize("hasRole('ADMIN')")
class AdminWorkerController(
    /** Worker 凭据服务。 */
    private val credentials: WorkerCredentialService,
) {
    /** 创建节点并返回一次性明文 Token。 */
    @PostMapping
    fun create(@Valid @RequestBody body: CreateWorkerRequest): CreatedWorkerResponse = credentials.create(body)
}

/** Worker 内部协议控制器。 */
@RestController
@RequestMapping("/internal/worker/v1")
class WorkerController(
    /** Worker 凭据服务。 */
    private val credentials: WorkerCredentialService,
    /** PostgreSQL 持久化队列。 */
    private val queue: JudgeQueue,
    /** 制品元数据访问。 */
    private val jdbc: JdbcTemplate,
    /** 文件制品存储。 */
    private val artifactStore: ArtifactStore,
    /** SSE 状态发布器。 */
    private val submissionEvents: SubmissionEventService,
    /** 应用配置。 */
    private val properties: AppProperties,
) {
    /** 上报节点安全能力和存活状态。 */
    @PostMapping("/heartbeat")
    fun heartbeat(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @Valid @RequestBody body: WorkerHeartbeatRequest,
    ): Map<String, Any> {
        val worker = credentials.authenticate(authorization)
        credentials.heartbeat(worker, body)
        return mapOf(
            "accepted" to true,
            "heartbeatSeconds" to properties.worker.heartbeatSeconds,
            "leaseSeconds" to properties.worker.leaseSeconds,
        )
    }

    /** 10 到 20 秒长轮询领取一份任务，无任务时返回 204。 */
    @PostMapping("/jobs/claim")
    fun claim(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): ResponseEntity<JudgeLease> {
        val worker = credentials.authenticate(authorization)
        val deadline = System.nanoTime() + properties.worker.longPollSeconds * 1_000_000_000L
        do {
            val lease = queue.claim(worker)
            if (lease != null) return ResponseEntity.ok(lease)
            Thread.sleep(500)
        } while (System.nanoTime() < deadline)
        return ResponseEntity.noContent().build()
    }

    /** 推送编译中或判题中状态。 */
    @PostMapping("/jobs/{jobId}/progress")
    fun progress(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable jobId: UUID,
        @Valid @RequestBody body: WorkerProgressRequest,
    ): ResponseEntity<Void> {
        val worker = credentials.authenticate(authorization)
        val submissionId = queue.progress(worker, jobId, body)
        submissionEvents.publish(submissionId)
        return ResponseEntity.noContent().build()
    }

    /** 续租当前任务。 */
    @PostMapping("/jobs/{jobId}/renew")
    fun renew(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable jobId: UUID,
        @RequestParam attemptId: UUID,
        @RequestParam leaseToken: String,
    ): RenewLeaseResponse = queue.renew(credentials.authenticate(authorization), jobId, attemptId, leaseToken)

    /** 幂等结算当前任务。 */
    @PostMapping("/jobs/{jobId}/complete")
    fun complete(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable jobId: UUID,
        @Valid @RequestBody body: JudgeCompletion,
    ): CompleteLeaseResponse {
        val result = queue.complete(credentials.authenticate(authorization), jobId, body)
        result.second?.let(submissionEvents::publish)
        return result.first
    }

    /** 下载仅属于当前租约题目版本的输入或标准输出制品。 */
    @GetMapping("/jobs/{jobId}/artifacts/{artifactId}")
    fun artifact(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable jobId: UUID,
        @PathVariable artifactId: UUID,
        @RequestParam attemptId: UUID,
        @RequestParam leaseToken: String,
    ): ResponseEntity<InputStreamResource> {
        val worker = credentials.authenticate(authorization)
        queue.renew(worker, jobId, attemptId, leaseToken)
        val artifact = jdbc.query(
            """
            SELECT a.storage_key, a.size_bytes, a.media_type
            FROM judge_job j
            JOIN submission s ON s.id = j.submission_id
            JOIN problem_test_case tc ON tc.problem_version_id = s.problem_version_id
            JOIN artifact a ON a.id IN (tc.input_artifact_id, tc.output_artifact_id)
            WHERE j.id = ? AND a.id = ?
            LIMIT 1
            """.trimIndent(),
            { result, _ -> Triple(result.getString("storage_key"), result.getLong("size_bytes"), result.getString("media_type")) },
            jobId,
            artifactId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "租约制品不存在")
        return ResponseEntity.ok()
            .contentLength(artifact.second)
            .contentType(MediaType.parseMediaType(artifact.third))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(InputStreamResource(artifactStore.open(artifact.first)))
    }
}
