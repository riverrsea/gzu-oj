package cn.gzuoj.api

import cn.gzuoj.shared.AI_DIFFERENTIAL_SANDBOX_STAGE
import cn.gzuoj.shared.AiCompileTask
import cn.gzuoj.shared.AiSandboxTaskPayload
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import cn.gzuoj.shared.AiWorkflow
import cn.gzuoj.shared.AiWorkflowState

/** Python Agent 回传的模型无关进度事件。 */
data class AiAgentEventRequest(
    /** 事件幂等标识。 */
    val eventId: UUID,
    /** 所属运行。 */
    val runId: UUID,
    /** 流程阶段。 */
    @field:NotBlank
    val stage: String,
    /** 阶段状态。 */
    @field:NotBlank
    val status: String,
    /** 给管理员看的简短说明。 */
    @field:NotBlank
    val message: String,
    /** 当前修复轮次。 */
    @field:Min(0)
    @field:Max(2)
    val repairRound: Int,
)

/** Python Agent 创建沙箱任务时附带的修复轮次、阶段与尝试序号。 */
data class AiAgentSandboxRequest(
    /** LangGraph 当前修复轮次。 */
    @field:Min(0)
    @field:Max(2)
    val repairRound: Int,
    /** 同一阶段内的第几次尝试；编译门禁重试时递增，用于幂等键。 */
    @field:Min(0)
    @field:Max(20)
    val attempt: Int = 0,
    /** 全量差分任务参数；与 compile 互斥。 */
    val task: AiSandboxTaskPayload? = null,
    /** 编译门禁任务参数；与 task 互斥。 */
    val compile: AiCompileTask? = null,
)

/** Agent 失败或取消通知。 */
data class AiAgentFailureRequest(
    /** 可审计的失败原因。 */
    @field:NotBlank
    val reason: String,
    /** 可恢复的失败阶段目标；为空表示该失败不可人工恢复。 */
    val resumeTarget: String? = null,
)

/** Agent 回传的单个角色结构化响应，用于管理员审计与人工接管。 */
data class AiAgentStepRequest(
    /** 执行角色。 */
    @field:NotBlank
    val role: String,
    /** 对应的小状态名，如 ANALYZING、REVIEWING。 */
    @field:NotBlank
    val state: String,
    /** 模型返回的结构化响应 JSON。 */
    val response: JsonNode,
    /** 本步骤失败原因；为空表示成功。 */
    val failureReason: String? = null,
    /** 本次模型调用费用微单位。 */
    @field:Min(0)
    val costMicrounits: Long = 0,
)

/** Worker 结算后通知 Python LangGraph 恢复 interrupt 的最小回调。 */
data class AiAgentSandboxResultRequest(
    /** 幂等事件标识。 */
    val eventId: UUID,
    /** 所属运行。 */
    val runId: UUID,
    /** 已结算的沙箱任务。 */
    val sandboxJobId: UUID,
    /** 当前修复轮次。 */
    val repairRound: Int,
    /** Kotlin 沙箱状态名。 */
    val status: String,
    /** 失败原因。 */
    val failureReason: String? = null,
    /** 失败产物归属名（TEST_DATA/SOLUTIONS/BRUTE_FORCE）；为空表示不可定向。 */
    val failedStage: String? = null,
)

