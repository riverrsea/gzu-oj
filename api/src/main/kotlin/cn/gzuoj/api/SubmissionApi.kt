package cn.gzuoj.api

import cn.gzuoj.shared.JudgeExecutionMode
import cn.gzuoj.shared.JudgeLanguage
import cn.gzuoj.shared.JudgePriority
import cn.gzuoj.shared.JudgeStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 创建判题提交的请求。 */
data class CreateSubmissionRequest(
    /** 稳定题目标识；用于校验版本归属。 */
    val problemId: UUID,
    /** 做题页面已经锁定的题目版本；普通练习也必须显式绑定。 */
    val problemVersionId: UUID,
    /** 编程语言。 */
    val language: JudgeLanguage,
    /** 用户源代码。 */
    @field:NotBlank(message = "源代码不能为空")
    @field:Size(max = 131_072, message = "源代码不能超过 128 KiB")
    val sourceCode: String,
    /** 可选公开训练赛标识。 */
    val contestId: UUID? = null,
    /** 可选个人计时作答标识。 */
    val timedPaperAttemptId: UUID? = null,
)

/** 使用用户可编辑公开输入运行代码的请求。 */
data class CreateRunRequest(
    /** 稳定题目标识；用于校验版本归属。 */
    val problemId: UUID,
    /** 做题页面已经锁定的已发布版本或历史版本。 */
    val problemVersionId: UUID,
    /** 编程语言。 */
    val language: JudgeLanguage,
    /** 用户源代码。 */
    @field:NotBlank(message = "源代码不能为空")
    @field:Size(max = 131_072, message = "源代码不能超过 128 KiB")
    val sourceCode: String,
    /** 用户可编辑公开输入，最多八组。 */
    @field:Size(min = 1, max = 8, message = "公开运行需要 1 到 8 组输入")
    val inputs: List<@Size(max = 262_144, message = "单组公开输入不能超过 256 KiB") String>,
)

/** 脱敏的逐点判题结果。 */
data class SubmissionCaseResponse(
    /** 测试点序号。 */
    val ordinal: Int,
    /** 判题状态。 */
    val status: JudgeStatus,
    /** 测试点得分。 */
    val score: Int,
    /** CPU 时间，单位毫秒。 */
    val timeMs: Long,
    /** 峰值内存，单位 KiB。 */
    val memoryKiB: Long,
    /** 对用户安全的简短说明。 */
    val message: String?,
    /** 公开运行时的用户输入；正式提交时为空。 */
    val input: String? = null,
    /** 公开运行时的实际标准输出；正式提交时为空。 */
    val actualOutput: String? = null,
)

/** 用户可见的提交状态。 */
data class SubmissionResponse(
    /** 提交标识。 */
    val id: UUID,
    /** 稳定题目标识。 */
    val problemId: UUID,
    /** 锁定的题目版本标识。 */
    val problemVersionId: UUID,
    /** 正式提交或公开运行。 */
    val executionMode: JudgeExecutionMode,
    /** 编程语言。 */
    val language: JudgeLanguage,
    /** 当前状态。 */
    val status: JudgeStatus,
    /** 当前或最终得分。 */
    val score: Int,
    /** 编译失败时的编译器信息。 */
    val compileMessage: String?,
    /** 提交时间。 */
    val createdAt: Instant,
    /** 完成时间。 */
    val finishedAt: Instant?,
    /** 不包含任何隐藏输入输出的测点结果。 */
    val testCases: List<SubmissionCaseResponse>,
)

