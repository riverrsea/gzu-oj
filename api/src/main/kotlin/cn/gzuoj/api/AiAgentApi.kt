package cn.gzuoj.api

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

/** Python Agent 创建沙箱任务时附带的修复轮次。 */
data class AiAgentSandboxRequest(
    /** LangGraph 当前修复轮次。 */
    @field:Min(0)
    @field:Max(2)
    val repairRound: Int,
    /** Worker 执行参数。 */
    val task: AiSandboxTaskPayload,
)

/** Agent 失败或取消通知。 */
data class AiAgentFailureRequest(
    /** 可审计的失败原因。 */
    @field:NotBlank
    val reason: String,
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
            INSERT INTO ai_agent_event(event_id, run_id, stage, status, message, repair_round)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(event_id) DO NOTHING
            """.trimIndent(),
            body.eventId, body.runId, body.stage.take(64), body.status.take(32), body.message.take(2_000), body.repairRound,
        )
        if (inserted == 1) advanceState(body)
    }

    /** 将 Agent 阶段投影到 Kotlin 状态机；乱序或重复事件不会回退状态。 */
    private fun advanceState(event: AiAgentEventRequest) {
        val target = when (event.stage) {
            "ANALYZING" -> AiWorkflowState.ANALYZING
            "GENERATING_SOLUTIONS" -> AiWorkflowState.GENERATING_SOLUTIONS
            "TEST_DESIGN", "ADVERSARIAL_REVIEW" -> AiWorkflowState.REVIEWING
            "GENERATING_ARTIFACTS" -> AiWorkflowState.GENERATING_TESTS
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
            INSERT INTO ai_problem_state_history(id, run_id, from_state, to_state, major_state, message)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            event.runId,
            current.name,
            target.name,
            AiWorkflow.majorState(target).name,
            event.message.take(2_000),
        )
    }

    /** 创建或复用同一运行和修复轮次的沙箱任务。 */
    @PostMapping("/runs/{runId}/sandbox-jobs")
    @Transactional
    fun sandbox(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable runId: UUID,
        @Valid @RequestBody body: AiAgentSandboxRequest,
    ): Map<String, UUID> {
        authenticate(authorization)
        val jobId = jdbc.query(
            "SELECT id FROM ai_sandbox_job WHERE run_id = ? AND repair_round = ?",
            { result, _ -> result.getObject("id", UUID::class.java) },
            runId, body.repairRound,
        ).firstOrNull() ?: UUID.randomUUID().also { id ->
            jdbc.update(
                "UPDATE ai_problem_run SET state = 'DIFFERENTIAL_TESTING', updated_at = now() WHERE id = ? AND state IN ('ANALYZING', 'GENERATING_SOLUTIONS', 'REVIEWING', 'GENERATING_TESTS')",
                runId,
            )
            jdbc.update(
                "INSERT INTO ai_sandbox_job(id, run_id, repair_round, payload, priority) VALUES (?, ?, ?, ?::jsonb, ?)",
                id, runId, body.repairRound, mapper.writeValueAsString(body.task), 10,
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
        jdbc.update(
            "UPDATE ai_problem_run SET state = 'NEEDS_REVIEW', failure_reason = ?, updated_at = now() WHERE id = ? AND state NOT IN ('PUBLISHED', 'CANCELED')",
            body.reason.take(2_000), runId,
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
