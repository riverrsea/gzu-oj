package cn.gzuoj.api

import cn.gzuoj.shared.AiPublicationGate
import cn.gzuoj.shared.AiWorkflow
import cn.gzuoj.shared.AiWorkflowState
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors

/** AI 录题中的固定 Agent 角色。 */
enum class AiAgentRole {
    /** 分析题意、约束和歧义。 */
    STATEMENT_ANALYST,
    /** 独立标程 A。 */
    SOLUTION_A,
    /** 独立标程 B。 */
    SOLUTION_B,
    /** 测试设计和覆盖计划。 */
    TEST_DESIGNER,
    /** 对抗审查两份标程与测试计划。 */
    ADVERSARIAL_REVIEWER,
    /** 生成确定性输入生成器和校验器。 */
    GENERATOR,
    /** 在规模允许时生成小数据暴力解。 */
    BRUTE_FORCE,
}

/** 所有角色共用的结构化候选响应。 */
data class AiAgentResponse(
    /** 本角色的结论摘要。 */
    val summary: String = "",
    /** 尚未解决的题意歧义。 */
    val ambiguities: List<String> = emptyList(),
    /** 候选 C++17 源码；没有源码的角色为空。 */
    val sourceCode: String? = null,
    /** 确定性生成器源码。 */
    val generatorSource: String? = null,
    /** 输入校验器源码。 */
    val validatorSource: String? = null,
    /** 测试类别和边界计划。 */
    val testPlan: List<String> = emptyList(),
    /** 固定随机种子。 */
    val seeds: List<Long> = emptyList(),
    /** 对抗审查发现。 */
    val findings: List<String> = emptyList(),
)

/** 一次 Provider 调用的审计结果。 */
data class AiProviderResult(
    /** 结构化模型响应。 */
    val response: AiAgentResponse,
    /** 本次估算费用，单位微美元；未知时为零。 */
    val costMicrounits: Long = 0,
)

/** AI 模型供应商边界，业务不直接依赖厂商原生 Agent API。 */
interface AiProvider {
    /** 为一个固定角色生成结构化候选。 */
    fun generate(role: AiAgentRole, statement: String, context: String): AiProviderResult
}

/** 使用 Spring AI 调用 OpenAI 兼容服务的 Provider 实现。 */
@Component
class SpringAiProvider(
    /** Spring AI 在模型启用时提供的客户端构建器。 */
    private val builders: ObjectProvider<ChatClient.Builder>,
    /** 站点 AI 配置。 */
    private val properties: AppProperties,
) : AiProvider {
    /** 使用统一系统约束和角色提示生成结构化 JSON。 */
    override fun generate(role: AiAgentRole, statement: String, context: String): AiProviderResult {
        if (!properties.ai.enabled || properties.ai.apiKey.length < 8) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", "AI Provider 尚未启用或密钥未配置")
        }
        val client = builders.ifAvailable?.build()
            ?: throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE", "Spring AI Provider 未启用")
        val response = client.prompt()
            .system(SYSTEM_PROMPT)
            .user(rolePrompt(role, statement, context))
            .call()
            .entity(AiAgentResponse::class.java)
            ?: throw IllegalStateException("模型未返回结构化响应")
        return AiProviderResult(response)
    }

    /** 构造角色任务，明确禁止把模型猜测作为标准输出。 */
    private fun rolePrompt(role: AiAgentRole, statement: String, context: String): String = buildString {
        appendLine("角色：" + role.name)
        appendLine("题面：")
        appendLine(statement.take(MAX_STATEMENT_CHARS))
        appendLine("已完成步骤上下文：")
        appendLine(context.take(MAX_CONTEXT_CHARS))
        appendLine("返回与 AiAgentResponse 字段匹配的结构化内容。")
    }

    private companion object {
        /** 所有角色共享的不可绕过约束。 */
        const val SYSTEM_PROMPT: String =
            "你是 OJ 题目工程 Agent。所有源码必须可复现；不得直接猜测隐藏测试标准输出；" +
                "标准输出只能由通过校验的标程在沙箱中计算。发现歧义必须明确报告，不得自行补写题意。"

        /** 发送给模型的题面字符上限。 */
        const val MAX_STATEMENT_CHARS: Int = 200_000

        /** 发送给模型的审计上下文字符上限。 */
        const val MAX_CONTEXT_CHARS: Int = 100_000
    }
}