/** Python Agent 内部回调接口；仅接受配置的 Bearer Token。 */
@RestController
@RequestMapping("/internal/agent/v1")
class AiAgentController(
    /** 数据库访问入口。 */
    private val jdbc: JdbcTemplate,
    /** JSON 编解码器。 */
    private val mapper: ObjectMapper,
    /** Agent 地址和共享令牌配置。 */
    private val properties: AppProperties,
) {
    /** 记录事件；重复 eventId 直接视为成功。 */
    @PostMapping("/events")
    @Transactional
    fun event(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @Valid @RequestBody body: AiAgentEventRequest,
    ) {
        authenticate(authorization)
        val inserted = jdbc.update(
            """
            INSERT INTO ai_run_log(id, run_id, event_id, kind, stage, status, message, repair_round)
            VALUES (?, ?, ?, 'PROGRESS', ?, ?, ?, ?)
            ON CONFLICT(event_id) DO NOTHING
            """.trimIndent(),
            UUID.randomUUID(), body.runId, body.eventId, body.stage.take(64), body.status.take(32), body.message.take(2_000), body.repairRound,
        )
        if (inserted == 1) advanceState(body)
    }

    /** 将 Agent 阶段投影到 Kotlin 状态机；乱序或重复事件不会回退状态。 */
    private fun advanceState(event: AiAgentEventRequest) {
        val target = when (event.stage) {
            "ANALYZING" -> AiWorkflowState.ANALYZING
            "GENERATING_TEST_DATA" -> AiWorkflowState.GENERATING_TESTS
            "GENERATING_SOLUTIONS", "GENERATING_BRUTE_FORCE" -> AiWorkflowState.GENERATING_SOLUTIONS
            "SANDBOX" -> AiWorkflowState.DIFFERENTIAL_TESTING
            else -> return
        }
        val current = jdbc.query(
            "SELECT state FROM ai_problem_run WHERE id = ? FOR UPDATE",
            { result, _ -> AiWorkflowState.valueOf(result.getString("state")) },
            event.runId,
        ).firstOrNull() ?: return
        if (current == target || !AiWorkflow.canTransition(current, target)) return
        jdbc.update(
            "UPDATE ai_problem_run SET state = ?, updated_at = now() WHERE id = ?",
            target.name,
            event.runId,
        )
        jdbc.update(
            """
            INSERT INTO ai_run_log(id, run_id, kind, message, from_state, to_state)
            VALUES (?, ?, 'TRANSITION', ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            event.runId,
            event.message.take(2_000),
            current.name,
            target.name,
        )
    }

    /** 创建或复用同一运行、修复轮次、阶段和尝试次数的沙箱任务。 */
    @PostMapping("/runs/{runId}/sandbox-jobs")
    @Transactional
    fun sandbox(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable runId: UUID,
        @Valid @RequestBody body: AiAgentSandboxRequest,
    ): Map<String, UUID> {
        authenticate(authorization)
        val compile = body.compile
        if ((compile == null) == (body.task == null)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_SANDBOX_TASK", "沙箱任务必须且只能提供差分参数或编译门禁参数")
        }
        val stage = compile?.stage?.name ?: AI_DIFFERENTIAL_SANDBOX_STAGE
        val jobId = jdbc.query(
            "SELECT id FROM ai_sandbox_job WHERE run_id = ? AND repair_round = ? AND stage = ? AND attempt = ?",
            { result, _ -> result.getObject("id", UUID::class.java) },
            runId, body.repairRound, stage, body.attempt,
        ).firstOrNull() ?: UUID.randomUUID().also { id ->
            // 只有全量差分任务代表进入差分阶段；编译门禁仍停留在当前生成阶段。
            if (compile == null) {
                jdbc.update(
                    "UPDATE ai_problem_run SET state = 'DIFFERENTIAL_TESTING', updated_at = now() WHERE id = ? AND state IN ('ANALYZING', 'GENERATING_SOLUTIONS', 'REVIEWING', 'GENERATING_TESTS')",
                    runId,
                )
            }
            jdbc.update(
                "INSERT INTO ai_sandbox_job(id, run_id, repair_round, stage, attempt, payload, priority) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
                id,
                runId,
                body.repairRound,
                stage,
                body.attempt,
                mapper.writeValueAsString(compile ?: body.task),
                10,
            )
        }
        return mapOf("sandboxJobId" to jobId)
    }

    /** 将模型或结构化校验失败交给管理员处理。 */
    @PostMapping("/runs/{runId}/fail")
    @Transactional
    fun fail(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable runId: UUID,
        @Valid @RequestBody body: AiAgentFailureRequest,
    ) {
        authenticate(authorization)
        val current = jdbc.query(
            "SELECT state FROM ai_problem_run WHERE id = ? FOR UPDATE",
            { result, _ -> AiWorkflowState.valueOf(result.getString("state")) },
            runId,
        ).firstOrNull() ?: return
        if (current == AiWorkflowState.PUBLISHED || current == AiWorkflowState.CANCELED) return
        jdbc.update(
            """
            UPDATE ai_problem_run
            SET state = 'NEEDS_REVIEW', failure_reason = ?, resume_target = ?, updated_at = now()
            WHERE id = ? AND state NOT IN ('PUBLISHED', 'CANCELED')
            """.trimIndent(),
            body.reason.take(2_000),
            body.resumeTarget?.take(32),
            runId,
        )
        // 仅首次进入待审查时追加一条时间线，避免重复失败覆盖历史。
        if (current != AiWorkflowState.NEEDS_REVIEW) recordFailureHistory(runId, current, body.reason)
    }

    /** 持久化 Agent 单个角色的结构化响应供管理员审计与人工接管。 */
    @PostMapping("/runs/{runId}/steps")
    @Transactional
    fun recordStep(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable runId: UUID,
        @Valid @RequestBody body: AiAgentStepRequest,
    ) {
        authenticate(authorization)
        val meta = jdbc.query(
            "SELECT model, prompt_version FROM ai_problem_run WHERE id = ?",
            { result, _ -> result.getString("model") to result.getString("prompt_version") },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_RUN_NOT_FOUND", "AI 运行不存在")
        val ordinal = jdbc.queryForObject(
            "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM ai_problem_step WHERE run_id = ?",
            Int::class.java,
            runId,
        ) ?: 1
        val contentSha256 = SecureValues.sha256(mapper.writeValueAsBytes(body.response))
        jdbc.update(
            """
            INSERT INTO ai_problem_step(
                id, run_id, role, state, model, prompt_version, response_json,
                content_sha256, cost_microunits, failure_reason, finished_at, ordinal
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now(), ?)
            ON CONFLICT (run_id, role) DO UPDATE SET
                state = EXCLUDED.state,
                response_json = EXCLUDED.response_json,
                content_sha256 = EXCLUDED.content_sha256,
                cost_microunits = EXCLUDED.cost_microunits,
                failure_reason = EXCLUDED.failure_reason,
                finished_at = now()
            """.trimIndent(),
            UUID.randomUUID(), runId, body.role.take(64), body.state.take(32), meta.first, meta.second,
            mapper.writeValueAsString(body.response), contentSha256, body.costMicrounits,
            body.failureReason?.take(2_000), ordinal,
        )
    }

    /** 追加一条进入人工接管的失败时间线。 */
    private fun recordFailureHistory(runId: UUID, from: AiWorkflowState, reason: String) {
        jdbc.update(
            """
            INSERT INTO ai_run_log(id, run_id, kind, message, from_state, to_state)
            VALUES (?, ?, 'TRANSITION', ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), runId, reason.take(2_000), from.name, AiWorkflowState.NEEDS_REVIEW.name,
        )
    }

    /** 取消通知幂等确认；实际状态由管理员 API 负责。 */
    @PostMapping("/runs/{runId}/cancel-ack")
    fun cancelAck(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable runId: UUID,
    ) {
        authenticate(authorization)
    }

    /** 使用常量时间比较校验 Python Agent 的内部令牌。 */
    private fun authenticate(authorization: String?) {
        val expected = properties.ai.agentInternalToken
        val token = authorization?.removePrefix("Bearer ")?.takeIf { authorization.startsWith("Bearer ") }
        if (expected.isBlank() || token == null || !SecureValues.constantTimeEquals(SecureValues.sha256(token), SecureValues.sha256(expected))) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "AGENT_UNAUTHENTICATED", "Agent 内部令牌无效")
        }
    }
}