/** 提交与持久化队列事务服务。 */
@Service
class SubmissionService(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
) {
    /**
     * 创建提交与队列任务。相同用户和幂等键只产生一份提交；
     * 相同幂等键对应不同请求时拒绝复用。
     */
    @Transactional
    fun create(request: CreateSubmissionRequest, idempotencyKey: String, userId: UUID): SubmissionResponse {
        if (idempotencyKey.length !in 8..128) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key 长度需为 8 到 128 位")
        }
        if (request.sourceCode.toByteArray(Charsets.UTF_8).size > 131_072) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "SOURCE_TOO_LARGE", "源代码不能超过 128 KiB")
        }
        if (request.contestId != null && request.timedPaperAttemptId != null) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SUBMISSION_CONTEXT", "比赛与个人计时上下文不能同时指定")
        }

        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, ?))",
            { _, _ -> Unit },
            idempotencyKey,
            userId.mostSignificantBits,
        )
        val requestHash = requestHash(request)
        val existing = jdbc.query(
            "SELECT request_hash, submission_id FROM idempotency_record WHERE user_id = ? AND idempotency_key = ?",
            { result, _ -> result.getString("request_hash") to result.getObject("submission_id", UUID::class.java) },
            userId,
            idempotencyKey,
        ).firstOrNull()
        if (existing != null) {
            if (!SecureValues.constantTimeEquals(existing.first, requestHash)) {
                throw ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "该 Idempotency-Key 已用于其他请求")
            }
            return get(existing.second, userId, false)
        }

        val versionId = resolveSubmissionVersion(request, userId)
        validateContext(request, userId, versionId)

        val submissionId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO submission(
                id, user_id, problem_version_id, language, source_code, contest_id, timed_paper_attempt_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            submissionId,
            userId,
            versionId,
            request.language.name,
            request.sourceCode,
            request.contestId,
            request.timedPaperAttemptId,
        )
        val priority = if (request.contestId != null) JudgePriority.PUBLIC_CONTEST else JudgePriority.PRACTICE
        jdbc.update(
            "INSERT INTO judge_job(id, submission_id, priority) VALUES (?, ?, ?)",
            UUID.randomUUID(),
            submissionId,
            priority,
        )
        jdbc.update(
            "INSERT INTO idempotency_record(user_id, idempotency_key, request_hash, submission_id) VALUES (?, ?, ?, ?)",
            userId,
            idempotencyKey,
            requestHash,
            submissionId,
        )
        return get(submissionId, userId, false)
    }

    /** 创建公开运行任务；只执行用户提供的输入，不读取隐藏测试点。 */
    @Transactional
    fun createRun(request: CreateRunRequest, idempotencyKey: String, userId: UUID): SubmissionResponse {
        validateIdempotencyKey(idempotencyKey)
        validateSourceSize(request.sourceCode)
        val totalInputBytes = request.inputs.sumOf { it.toByteArray(Charsets.UTF_8).size }
        if (totalInputBytes > 1_048_576) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "RUN_INPUT_TOO_LARGE", "公开运行输入合计不能超过 1 MiB")
        }
        val storedKey = "run:" + SecureValues.sha256(idempotencyKey)
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, ?))",
            { _, _ -> Unit },
            storedKey,
            userId.mostSignificantBits,
        )
        val requestHash = SecureValues.sha256(
            listOf(
                request.problemId,
                request.problemVersionId,
                request.language,
                SecureValues.sha256(request.sourceCode),
                request.inputs.joinToString("") { SecureValues.sha256(it) },
            ).joinToString(""),
        )
        val existing = jdbc.query(
            "SELECT request_hash, submission_id FROM idempotency_record WHERE user_id = ? AND idempotency_key = ?",
            { result, _ -> result.getString("request_hash") to result.getObject("submission_id", UUID::class.java) },
            userId,
            storedKey,
        ).firstOrNull()
        if (existing != null) {
            if (!SecureValues.constantTimeEquals(existing.first, requestHash)) {
                throw ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "该 Idempotency-Key 已用于其他运行请求")
            }
            return get(existing.second, userId, false)
        }
        val versionId = jdbc.query(
            "SELECT id FROM problem_version WHERE id = ? AND problem_id = ? AND status IN ('PUBLISHED', 'WITHDRAWN')",
            { result, _ -> result.getObject("id", UUID::class.java) },
            request.problemVersionId,
            request.problemId,
        ).firstOrNull() ?: throw ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_PROBLEM_VERSION",
            "公开运行版本不属于该题或尚未发布",
        )
        val submissionId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO submission(id, user_id, problem_version_id, language, source_code, execution_mode)
            VALUES (?, ?, ?, ?, ?, 'RUN')
            """.trimIndent(),
            submissionId,
            userId,
            versionId,
            request.language.name,
            request.sourceCode,
        )
        request.inputs.forEachIndexed { index, input ->
            jdbc.update(
                "INSERT INTO submission_run_case(id, submission_id, ordinal, input_text) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                submissionId,
                index + 1,
                input,
            )
        }
        jdbc.update(
            "INSERT INTO judge_job(id, submission_id, priority) VALUES (?, ?, ?)",
            UUID.randomUUID(),
            submissionId,
            JudgePriority.PRACTICE,
        )
        jdbc.update(
            "INSERT INTO idempotency_record(user_id, idempotency_key, request_hash, submission_id) VALUES (?, ?, ?, ?)",
            userId,
            storedKey,
            requestHash,
            submissionId,
        )
        return get(submissionId, userId, false)
    }

    /** 读取一份提交；管理员可读取任意提交，普通用户只能读取自己的提交。 */
    fun get(submissionId: UUID, userId: UUID, admin: Boolean): SubmissionResponse {
        val sql = buildString {
            append(
                """
                SELECT s.id, p.id AS problem_id, s.problem_version_id, s.execution_mode,
                       s.language, s.status, s.score,
                       s.compile_message, s.created_at, s.finished_at
                FROM submission s
                JOIN problem_version pv ON pv.id = s.problem_version_id
                JOIN problem p ON p.id = pv.problem_id
                WHERE s.id = ?
                """.trimIndent(),
            )
            if (!admin) append(" AND s.user_id = ?")
        }
        val args = if (admin) arrayOf<Any>(submissionId) else arrayOf<Any>(submissionId, userId)
        val submission = jdbc.query(sql, { result, _ ->
            SubmissionResponse(
                id = result.getObject("id", UUID::class.java),
                problemId = result.getObject("problem_id", UUID::class.java),
                problemVersionId = result.getObject("problem_version_id", UUID::class.java),
                executionMode = JudgeExecutionMode.valueOf(result.getString("execution_mode")),
                language = JudgeLanguage.valueOf(result.getString("language")),
                status = JudgeStatus.valueOf(result.getString("status")),
                score = result.getInt("score"),
                compileMessage = result.getString("compile_message"),
                createdAt = result.getTimestamp("created_at").toInstant(),
                finishedAt = result.getTimestamp("finished_at")?.toInstant(),
                testCases = emptyList(),
            )
        }, *args).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "提交不存在")
        val cases = if (submission.executionMode == JudgeExecutionMode.RUN) {
            loadRunCaseResults(submissionId)
        } else {
            loadCaseResults(submissionId)
        }
        return submission.copy(testCases = cases)
    }

    /** 分页读取当前用户的提交历史。 */
    fun history(userId: UUID, limit: Int, before: Instant?): List<SubmissionResponse> {
        val safeLimit = limit.coerceIn(1, 100)
        val ids = if (before == null) {
            jdbc.query(
                "SELECT id FROM submission WHERE user_id = ? AND execution_mode = 'SUBMIT' ORDER BY created_at DESC, id DESC LIMIT ?",
                { result, _ -> result.getObject("id", UUID::class.java) },
                userId,
                safeLimit,
            )
        } else {
            jdbc.query(
                "SELECT id FROM submission WHERE user_id = ? AND execution_mode = 'SUBMIT' AND created_at < ? ORDER BY created_at DESC, id DESC LIMIT ?",
                { result, _ -> result.getObject("id", UUID::class.java) },
                userId,
                Timestamp.from(before),
                safeLimit,
            )
        }
        return ids.map { get(it, userId, false) }
    }

    /** 校验比赛或个人计时上下文确实属于当前用户并锁定相同版本。 */
    private fun validateContext(request: CreateSubmissionRequest, userId: UUID, versionId: UUID) {
        if (request.contestId != null) {
            val valid = jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM contest_participant cp
                    JOIN contest c ON c.id = cp.contest_id
                    JOIN contest_problem p ON p.contest_id = c.id
                    WHERE cp.contest_id = ? AND cp.user_id = ? AND p.problem_version_id = ?
                      AND now() BETWEEN c.starts_at AND c.starts_at + make_interval(mins => c.duration_minutes)
                )
                """.trimIndent(),
                Boolean::class.java,
                request.contestId,
                userId,
                versionId,
            ) ?: false
            if (!valid) throw ApiException(HttpStatus.FORBIDDEN, "CONTEST_SUBMISSION_FORBIDDEN", "当前无法向该比赛提交")
        }
        if (request.timedPaperAttemptId != null) {
            val valid = jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM timed_paper_attempt a
                    JOIN timed_paper t ON t.id = a.timed_paper_id
                    JOIN timed_paper_problem p ON p.timed_paper_id = t.id
                    WHERE a.id = ? AND a.user_id = ? AND p.problem_version_id = ?
                      AND a.finished_at IS NULL
                      AND now() <= a.started_at + make_interval(mins => t.duration_minutes)
                )
                """.trimIndent(),
                Boolean::class.java,
                request.timedPaperAttemptId,
                userId,
                versionId,
            ) ?: false
            if (!valid) throw ApiException(HttpStatus.FORBIDDEN, "TIMED_PAPER_SUBMISSION_FORBIDDEN", "当前无法向该套卷提交")
        }
    }

    /** 按比赛、套卷或普通练习上下文选择不可变题目版本。 */
    private fun resolveSubmissionVersion(request: CreateSubmissionRequest, userId: UUID): UUID {
        if (request.contestId != null) {
            val lockedVersion = jdbc.query(
                """
                SELECT cp.problem_version_id
                FROM contest_participant participant
                JOIN contest_problem cp ON cp.contest_id = participant.contest_id
                JOIN problem_version pv ON pv.id = cp.problem_version_id
                WHERE participant.contest_id = ? AND participant.user_id = ? AND pv.problem_id = ?
                """.trimIndent(),
                { result, _ -> result.getObject("problem_version_id", UUID::class.java) },
                request.contestId,
                userId,
                request.problemId,
            ).firstOrNull() ?: throw ApiException(HttpStatus.FORBIDDEN, "CONTEST_SUBMISSION_FORBIDDEN", "比赛未包含该题或用户未加入")
            if (request.problemVersionId != lockedVersion) {
                throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROBLEM_VERSION", "提交版本与比赛锁定版本不一致")
            }
            return lockedVersion
        }
        if (request.timedPaperAttemptId != null) {
            val lockedVersion = jdbc.query(
                """
                SELECT tpp.problem_version_id
                FROM timed_paper_attempt attempt
                JOIN timed_paper_problem tpp ON tpp.timed_paper_id = attempt.timed_paper_id
                JOIN problem_version pv ON pv.id = tpp.problem_version_id
                WHERE attempt.id = ? AND attempt.user_id = ? AND pv.problem_id = ?
                """.trimIndent(),
                { result, _ -> result.getObject("problem_version_id", UUID::class.java) },
                request.timedPaperAttemptId,
                userId,
                request.problemId,
            ).firstOrNull() ?: throw ApiException(HttpStatus.FORBIDDEN, "TIMED_PAPER_SUBMISSION_FORBIDDEN", "套卷未包含该题或作答不属于当前用户")
            if (request.problemVersionId != lockedVersion) {
                throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROBLEM_VERSION", "提交版本与套卷锁定版本不一致")
            }
            return lockedVersion
        }
        return jdbc.query(
            "SELECT id FROM problem_version WHERE id = ? AND problem_id = ? AND status IN ('PUBLISHED', 'WITHDRAWN')",
            { result, _ -> result.getObject("id", UUID::class.java) },
            request.problemVersionId,
            request.problemId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROBLEM_VERSION", "题目版本不属于该题或尚未发布")
    }

    /** 读取脱敏测点结果。 */
    private fun loadCaseResults(submissionId: UUID): List<SubmissionCaseResponse> = jdbc.query(
        """
        SELECT tc.ordinal, r.status, r.score, r.time_ms, r.memory_kib, r.message
        FROM submission_case_result r
        JOIN problem_test_case tc ON tc.id = r.test_case_id
        WHERE r.submission_id = ? ORDER BY tc.ordinal
        """.trimIndent(),
        { result, _ ->
            SubmissionCaseResponse(
                ordinal = result.getInt("ordinal"),
                status = JudgeStatus.valueOf(result.getString("status")),
                score = result.getInt("score"),
                timeMs = result.getLong("time_ms"),
                memoryKiB = result.getLong("memory_kib"),
                message = result.getString("message"),
            )
        },
        submissionId,
    )

    /** 读取公开运行输入和沙箱实际输出。 */
    private fun loadRunCaseResults(submissionId: UUID): List<SubmissionCaseResponse> = jdbc.query(
        """
        SELECT ordinal, status, time_ms, memory_kib, message, input_text, actual_output
        FROM submission_run_case WHERE submission_id = ? ORDER BY ordinal
        """.trimIndent(),
        { result, _ ->
            SubmissionCaseResponse(
                ordinal = result.getInt("ordinal"),
                status = JudgeStatus.valueOf(result.getString("status")),
                score = 0,
                timeMs = result.getLong("time_ms"),
                memoryKiB = result.getLong("memory_kib"),
                message = result.getString("message"),
                input = result.getString("input_text"),
                actualOutput = result.getString("actual_output"),
            )
        },
        submissionId,
    )

    /** 校验幂等键格式。 */
    private fun validateIdempotencyKey(idempotencyKey: String) {
        if (idempotencyKey.length !in 8..128) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key 长度需为 8 到 128 位")
        }
    }

    /** 按 UTF-8 字节数校验源码上限。 */
    private fun validateSourceSize(sourceCode: String) {
        if (sourceCode.toByteArray(Charsets.UTF_8).size > 131_072) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "SOURCE_TOO_LARGE", "源代码不能超过 128 KiB")
        }
    }

    /** 生成幂等请求哈希。 */
    private fun requestHash(request: CreateSubmissionRequest): String = SecureValues.sha256(
        listOf(
            request.problemId,
            request.problemVersionId,
            request.language,
            request.contestId ?: "",
            request.timedPaperAttemptId ?: "",
            SecureValues.sha256(request.sourceCode),
        ).joinToString(""),
    )
}

/** 用户公开用例运行接口。 */
@RestController
@RequestMapping("/api/v1/runs")
class RunController(
    /** 提交与运行事务服务。 */
    private val service: SubmissionService,
) {
    /** 创建幂等公开运行任务。 */
    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateRunRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): SubmissionResponse = service.createRun(body, idempotencyKey, principal.userId)
}

/** 提交状态 SSE 连接管理器。 */
@Service
class SubmissionEventService(
    /** 提交查询服务。 */
    private val submissions: SubmissionService,
) {
    /** 活跃 SSE 连接。 */
    private val emitters = ConcurrentHashMap<UUID, MutableSet<OwnedEmitter>>()

    /** 为用户注册指定提交的状态流。 */
    fun subscribe(submissionId: UUID, principal: AppPrincipal): SseEmitter {
        val initial = submissions.get(submissionId, principal.userId, principal.role == "ADMIN")
        val emitter = SseEmitter(15 * 60 * 1000L)
        val owned = OwnedEmitter(principal.userId, principal.role == "ADMIN", emitter, initial.status)
        emitters.computeIfAbsent(submissionId) { ConcurrentHashMap.newKeySet() }.add(owned)
        fun remove() { emitters[submissionId]?.remove(owned) }
        emitter.onCompletion(::remove)
        emitter.onTimeout { remove(); emitter.complete() }
        emitter.onError { remove() }
        emitter.send(SseEmitter.event().name("submission").data(initial))
        if (initial.status.isTerminal()) {
            remove()
            emitter.complete()
        }
        return emitter
    }

    /** 由 Worker 进度与结算事务主动触发状态刷新。 */
    fun publish(submissionId: UUID) {
        val active = emitters[submissionId]?.toList() ?: return
        for (owned in active) {
            try {
                val state = submissions.get(submissionId, owned.userId, owned.admin)
                owned.emitter.send(SseEmitter.event().name("submission").data(state))
                owned.lastStatus = state.status
                if (state.status.isTerminal()) owned.emitter.complete()
            } catch (_: Exception) {
                owned.emitter.completeWithError(IllegalStateException("提交状态流已关闭"))
            }
        }
    }

    /** 单个用户拥有的状态连接。 */
    private data class OwnedEmitter(
        /** 用户标识。 */
        val userId: UUID,
        /** 是否管理员。 */
        val admin: Boolean,
        /** MVC SSE 发送器。 */
        val emitter: SseEmitter,
        /** 最近发送状态。 */
        var lastStatus: JudgeStatus,
    )

    /** 判断状态是否为终态。 */
    private fun JudgeStatus.isTerminal(): Boolean = this !in setOf(
        JudgeStatus.QUEUED,
        JudgeStatus.COMPILING,
        JudgeStatus.JUDGING,
    )
}

/** 用户提交接口。 */
@RestController
@RequestMapping("/api/v1/submissions")
class SubmissionController(
    /** 提交事务服务。 */
    private val service: SubmissionService,
    /** SSE 连接服务。 */
    private val events: SubmissionEventService,
) {
    /** 创建幂等判题提交。 */
    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateSubmissionRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): SubmissionResponse = service.create(body, idempotencyKey, principal.userId)

    /** 轮询一份提交状态。 */
    @GetMapping("/{submissionId}")
    fun get(
        @PathVariable submissionId: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): SubmissionResponse = service.get(submissionId, principal.userId, principal.role == "ADMIN")

    /** 订阅一份提交的实时状态。 */
    @GetMapping("/{submissionId}/events", produces = ["text/event-stream"])
    fun events(
        @PathVariable submissionId: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): SseEmitter = events.subscribe(submissionId, principal)

    /** 获取当前用户的提交历史。 */
    @GetMapping
    fun history(
        @RequestParam(defaultValue = "30") limit: Int,
        @RequestParam(required = false) before: Instant?,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): List<SubmissionResponse> = service.history(principal.userId, limit, before)
}
