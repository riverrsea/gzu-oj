package cn.gzuoj.api

import cn.gzuoj.shared.AiPublicationGate
import cn.gzuoj.shared.AiSandboxCompletion
import cn.gzuoj.shared.AiSandboxTaskPayload
import cn.gzuoj.shared.AiMajorState
import cn.gzuoj.shared.AiWorkflow
import cn.gzuoj.shared.AiWorkflowState
import cn.gzuoj.shared.JudgePriority
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 管理员启动 AI 录题请求。 */
data class StartAiRunRequest(
    /** 需要处理的题目草稿版本。 */
    val problemVersionId: UUID,
    /** 本次 AI 流程需要生成的固定测试点数量。 */
    @field:Min(1)
    @field:Max(200)
    val testCaseCount: Int = 10,
    /** 差分通过后是否自动发布题目版本。 */
    val autoPublish: Boolean = false,
    /** 自动发布时从生成测试点开头选择为公开样例的数量。 */
    @field:Min(0)
    @field:Max(200)
    val sampleCount: Int = 0,
)

/** 管理员对到达 fail 节点、处于人工接管的运行发起恢复。 */
data class ResumeAiRunRequest(
    /** 恢复动作：reanalyze 对应题意分析重跑，rereview 对应对抗审查重跑。 */
    @field:NotBlank
    val action: String,
    /** 人工 review 后回传给 Agent、作为重新生成上下文的结构化澄清内容。 */
    val correction: JsonNode? = null,
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

/** AI 运行的用户可见审计摘要。 */
data class AiRunResponse(
    /** 运行标识。 */
    val id: UUID,
    /** 题目草稿版本。 */
    val problemVersionId: UUID,
    /** 当前状态。 */
    val state: AiWorkflowState,
    /** 兼容小状态之上的页面聚合大状态。 */
    val majorState: AiMajorState,
    /** 当前自动修复轮次。 */
    val repairRound: Int,
    /** 管理员启动流程时要求生成的测试点数量。 */
    val requestedTestCaseCount: Int,
    /** 是否在差分通过后自动发布。 */
    val autoPublish: Boolean,
    /** 自动发布时选择的公开样例数量。 */
    val requestedSampleCount: Int,
    /** 模型名称。 */
    val model: String,
    /** 提示词版本。 */
    val promptVersion: String,
    /** 累计费用微单位。 */
    val costMicrounits: Long,
    /** 失败或人工处理原因。 */
    val failureReason: String?,
    /** 处于人工接管时可恢复的失败阶段；为空表示不可人工恢复。 */
    val resumeTarget: AiWorkflowState? = null,
    /** 已完成的固定角色。 */
    val completedRoles: List<String>,
    /** 已持久化的 Agent 返回，供管理员排查每一步的结果。 */
    val steps: List<AiStepResponse> = emptyList(),
    /** 状态变更时间线；旧运行没有历史时为空。 */
    val history: List<AiStateHistoryEntry> = emptyList(),
    /** 已通过差分并写入题目版本的测试点；仅管理员接口可见。 */
    val generatedTestCases: List<AiGeneratedTestCaseResponse> = emptyList(),
    /** 已通过沙箱验证并保存数据库源码的标准答案状态。 */
    val referenceSolutionSaved: Boolean = false,
)

/** 管理员读取的当前标准答案源码。 */
data class ReferenceSolutionResponse(
    /** 固定语言，首版仅支持 GNU C++17。 */
    val language: String,
    /** 直接保存在数据库中的源码。 */
    val sourceCode: String,
    /** 源码 SHA-256。 */
    val sourceSha256: String,
    /** 通过资源门禁后选中的候选。 */
    val selectedCandidate: String,
    /** 产生该源码的沙箱任务。 */
    val sandboxJobId: UUID,
    /** 保存时间。 */
    val createdAt: Instant,
)

/** 管理员查看的单个 AI 生成测试点。 */
data class AiGeneratedTestCaseResponse(
    /** 测试点顺序。 */
    val ordinal: Int,
    /** 生成器固定种子。 */
    val seed: Long,
    /** 生成并校验通过的输入。 */
    val input: String,
    /** 由差分通过标程计算的标准输出。 */
    val output: String,
    /** 自动分配的测试点分值。 */
    val score: Int,
    /** 是否作为公开样例返回。 */
    val sample: Boolean,
)

/** 单个 Agent 步骤的管理员可见返回。 */
data class AiStepResponse(
    /** 步骤记录标识。 */
    val id: UUID,
    /** 执行角色。 */
    val role: String,
    /** 角色执行时所处的小状态。 */
    val state: AiWorkflowState,
    /** 解析后的结构化返回。 */
    val response: tools.jackson.databind.JsonNode?,
    /** 数据库保存的原始结构化 JSON，解析失败时仍可排查模型实际返回。 */
    val rawResponse: String?,
    /** 本次调用费用。 */
    val costMicrounits: Long,
    /** 返回内容 SHA-256。 */
    val contentSha256: String?,
    /** 角色完成时间。 */
    val finishedAt: Instant?,
    /** 本步骤错误原因。 */
    val failureReason: String?,
)

/** AI 运行状态时间线中的一条追加式记录。 */
data class AiStateHistoryEntry(
    /** 聚合大状态。 */
    val majorState: AiMajorState,
    /** 保留兼容性的细粒度小状态。 */
    val state: AiWorkflowState,
    /** 状态变化说明。 */
    val message: String?,
    /** 状态变化时间。 */
    val createdAt: Instant,
)

/** AI 运行当前状态的数据库行。 */
private data class AiRunRecord(
    /** 运行标识。 */
    val id: UUID,
    /** 题目草稿版本。 */
    val problemVersionId: UUID,
    /** 细粒度状态。 */
    val state: AiWorkflowState,
    /** 自动修复轮次。 */
    val repairRound: Int,
    /** 管理员要求生成的测试点数量。 */
    val requestedTestCaseCount: Int,
    /** 是否在差分通过后自动发布。 */
    val autoPublish: Boolean,
    /** 自动发布时选择的公开样例数量。 */
    val requestedSampleCount: Int,
    /** 模型名称。 */
    val model: String,
    /** 提示词版本。 */
    val promptVersion: String,
    /** 累计费用。 */
    val costMicrounits: Long,
    /** 失败原因。 */
    val failureReason: String?,
    /** 可恢复的失败阶段目标。 */
    val resumeTarget: AiWorkflowState?,
    /** 发布门禁 JSON。 */
    val publicationGate: String?,
    /** 创建时间。 */
    val createdAt: Instant,
)

/** AI 差分结算时锁定的发布和公开样例选项。 */
private data class AiPublicationOptions(
    /** 需要写入测试点的草稿版本。 */
    val problemVersionId: UUID,
    /** 当前 AI 小状态。 */
    val state: AiWorkflowState,
    /** 制品创建者。 */
    val createdBy: UUID,
    /** 是否自动发布。 */
    val autoPublish: Boolean,
    /** 需要标记为公开样例的测试点数量。 */
    val sampleCount: Int,
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
        if (request.sampleCount > request.testCaseCount) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SAMPLE_COUNT", "公开样例数量不能超过生成测试点数量")
        }
        if (request.sampleCount > 0 && !request.autoPublish) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SAMPLE_COUNT", "只有自动发布时才能预先设置公开样例数量")
        }
        val active = jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM ai_problem_run WHERE problem_version_id = ?
                AND state NOT IN ('PUBLISHED', 'FAILED', 'CANCELED', 'NEEDS_REVIEW')
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
                id, problem_version_id, state, requested_test_case_count, auto_publish, requested_sample_count,
                provider_base_url, model, prompt_version, created_by
            ) VALUES (?, ?, 'ANALYZING', ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            request.problemVersionId,
            request.testCaseCount,
            request.autoPublish,
            request.sampleCount,
            properties.ai.agentBaseUrl,
            "python-agent",
            "external",
            creator,
        )
        jdbc.update(
            """
            INSERT INTO ai_agent_outbox(idempotency_key, event_type, run_id, payload)
            VALUES (?, 'START_RUN', ?, '{}'::jsonb)
            ON CONFLICT(idempotency_key) DO NOTHING
            """.trimIndent(),
            "start:$id",
            id,
        )
        recordStateTransition(id, null, AiWorkflowState.ANALYZING, "草稿资格已确认，开始分析题意")
        return get(id)
    }

    /** 将 Worker 的真实生成与差分结果写入草稿，并据此形成不可伪造的发布门禁。 */
    @Transactional
    internal fun acceptSandboxResult(
        jobId: UUID,
        runId: UUID,
        task: AiSandboxTaskPayload,
        completion: AiSandboxCompletion,
        verified: VerifiedAiSandboxResult,
    ) {
        val run = jdbc.query(
            "SELECT problem_version_id, state, created_by, auto_publish, requested_sample_count FROM ai_problem_run WHERE id = ? FOR UPDATE",
            { result, _ ->
                AiPublicationOptions(
                    problemVersionId = result.getObject("problem_version_id", UUID::class.java),
                    state = AiWorkflowState.valueOf(result.getString("state")),
                    createdBy = result.getObject("created_by", UUID::class.java),
                    autoPublish = result.getBoolean("auto_publish"),
                    sampleCount = result.getInt("requested_sample_count"),
                )
            },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_RUN_NOT_FOUND", "AI 运行不存在")
        if (run.state != AiWorkflowState.DIFFERENTIAL_TESTING) {
            throw ApiException(HttpStatus.CONFLICT, "AI_VALIDATION_NOT_READY", "当前状态不能结算 AI 差分任务")
        }
        AiWorkflow.requireTransition(run.state, AiWorkflowState.VALIDATING)

        val caseCount = verified.testCases.size
        val baseScore = 100 / caseCount
        val remainder = 100 % caseCount
        val testCases = verified.testCases.mapIndexed { index, testCase ->
            AiGeneratedTestCase(
                seed = testCase.seed,
                input = testCase.input,
                output = testCase.expectedOutput,
                score = baseScore + if (index < remainder) 1 else 0,
                sample = run.autoPublish && index < run.sampleCount,
            )
        }
        // 题意分析与审查均由 Python Agent 完成；到达沙箱回调即视为已处理歧义。
        val noUnresolvedAmbiguity = true
        val gate = AiPublicationGate(
            solutionsAgree = completion.solutionsAgree,
            bruteForcePassed = completion.bruteForcePassed,
            // 该字段只兼容历史 JSON，AI 流程不再把总分作为通过条件。
            scoreSumIsOneHundred = true,
            deterministic = completion.deterministic,
            resourceMarginPassed = verified.maximumTimePercent <= RESOURCE_MARGIN_PERCENT &&
                verified.maximumMemoryPercent <= RESOURCE_MARGIN_PERCENT,
            noUnresolvedAmbiguity = noUnresolvedAmbiguity,
        )
        val evidence = AiValidationEvidence(
            solutionOutputHashesAgree = completion.solutionsAgree,
            bruteForceJobIds = listOf(jobId),
            differentialJobIds = listOf(jobId),
            reproducedSeeds = task.seeds,
            artifactHashes = buildList {
                add(SecureValues.sha256(task.solutionASource))
                add(SecureValues.sha256(task.solutionBSource))
                add(SecureValues.sha256(task.bruteForceSource))
                add(SecureValues.sha256(task.generatorSource))
                add(SecureValues.sha256(task.validatorSource))
                verified.testCases.forEach { testCase ->
                    add(testCase.inputSha256.lowercase())
                    add(testCase.outputSha256.lowercase())
                }
            }.distinct(),
            maximumTimePercent = verified.maximumTimePercent,
            maximumMemoryPercent = verified.maximumMemoryPercent,
        )
        val next = if (gate.allowsPublication()) AiWorkflowState.VALIDATING else AiWorkflowState.NEEDS_REVIEW
        val reason = if (next == AiWorkflowState.NEEDS_REVIEW) failedGateReason(gate) else null
        if (next == AiWorkflowState.VALIDATING) {
            // 测试点和源码必须在同一事务内、且所有确定性门禁通过后才落库。
            problems.replaceDraftTestCasesFromAi(run.problemVersionId, runId, run.createdBy, testCases)
            // 源码只能取自本次 Worker 已执行的不可变 payload，不能接受回调重新上传。
            val reference = ReferenceSolutionSelector.select(task.solutionASource, task.solutionBSource, completion.testCases)
            jdbc.update(
                """
                INSERT INTO problem_reference_solution(
                    id, problem_version_id, language, source_code, source_sha256,
                    generated_by_run_id, sandbox_job_id, selected_candidate
                ) VALUES (?, ?, 'CPP17', ?, ?, ?, ?, ?)
                ON CONFLICT(problem_version_id) DO UPDATE SET
                    source_code = EXCLUDED.source_code,
                    source_sha256 = EXCLUDED.source_sha256,
                    generated_by_run_id = EXCLUDED.generated_by_run_id,
                    sandbox_job_id = EXCLUDED.sandbox_job_id,
                    selected_candidate = EXCLUDED.selected_candidate,
                    created_at = now()
                """.trimIndent(),
                UUID.randomUUID(), run.problemVersionId, reference.sourceCode,
                SecureValues.sha256(reference.sourceCode), runId, jobId, reference.candidate,
            )
        }
        jdbc.update(
            """
            UPDATE ai_problem_run
            SET state = ?, publication_gate = ?::jsonb, validation_evidence = ?::jsonb,
                failure_reason = ?, next_run_at = now(), coordinator_lease = NULL,
                coordinator_lease_expires_at = NULL, updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            next.name,
            mapper.writeValueAsString(gate),
            mapper.writeValueAsString(evidence),
            reason,
            runId,
        )
        recordStateTransition(
            runId,
            run.state,
            next,
            reason ?: "已生成 $caseCount 个测试点，确定性差分和发布门禁全部通过",
            publicationGatePassed = next == AiWorkflowState.VALIDATING,
        )
        if (next == AiWorkflowState.VALIDATING && run.autoPublish) {
            publishAfterGate(runId)
        }
    }

    /** AI 生成源码或差分结果不合格时进入人工审查，并保留完整失败原因。 */
    @Transactional
    internal fun failSandboxValidation(runId: UUID, reason: String) {
        val state = jdbc.query(
            "SELECT state FROM ai_problem_run WHERE id = ? FOR UPDATE",
            { result, _ -> AiWorkflowState.valueOf(result.getString("state")) },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_RUN_NOT_FOUND", "AI 运行不存在")
        if (state != AiWorkflowState.DIFFERENTIAL_TESTING) return
        AiWorkflow.requireTransition(state, AiWorkflowState.NEEDS_REVIEW)
        jdbc.update(
            """
            UPDATE ai_problem_run SET state = 'NEEDS_REVIEW', failure_reason = ?,
                coordinator_lease = NULL, coordinator_lease_expires_at = NULL, updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            reason.take(2_000),
            runId,
        )
        recordStateTransition(runId, state, AiWorkflowState.NEEDS_REVIEW, reason)
    }

    /** 前两轮差分失败交给 Python Agent 定向修复，第三轮才进入人工接管。 */
    @Transactional
    internal fun prepareSandboxRepair(runId: UUID, reason: String) {
        val state = jdbc.query(
            "SELECT state FROM ai_problem_run WHERE id = ? FOR UPDATE",
            { result, _ -> AiWorkflowState.valueOf(result.getString("state")) },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_RUN_NOT_FOUND", "AI 运行不存在")
        if (state != AiWorkflowState.DIFFERENTIAL_TESTING) return
        AiWorkflow.requireRepair(state, AiWorkflowState.GENERATING_TESTS)
        jdbc.update(
            "UPDATE ai_problem_run SET state = 'GENERATING_TESTS', failure_reason = ?, updated_at = now() WHERE id = ?",
            reason.take(2_000),
            runId,
        )
        recordStateTransition(runId, state, AiWorkflowState.GENERATING_TESTS, "差分未通过，Agent 将针对失败原因修复测试生成器")
    }

    /** 汇总未通过的门禁，直接展示给管理员定位人工处理项。 */
    private fun failedGateReason(gate: AiPublicationGate): String {
        val failures = buildList {
            if (!gate.solutionsAgree) add("两份标程输出不一致")
            if (!gate.bruteForcePassed) add("小数据暴力差分未通过")
            if (!gate.deterministic) add("固定种子不能复现输入")
            if (!gate.resourceMarginPassed) add("标程资源用量超过题目限制的 70%")
            if (!gate.noUnresolvedAmbiguity) add("仍有未解决的题意歧义")
        }
        return ("确定性发布门禁未全部通过：" + failures.joinToString("；")).take(2_000)
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
        jdbc.update(
            """
            UPDATE ai_problem_run SET state = 'CANCELED', coordinator_lease = NULL,
                coordinator_lease_expires_at = NULL, updated_at = now() WHERE id = ?
            """.trimIndent(),
            runId,
        )
        jdbc.update(
            "UPDATE ai_sandbox_job SET status = 'CANCELED', completed_at = now() WHERE run_id = ? AND status IN ('QUEUED', 'LEASED')",
            runId,
        )
        // 取消通知也进入 outbox，Agent 暂时不可用时由调度器重试，不阻塞管理员请求。
        jdbc.update(
            """
            INSERT INTO ai_agent_outbox(idempotency_key, event_type, run_id, payload)
            VALUES (?, 'CANCEL_RUN', ?, '{}'::jsonb)
            ON CONFLICT(idempotency_key) DO NOTHING
            """.trimIndent(),
            "cancel:$runId",
            runId,
        )
        recordStateTransition(runId, state, AiWorkflowState.CANCELED, "管理员取消 AI 录题流程")
        return get(runId)
    }

    /** 人工接管后恢复指定的失败阶段，回传澄清内容并唤醒 Agent 重新执行。 */
    @Transactional
    fun resume(runId: UUID, request: ResumeAiRunRequest): AiRunResponse {
        val target = resumeTarget(request.action)
        val row = jdbc.query(
            "SELECT state, resume_target FROM ai_problem_run WHERE id = ? FOR UPDATE",
            { result, _ -> result.getString("state") to result.getString("resume_target") },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_RUN_NOT_FOUND", "AI 运行不存在")
        val current = AiWorkflowState.valueOf(row.first)
        if (current != AiWorkflowState.NEEDS_REVIEW) {
            throw ApiException(HttpStatus.CONFLICT, "AI_RUN_NOT_NEEDS_REVIEW", "只有处于人工接管的运行可以恢复")
        }
        if (row.second != target.name) {
            throw ApiException(HttpStatus.CONFLICT, "AI_RESUME_TARGET_MISMATCH", "恢复动作与失败阶段不一致")
        }
        AiWorkflow.requireResume(current, target)
        val correctionNode = request.correction?.takeIf { !it.isNull } ?: mapper.createObjectNode()
        jdbc.update(
            """
            UPDATE ai_problem_run
            SET state = ?, resume_target = NULL, human_correction = ?::jsonb, failure_reason = NULL,
                next_run_at = now(), coordinator_lease = NULL, coordinator_lease_expires_at = NULL,
                updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            target.name,
            mapper.writeValueAsString(correctionNode),
            runId,
        )
        jdbc.update(
            """
            INSERT INTO ai_agent_outbox(idempotency_key, event_type, run_id, payload)
            VALUES (?, 'RESUME_RUN', ?, ?::jsonb)
            ON CONFLICT(idempotency_key) DO NOTHING
            """.trimIndent(),
            "resume:$runId:${target.name}:${UUID.randomUUID()}",
            runId,
            mapper.writeValueAsString(mapOf("action" to request.action, "correction" to correctionNode)),
        )
        recordStateTransition(runId, AiWorkflowState.NEEDS_REVIEW, target, "管理员已修复，重新执行题意分析或对抗审查")
        return get(runId)
    }

    /** 将管理员恢复动作映射到需要重新执行的阶段小状态。 */
    private fun resumeTarget(action: String): AiWorkflowState = when (action) {
        "reanalyze" -> AiWorkflowState.ANALYZING
        "rereview" -> AiWorkflowState.REVIEWING
        else -> throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESUME_ACTION", "不支持的恢复动作：$action")
    }

    /** 读取运行及已完成角色，不返回密钥或完整提示词。 */
    fun get(runId: UUID): AiRunResponse {
        val steps = loadSteps(runId)
        val completed = steps.map(AiStepResponse::role).distinct()
        val row = jdbc.query(
            """
            SELECT id, problem_version_id, state, repair_round, requested_test_case_count, auto_publish,
                   requested_sample_count, model, prompt_version,
                   cost_microunits, failure_reason, resume_target, publication_gate::text, created_at
            FROM ai_problem_run WHERE id = ?
            """.trimIndent(),
            { result, _ ->
                AiRunRecord(
                    id = result.getObject("id", UUID::class.java),
                    problemVersionId = result.getObject("problem_version_id", UUID::class.java),
                    state = AiWorkflowState.valueOf(result.getString("state")),
                    repairRound = result.getInt("repair_round"),
                    requestedTestCaseCount = result.getInt("requested_test_case_count"),
                    autoPublish = result.getBoolean("auto_publish"),
                    requestedSampleCount = result.getInt("requested_sample_count"),
                    model = result.getString("model"),
                    promptVersion = result.getString("prompt_version"),
                    costMicrounits = result.getLong("cost_microunits"),
                    failureReason = result.getString("failure_reason"),
                    resumeTarget = result.getString("resume_target")?.let { AiWorkflowState.valueOf(it) },
                    publicationGate = result.getString("publication_gate"),
                    createdAt = result.getTimestamp("created_at").toInstant(),
                )
            },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "AI_RUN_NOT_FOUND", "AI 运行不存在")
        val gatePassed = row.publicationGate?.let { mapper.readValue(it, AiPublicationGate::class.java).allowsPublication() } == true
        return AiRunResponse(
            id = row.id,
            problemVersionId = row.problemVersionId,
            state = row.state,
            majorState = AiWorkflow.majorState(row.state, gatePassed),
            repairRound = row.repairRound,
            requestedTestCaseCount = row.requestedTestCaseCount,
            autoPublish = row.autoPublish,
            requestedSampleCount = row.requestedSampleCount,
            model = row.model,
            promptVersion = row.promptVersion,
            costMicrounits = row.costMicrounits,
            failureReason = row.failureReason,
            resumeTarget = row.resumeTarget,
            completedRoles = completed,
            steps = steps,
            history = loadHistory(runId).ifEmpty {
                listOf(
                    AiStateHistoryEntry(
                        majorState = AiWorkflow.majorState(row.state, gatePassed),
                        state = row.state,
                        message = "历史记录未迁移，仅显示当前状态",
                        createdAt = row.createdAt,
                    ),
                )
            },
            generatedTestCases = problems.aiGeneratedTestCases(runId),
            referenceSolutionSaved = referenceSolutionExists(row.problemVersionId),
        )
    }

    /** 管理员读取数据库中的标准答案；不经过 ArtifactStore。 */
    fun referenceSolution(runId: UUID): ReferenceSolutionResponse {
        return jdbc.query(
            """
            SELECT rs.language, rs.source_code, rs.source_sha256, rs.selected_candidate,
                   rs.sandbox_job_id, rs.created_at
            FROM problem_reference_solution rs
            JOIN ai_problem_run r ON r.problem_version_id = rs.problem_version_id
            WHERE r.id = ?
            """.trimIndent(),
            { result, _ ->
                ReferenceSolutionResponse(
                    language = result.getString("language"),
                    sourceCode = result.getString("source_code"),
                    sourceSha256 = result.getString("source_sha256"),
                    selectedCandidate = result.getString("selected_candidate"),
                    sandboxJobId = result.getObject("sandbox_job_id", UUID::class.java),
                    createdAt = result.getTimestamp("created_at").toInstant(),
                )
            },
            runId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "REFERENCE_SOLUTION_NOT_FOUND", "标准答案尚未生成")
    }

    /** 发布状态只暴露布尔值，源码需通过独立管理员接口读取。 */
    private fun referenceSolutionExists(problemVersionId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM problem_reference_solution WHERE problem_version_id = ?)",
        Boolean::class.java,
        problemVersionId,
    ) ?: false

    /** 读取 Agent 返回；不读取 request_json，避免把题面上下文和内部提示词暴露给页面。 */
    private fun loadSteps(runId: UUID): List<AiStepResponse> = jdbc.query(
        """
        SELECT id, role, state,
               COALESCE(response_json, structured_response)::text AS response_payload,
               cost_microunits, content_sha256, finished_at, failure_reason
        FROM ai_problem_step
        WHERE run_id = ?
        ORDER BY ordinal, id
        """.trimIndent(),
        { result, _ ->
            val rawResponse = result.getString("response_payload")
            AiStepResponse(
                id = result.getObject("id", UUID::class.java),
                role = result.getString("role"),
                state = AiWorkflowState.valueOf(result.getString("state")),
                response = rawResponse?.let { payload -> runCatching { mapper.readTree(payload) }.getOrNull() },
                rawResponse = rawResponse,
                costMicrounits = result.getLong("cost_microunits"),
                contentSha256 = result.getString("content_sha256"),
                finishedAt = result.getTimestamp("finished_at")?.toInstant(),
                failureReason = result.getString("failure_reason"),
            )
        },
        runId,
    )

    /** 查询指定草稿当前仍未结束的 AI 运行，供草稿编辑页恢复状态。 */
    fun activeForVersion(problemVersionId: UUID): AiRunResponse? {
        val runId = jdbc.query(
            """
            SELECT id FROM ai_problem_run
            WHERE problem_version_id = ? AND state NOT IN ('PUBLISHED', 'FAILED', 'CANCELED')
            ORDER BY updated_at DESC, created_at DESC LIMIT 1
            """.trimIndent(),
            { result, _ -> result.getObject("id", UUID::class.java) },
            problemVersionId,
        ).firstOrNull() ?: return null
        return get(runId)
    }

    /** 读取状态变化时间线；历史表新增前的旧运行允许为空。 */
    private fun loadHistory(runId: UUID): List<AiStateHistoryEntry> = jdbc.query(
        """
        SELECT major_state, to_state, message, created_at
        FROM ai_problem_state_history
        WHERE run_id = ? ORDER BY created_at, id
        """.trimIndent(),
        { result, _ ->
            AiStateHistoryEntry(
                majorState = AiMajorState.valueOf(result.getString("major_state")),
                state = AiWorkflowState.valueOf(result.getString("to_state")),
                message = result.getString("message"),
                createdAt = result.getTimestamp("created_at").toInstant(),
            )
        },
        runId,
    )

    /** 追加状态历史；不修改已有小状态，支持重启后恢复时间线。 */
    private fun recordStateTransition(
        runId: UUID,
        from: AiWorkflowState?,
        to: AiWorkflowState,
        message: String? = null,
        publicationGatePassed: Boolean = false,
    ) {
        jdbc.update(
            """
            INSERT INTO ai_problem_state_history(id, run_id, from_state, to_state, major_state, message)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            runId,
            from?.name,
            to.name,
            AiWorkflow.majorState(to, publicationGatePassed).name,
            message?.take(2_000),
        )
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
        val referenceValid = jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM problem_reference_solution
                WHERE problem_version_id = ?
                  AND source_sha256 = encode(digest(convert_to(source_code, 'UTF8'), 'sha256'), 'hex')
            )
            """.trimIndent(),
            Boolean::class.java,
            row.first,
        ) ?: false
        if (!referenceValid) {
            throw ApiException(HttpStatus.CONFLICT, "REFERENCE_SOLUTION_MISSING", "标准答案缺失或源码哈希不匹配")
        }
        problems.publish(row.first)
        jdbc.update(
            """
            UPDATE ai_problem_run SET state = 'PUBLISHED', coordinator_lease = NULL,
                coordinator_lease_expires_at = NULL, updated_at = now() WHERE id = ?
            """.trimIndent(),
            runId,
        )
        recordStateTransition(runId, AiWorkflowState.VALIDATING, AiWorkflowState.PUBLISHED, "全部发布门禁通过，题目版本已发布")
    }

    private companion object {
        /** 单份 AI 生成源码允许进入沙箱的最大字节数。 */
        const val MAX_AI_SOURCE_BYTES: Int = 128 * 1024

        /** 自动发布要求标程在时间和内存限制内至少保留三成余量。 */
        const val RESOURCE_MARGIN_PERCENT: Int = 70
    }
}

/** 管理员 AI 录题与人工接管接口。 */
@RestController
@RequestMapping("/api/v1/admin/test-generation-runs")
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

    /** 按题目草稿查询当前 AI 运行，便于编辑页刷新后恢复时间线。 */
    @GetMapping("/by-version/{problemVersionId}")
    fun active(@PathVariable problemVersionId: UUID): ResponseEntity<AiRunResponse> {
        val run = service.activeForVersion(problemVersionId) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(run)
    }

    /** 读取通过 Worker 验证并直接保存在数据库中的标准答案。 */
    @GetMapping("/{runId}/reference-solution")
    fun referenceSolution(@PathVariable runId: UUID): ReferenceSolutionResponse = service.referenceSolution(runId)

    /** 取消尚未结束的 AI 流程。 */
    @PostMapping("/{runId}/cancel")
    fun cancel(@PathVariable runId: UUID): AiRunResponse = service.cancel(runId)

    /** 人工接管后恢复指定的失败阶段并回传给 Agent 重新执行。 */
    @PostMapping("/{runId}/resume")
    fun resume(
        @PathVariable runId: UUID,
        @Valid @RequestBody body: ResumeAiRunRequest,
    ): AiRunResponse = service.resume(runId, body)
}