/** Kotlin 只负责可靠派发运行快照，模型调用和流程推进全部由 Python Agent 完成。 */
@Component
class AiAgentDispatcher(
    /** 数据库访问入口。 */
    private val jdbc: JdbcTemplate,
    /** JSON 编解码器。 */
    private val mapper: ObjectMapper,
    /** Agent 地址和认证配置。 */
    private val properties: AppProperties,
) {
    /** 待派发的持久化消息。 */
    private data class OutboxRow(
        /** 消息标识。 */
        val id: UUID,
        /** AI 运行标识。 */
        val runId: UUID,
        /** 消息类型。 */
        val eventType: String,
        /** 消息 JSON；启动和取消事件为空对象。 */
        val payload: String,
    )

    /** 扫描持久化 outbox，网络失败时保留待重试状态。 */
    @Scheduled(fixedDelayString = "\${gzu-oj.ai.dispatch-delay-ms:2000}")
    fun dispatch() {
        val row = jdbc.query(
            "SELECT id, run_id, event_type, payload::text FROM ai_agent_outbox WHERE status = 'PENDING' AND available_at <= now() ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",
            { result, _ ->
                OutboxRow(
                    id = result.getObject("id", UUID::class.java),
                    runId = result.getObject("run_id", UUID::class.java),
                    eventType = result.getString("event_type"),
                    payload = result.getString("payload"),
                )
            },
        ).firstOrNull() ?: return
        try {
            val client = RestClient.builder()
                .requestFactory(SimpleClientHttpRequestFactory().apply {
                    val timeout = (properties.ai.agentTimeoutSeconds.coerceAtLeast(1) * 1_000L)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    setConnectTimeout(timeout)
                    setReadTimeout(timeout)
                })
                .baseUrl(properties.ai.agentBaseUrl)
                .defaultHeader("Authorization", "Bearer ${properties.ai.agentInternalToken}")
                .build()
            when (row.eventType) {
                "START_RUN" -> {
                    val payload = jdbc.query(
                        """
                        SELECT r.id AS run_id, r.problem_version_id, r.repair_round, r.requested_test_case_count,
                               pv.statement_markdown, pv.time_limit_ms, pv.memory_limit_mib
                        FROM ai_problem_run r JOIN problem_version pv ON pv.id = r.problem_version_id WHERE r.id = ?
                        """.trimIndent(),
                        { result, _ ->
                            mapOf(
                                "runId" to result.getObject("run_id", UUID::class.java),
                                "problemVersionId" to result.getObject("problem_version_id", UUID::class.java),
                                "statementMarkdown" to result.getString("statement_markdown"),
                                "testCaseCount" to result.getInt("requested_test_case_count"),
                                "timeLimitMs" to result.getInt("time_limit_ms"),
                                "memoryLimitMiB" to result.getInt("memory_limit_mib"),
                                "repairRound" to result.getInt("repair_round"),
                            )
                        },
                        row.runId,
                    ).firstOrNull() ?: return
                    client.post().uri("/internal/v1/runs").body(payload).retrieve().toBodilessEntity()
                }
                "CANCEL_RUN" -> client.post().uri("/internal/v1/runs/{runId}/cancel", row.runId)
                    .retrieve().toBodilessEntity()
                "SANDBOX_RESULT" -> client.post()
                    .uri("/internal/v1/runs/{runId}/sandbox-results", row.runId)
                    .body(mapper.readTree(row.payload))
                    .retrieve().toBodilessEntity()
                "RESUME_RUN" -> client.post()
                    .uri("/internal/v1/runs/{runId}/resume", row.runId)
                    .body(mapper.readTree(row.payload))
                    .retrieve().toBodilessEntity()
                else -> throw IllegalStateException("不支持的 Agent outbox 事件：${row.eventType}")
            }
            jdbc.update("UPDATE ai_agent_outbox SET status = 'SENT', sent_at = now(), attempts = attempts + 1 WHERE id = ?", row.id)
        } catch (failure: Exception) {
            jdbc.update(
                """
                UPDATE ai_agent_outbox
                SET attempts = LEAST(attempts + 1, 20),
                    status = CASE WHEN attempts >= 19 THEN 'FAILED' ELSE status END,
                    available_at = now() + interval '10 seconds',
                    last_error = ?
                WHERE id = ?
                """.trimIndent(),
                failure.message?.take(2_000), row.id,
            )
        }
    }
}