/** 管理员启动 AI 录题请求。 */
data class StartAiRunRequest(
    /** 需要处理的题目草稿版本。 */
    val problemVersionId: UUID,
)

/** 差分与资源校验的可审计证据。 */
data class AiValidationEvidence(
    /** 两份标程在全部大数据上的输出哈希是否一致。 */
    val solutionOutputHashesAgree: Boolean,
    /** 小数据暴力差分任务标识。 */
    @field:Size(min = 1, max = 100)
    val bruteForceJobIds: List<UUID>,
    /** 大数据双标程差分任务标识。 */
    @field:Size(min = 1, max = 100)
    val differentialJobIds: List<UUID>,
    /** 已复跑并一致的固定种子。 */
    @field:Size(min = 1, max = 1_000)
    val reproducedSeeds: List<Long>,
    /** 输入、标准输出和生成器的 SHA-256。 */
    @field:Size(min = 1, max = 2_000)
    val artifactHashes: List<@NotBlank String>,
    /** 标程最坏 CPU 时间占题目限制百分比。 */
    @field:Min(0)
    @field:Max(100)
    val maximumTimePercent: Int,
    /** 标程最坏内存占题目限制百分比。 */
    @field:Min(0)
    @field:Max(100)
    val maximumMemoryPercent: Int,
)

/** 管理员提交确定性门禁结果的请求。 */
data class RecordAiValidationRequest(
    /** 六项发布门禁。 */
    @field:Valid
    val gate: AiPublicationGate,
    /** 能够回查的差分和制品证据。 */
    @field:Valid
    val evidence: AiValidationEvidence,
)

/** AI 运行的用户可见审计摘要。 */
data class AiRunResponse(
    /** 运行标识。 */
    val id: UUID,
    /** 题目草稿版本。 */
    val problemVersionId: UUID,
    /** 当前状态。 */
    val state: AiWorkflowState,
    /** 当前自动修复轮次。 */
    val repairRound: Int,
    /** 模型名称。 */
    val model: String,
    /** 提示词版本。 */
    val promptVersion: String,
    /** 累计费用微单位。 */
    val costMicrounits: Long,
    /** 失败或人工处理原因。 */
    val failureReason: String?,
    /** 已完成的固定角色。 */
    val completedRoles: List<AiAgentRole>,
)

/** 协调器领取到的一步执行租约。 */
internal data class AiRunLease(
    /** 运行标识。 */
    val runId: UUID,
    /** 本次协调租约。 */
    val lease: UUID,
    /** 当前状态。 */
    val state: AiWorkflowState,
    /** 题面 Markdown。 */
    val statement: String,
)

/** AI 状态、审计步骤和发布门禁持久化服务。 */
@Service
class AiRunService(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
    /** JSON 编解码器。 */
    private val mapper: ObjectMapper,
    /** 应用配置。 */
    private val properties: AppProperties,
    /** 题目版本发布服务。 */
    private val problems: ProblemService,
) {
    /** 为管理员草稿创建可恢复运行。 */
    @Transactional
    fun start(request: StartAiRunRequest, creator: UUID): AiRunResponse {
        val draftExists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM problem_version WHERE id = ? AND status = 'DRAFT')",
            Boolean::class.java,
            request.problemVersionId,
        ) ?: false
        if (!draftExists) throw ApiException(HttpStatus.CONFLICT, "AI_REQUIRES_DRAFT", "AI 录题只能处理草稿版本")
        val active = jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM ai_problem_run WHERE problem_version_id = ?
                AND state NOT IN ('PUBLISHED', 'FAILED', 'CANCELED')
            )
            """.trimIndent(),
            Boolean::class.java,
            request.problemVersionId,
        ) ?: false
        if (active) throw ApiException(HttpStatus.CONFLICT, "AI_RUN_ACTIVE", "该版本已有未结束的 AI 流程")
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO ai_problem_run(
                id, problem_version_id, state, provider_base_url, model, prompt_version, created_by
            ) VALUES (?, ?, 'DRAFT', ?, ?, ?, ?)
            """.trimIndent(),
            id,
            request.problemVersionId,
            properties.ai.baseUrl,
            properties.ai.model,
            properties.ai.promptVersion,
            creator,
        )
        return get(id)
    }

    /** 领取一个可由模型推进的运行，事务结束后再发起外部调用。 */
    @Transactional
    internal fun claimNext(): AiRunLease? {
        if (!properties.ai.enabled) return null
        val row = jdbc.query(
            """
            SELECT r.id, r.state, pv.statement_markdown
            FROM ai_problem_run r JOIN problem_version pv ON pv.id = r.problem_version_id
            WHERE r.state IN ('DRAFT', 'ANALYZING', 'GENERATING_SOLUTIONS', 'REVIEWING', 'GENERATING_TESTS', 'VALIDATING')
              AND r.next_run_at <= now()
              AND (r.coordinator_lease IS NULL OR r.coordinator_lease_expires_at < now())
            ORDER BY r.created_at FOR UPDATE OF r SKIP LOCKED LIMIT 1
            """.trimIndent(),
            { result, _ ->
                Triple(
                    result.getObject("id", UUID::class.java),
                    AiWorkflowState.valueOf(result.getString("state")),
                    result.getString("statement_markdown"),
                )
            },
        ).firstOrNull() ?: return null
        val lease = UUID.randomUUID()
        jdbc.update(
            "UPDATE ai_problem_run SET coordinator_lease = ?, coordinator_lease_expires_at = now() + interval '5 minutes' WHERE id = ?",
            lease,
            row.first,
        )
        return AiRunLease(row.first, lease, row.second, row.third)
    }

    /** 保存本步所有角色结果并原子推进状态。 */
    @Transactional
    internal fun completeStep(lease: AiRunLease, results: Map<AiAgentRole, AiProviderResult>) {
        val current = lockLease(lease)
        val next = nextState(current)
        AiWorkflow.requireTransition(current, next)
        val existingCount = jdbc.queryForObject(
            "SELECT count(*) FROM ai_problem_step WHERE run_id = ?",
            Int::class.java,
            lease.runId,
        ) ?: 0
        results.entries.forEachIndexed { index, entry ->
            jdbc.update(
                """
                INSERT INTO ai_problem_step(
                    id, run_id, role, state, model, prompt_version, request_json, response_json,
                    structured_response, ordinal, cost_microunits, content_sha256, finished_at
                )
                SELECT ?, r.id, ?, ?, r.model, r.prompt_version, ?::jsonb, ?::jsonb,
                       ?::jsonb, ?, ?, ?, now()
                FROM ai_problem_run r WHERE r.id = ?
                """.trimIndent(),
                UUID.randomUUID(),
                entry.key.name,
                current.name,
                mapper.writeValueAsString(mapOf("statementSha256" to SecureValues.sha256(lease.statement))),
                mapper.writeValueAsString(entry.value.response),
                mapper.writeValueAsString(entry.value.response),
                existingCount + index + 1,
                entry.value.costMicrounits,
                SecureValues.sha256(mapper.writeValueAsBytes(entry.value.response)),
                lease.runId,
            )
        }
        val addedCost = results.values.sumOf(AiProviderResult::costMicrounits)
        val currentCost = jdbc.queryForObject(
            "SELECT cost_microunits FROM ai_problem_run WHERE id = ?",
            Long::class.java,
            lease.runId,
        ) ?: 0
        if (currentCost + addedCost > properties.ai.maxCostMicrounits) {
            jdbc.update(
                """
                UPDATE ai_problem_run SET state = 'NEEDS_REVIEW', failure_reason = 'AI 费用达到运行上限',
                    coordinator_lease = NULL, coordinator_lease_expires_at = NULL, updated_at = now()
                WHERE id = ?
                """.trimIndent(),
                lease.runId,
            )
            return
        }
        if (next == AiWorkflowState.PUBLISHED) {
            publishAfterGate(lease.runId)
        } else {
            jdbc.update(
                """
                UPDATE ai_problem_run SET state = ?, cost_microunits = cost_microunits + ?,
                    coordinator_lease = NULL, coordinator_lease_expires_at = NULL, updated_at = now()
                WHERE id = ?
                """.trimIndent(),
                next.name,
                addedCost,
                lease.runId,
            )
        }
    }

    /** 模型或 Provider 失败后进入人工接管，保留已有步骤。 */
    @Transactional
    internal fun failLease(lease: AiRunLease, reason: String) {
        jdbc.update(
            """
            UPDATE ai_problem_run SET state = 'NEEDS_REVIEW', failure_reason = ?,
                coordinator_lease = NULL, coordinator_lease_expires_at = NULL, updated_at = now()
            WHERE id = ? AND coordinator_lease = ?
            """.trimIndent(),
            reason.take(2_000),
            lease.runId,
            lease.lease,
        )
    }

    /** 在差分阶段记录外部沙箱形成的确定性门禁证据。 */
    @Transactional
    fun recordValidation(runId: UUID, request: RecordAiValidationRequest): AiRunResponse {
        val state = jdbc.query(
            "SELECT state FROM ai_problem_run WHERE id = ? FOR UPDATE",
            { result, _ -> AiWorkflowState.valueOf(result.getString("state")) },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_RUN_NOT_FOUND", "AI 运行不存在")
        if (state != AiWorkflowState.DIFFERENTIAL_TESTING) {
            throw ApiException(HttpStatus.CONFLICT, "AI_VALIDATION_NOT_READY", "当前状态不能提交差分证据")
        }
        val hashesValid = request.evidence.artifactHashes.all { it.matches(Regex("^[0-9a-fA-F]{64}$")) }
        val resourceValid = request.evidence.maximumTimePercent <= 70 && request.evidence.maximumMemoryPercent <= 70
        if (!hashesValid || request.gate.resourceMarginPassed != resourceValid ||
            request.gate.solutionsAgree != request.evidence.solutionOutputHashesAgree) {
            throw ApiException(HttpStatus.BAD_REQUEST, "AI_EVIDENCE_INCONSISTENT", "发布门禁与差分证据不一致")
        }
        val next = if (request.gate.allowsPublication()) AiWorkflowState.VALIDATING else AiWorkflowState.NEEDS_REVIEW
        AiWorkflow.requireTransition(state, next)
        jdbc.update(
            """
            UPDATE ai_problem_run SET state = ?, publication_gate = ?::jsonb, validation_evidence = ?::jsonb,
                failure_reason = ?, next_run_at = now(), updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            next.name,
            mapper.writeValueAsString(request.gate),
            mapper.writeValueAsString(request.evidence),
            if (next == AiWorkflowState.NEEDS_REVIEW) "确定性发布门禁未全部通过" else null,
            runId,
        )
        return get(runId)
    }

    /** 管理员取消非终态运行。 */
    @Transactional
    fun cancel(runId: UUID): AiRunResponse {
        val state = jdbc.query(
            "SELECT state FROM ai_problem_run WHERE id = ? FOR UPDATE",
            { result, _ -> AiWorkflowState.valueOf(result.getString("state")) },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_RUN_NOT_FOUND", "AI 运行不存在")
        if (!AiWorkflow.canTransition(state, AiWorkflowState.CANCELED)) {
            throw ApiException(HttpStatus.CONFLICT, "AI_RUN_TERMINAL", "终态运行不能取消")
        }
        jdbc.update("UPDATE ai_problem_run SET state = 'CANCELED', updated_at = now() WHERE id = ?", runId)
        return get(runId)
    }

    /** 读取运行及已完成角色，不返回密钥或完整提示词。 */
    fun get(runId: UUID): AiRunResponse {
        val completed = jdbc.queryForList(
            "SELECT DISTINCT role FROM ai_problem_step WHERE run_id = ? AND finished_at IS NOT NULL ORDER BY role",
            String::class.java,
            runId,
        ).filterNotNull().map(AiAgentRole::valueOf)
        return jdbc.query(
            """
            SELECT id, problem_version_id, state, repair_round, model, prompt_version,
                   cost_microunits, failure_reason FROM ai_problem_run WHERE id = ?
            """.trimIndent(),
            { result, _ ->
                AiRunResponse(
                    id = result.getObject("id", UUID::class.java),
                    problemVersionId = result.getObject("problem_version_id", UUID::class.java),
                    state = AiWorkflowState.valueOf(result.getString("state")),
                    repairRound = result.getInt("repair_round"),
                    model = result.getString("model"),
                    promptVersion = result.getString("prompt_version"),
                    costMicrounits = result.getLong("cost_microunits"),
                    failureReason = result.getString("failure_reason"),
                    completedRoles = completed,
                )
            },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_RUN_NOT_FOUND", "AI 运行不存在")
    }

    /** 读取已有角色响应作为下一步上下文。 */
    fun context(runId: UUID): String = jdbc.queryForList(
        "SELECT role || ': ' || response_json::text FROM ai_problem_step WHERE run_id = ? ORDER BY ordinal",
        String::class.java,
        runId,
    ).filterNotNull().joinToString("\n").take(100_000)

    /** 校验协调器租约并锁定运行。 */
    private fun lockLease(lease: AiRunLease): AiWorkflowState = jdbc.query(
        """
        SELECT state FROM ai_problem_run
        WHERE id = ? AND coordinator_lease = ? AND coordinator_lease_expires_at > now()
        FOR UPDATE
        """.trimIndent(),
        { result, _ -> AiWorkflowState.valueOf(result.getString("state")) },
        lease.runId,
        lease.lease,
    ).firstOrNull() ?: throw ApiException(HttpStatus.CONFLICT, "AI_LEASE_STALE", "AI 协调租约已失效")

    /** 返回模型步骤的正常后继；差分阶段由沙箱证据接口推进。 */
    private fun nextState(state: AiWorkflowState): AiWorkflowState = when (state) {
        AiWorkflowState.DRAFT -> AiWorkflowState.ANALYZING
        AiWorkflowState.ANALYZING -> AiWorkflowState.GENERATING_SOLUTIONS
        AiWorkflowState.GENERATING_SOLUTIONS -> AiWorkflowState.REVIEWING
        AiWorkflowState.REVIEWING -> AiWorkflowState.GENERATING_TESTS
        AiWorkflowState.GENERATING_TESTS -> AiWorkflowState.DIFFERENTIAL_TESTING
        AiWorkflowState.VALIDATING -> AiWorkflowState.PUBLISHED
        else -> throw ApiException(HttpStatus.CONFLICT, "AI_STEP_EXTERNAL", "当前状态需要差分证据或管理员处理")
    }

    /** 在同一事务内再次验证全部门禁并发布不可变版本。 */
    private fun publishAfterGate(runId: UUID) {
        val row = jdbc.query(
            "SELECT problem_version_id, publication_gate::text FROM ai_problem_run WHERE id = ? FOR UPDATE",
            { result, _ -> result.getObject("problem_version_id", UUID::class.java) to result.getString("publication_gate") },
            runId,
        ).first()
        val gateJson = row.second ?: throw ApiException(HttpStatus.CONFLICT, "AI_GATE_MISSING", "缺少发布门禁")
        val gate = mapper.readValue(gateJson, AiPublicationGate::class.java)
        if (!gate.allowsPublication()) throw ApiException(HttpStatus.CONFLICT, "AI_GATE_FAILED", "发布门禁未全部通过")
        problems.publish(row.first)
        jdbc.update(
            """
            UPDATE ai_problem_run SET state = 'PUBLISHED', coordinator_lease = NULL,
                coordinator_lease_expires_at = NULL, updated_at = now() WHERE id = ?
            """.trimIndent(),
            runId,
        )
    }
}

/** 并发为一的 API 内 AI 协调器。 */
@Component
class AiCoordinator(
    /** AI 运行持久化服务。 */
    private val runs: AiRunService,
    /** Spring AI Provider 边界。 */
    private val provider: AiProvider,
    /** 应用 AI 配置。 */
    private val properties: AppProperties,
) {
    /** 定期领取一个步骤；数据库租约支持重启恢复。 */
    @Scheduled(fixedDelayString = "\${gzu-oj.ai.coordinator-delay-ms:5000}")
    fun coordinate() {
        if (!properties.ai.enabled || !guard.tryAcquire()) return
        try {
            val lease = runs.claimNext() ?: return
            try {
                val roles = rolesFor(lease.state)
                val context = runs.context(lease.runId)
                val results = if (roles.size > 1) parallelGenerate(roles, lease.statement, context) else
                    roles.associateWith { provider.generate(it, lease.statement, context) }
                runs.completeStep(lease, results)
            } catch (failure: Exception) {
                logger.error("AI 运行 {} 的步骤执行失败", lease.runId, failure)
                runs.failLease(lease, failure.message ?: "AI Provider 调用失败")
            }
        } finally {
            guard.release()
        }
    }

    /** 两份标程和测试计划并行生成，但一份运行仍只占一个协调器名额。 */
    private fun parallelGenerate(
        roles: List<AiAgentRole>,
        statement: String,
        context: String,
    ): Map<AiAgentRole, AiProviderResult> = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        val futures = roles.associateWith { role ->
            executor.submit<AiProviderResult> { provider.generate(role, statement, context) }
        }
        futures.mapValues { it.value.get() }
    }

    /** 状态对应的固定 Agent 角色。 */
    private fun rolesFor(state: AiWorkflowState): List<AiAgentRole> = when (state) {
        AiWorkflowState.DRAFT -> listOf(AiAgentRole.STATEMENT_ANALYST)
        AiWorkflowState.ANALYZING -> listOf(
            AiAgentRole.SOLUTION_A,
            AiAgentRole.SOLUTION_B,
            AiAgentRole.TEST_DESIGNER,
        )
        AiWorkflowState.GENERATING_SOLUTIONS -> listOf(AiAgentRole.ADVERSARIAL_REVIEWER)
        AiWorkflowState.REVIEWING -> listOf(AiAgentRole.GENERATOR, AiAgentRole.BRUTE_FORCE)
        AiWorkflowState.GENERATING_TESTS -> emptyList()
        AiWorkflowState.VALIDATING -> emptyList()
        else -> throw IllegalStateException("当前 AI 状态不能由模型协调器推进：" + state)
    }

    private companion object {
        /** 进程内单并发门禁；跨进程由数据库租约保证。 */
        val guard = java.util.concurrent.Semaphore(1)

        /** 日志记录器。 */
        val logger = LoggerFactory.getLogger(AiCoordinator::class.java)
    }
}

/** 管理员 AI 录题与人工接管接口。 */
@RestController
@RequestMapping("/api/v1/admin/ai-runs")
@PreAuthorize("hasRole('ADMIN')")
class AdminAiRunController(
    /** AI 运行服务。 */
    private val service: AiRunService,
) {
    /** 为题目草稿启动 AI 流程。 */
    @PostMapping
    fun start(
        @Valid @RequestBody body: StartAiRunRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): AiRunResponse = service.start(body, principal.userId)

    /** 查看状态和已完成角色。 */
    @GetMapping("/{runId}")
    fun get(@PathVariable runId: UUID): AiRunResponse = service.get(runId)

    /** 写入真实沙箱差分产生的发布证据。 */
    @PostMapping("/{runId}/validation")
    fun validate(
        @PathVariable runId: UUID,
        @Valid @RequestBody body: RecordAiValidationRequest,
    ): AiRunResponse = service.recordValidation(runId, body)

    /** 取消尚未结束的 AI 流程。 */
    @PostMapping("/{runId}/cancel")
    fun cancel(@PathVariable runId: UUID): AiRunResponse = service.cancel(runId)
}
