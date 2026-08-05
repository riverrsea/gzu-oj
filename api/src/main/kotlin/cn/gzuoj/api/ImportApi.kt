package cn.gzuoj.api

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** 暂存导入的逐题预览。 */
data class ImportItemResponse(
    /** 来源键。 */
    val sourceKey: String,
    /** 校验状态。 */
    val status: String,
    /** 内容哈希。 */
    val contentSha256: String?,
    /** 校验错误。 */
    val errors: List<String>,
    /** 预览标题。 */
    val title: String?,
    /** 测试点数量。 */
    val testCaseCount: Int,
)

/** 导入批次预览。 */
data class ImportBatchResponse(
    /** 批次标识。 */
    val id: UUID,
    /** 批次状态。 */
    val status: String,
    /** 逐题校验结果。 */
    val items: List<ImportItemResponse>,
)

/** 批次提交结果。 */
data class ImportCommitResponse(
    /** 创建的新草稿版本数。 */
    val imported: Int,
    /** 内容完全一致而跳过的题目数。 */
    val skipped: Int,
    /** 校验失败且未导入的题目数。 */
    val invalid: Int,
)

/** 暂存、预览和提交导入批次。 */
@Service
class ImportService(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
    /** 文件制品存储。 */
    private val artifacts: ArtifactStore,
    /** 题目版本服务。 */
    private val problems: ProblemService,
    /** JSON 编解码器。 */
    private val mapper: ObjectMapper,
) {
    /** 上传并完整校验导入包。 */
    @Transactional
    fun stage(file: MultipartFile, creator: UUID): ImportBatchResponse {
        if (file.size !in 1..SafeImportArchive.MAX_ARCHIVE_BYTES.toLong()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMPORT_SIZE", "导入 ZIP 大小不合法")
        }
        val bytes = file.bytes
        val parsed = try {
            ProblemImportParser().parse(bytes)
        } catch (failure: IllegalArgumentException) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMPORT_ARCHIVE", failure.message ?: "导入包不合法")
        }
        val stored = artifacts.put("imports", bytes, "application/zip")
        val artifactId = UUID.randomUUID()
        val batchId = UUID.randomUUID()
        try {
            jdbc.update(
                "INSERT INTO artifact(id, storage_key, sha256, size_bytes, media_type, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                artifactId,
                stored.storageKey,
                stored.sha256,
                stored.sizeBytes,
                stored.mediaType,
                creator,
            )
            jdbc.update(
                "INSERT INTO import_batch(id, created_by, archive_artifact_id, status) VALUES (?, ?, ?, 'VALIDATED')",
                batchId,
                creator,
                artifactId,
            )
            parsed.forEachIndexed { index, result ->
                val problem = result.getOrNull()
                val error = result.exceptionOrNull()?.message ?: "未知校验错误"
                val sourceKey = problem?.sourceKey ?: "invalid-" + (index + 1)
                val preview = if (problem == null) {
                    emptyMap<String, Any>()
                } else {
                    mapOf(
                        "title" to problem.title,
                        "school" to problem.school,
                        "year" to problem.year,
                        "testCaseCount" to problem.testCases.size,
                    )
                }
                jdbc.update(
                    """
                    INSERT INTO import_item(id, batch_id, source_key, content_sha256, status, errors, preview)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    batchId,
                    sourceKey,
                    problem?.contentSha256,
                    if (problem == null) "INVALID" else "VALID",
                    mapper.writeValueAsString(if (problem == null) listOf(error) else emptyList<String>()),
                    mapper.writeValueAsString(preview),
                )
            }
            return batch(batchId, creator)
        } catch (failure: Exception) {
            artifacts.delete(stored.storageKey)
            throw failure
        }
    }

    /** 查询属于当前管理员的批次预览。 */
    fun batch(batchId: UUID, creator: UUID): ImportBatchResponse {
        val status = jdbc.query(
            "SELECT status FROM import_batch WHERE id = ? AND created_by = ?",
            { result, _ -> result.getString("status") },
            batchId,
            creator,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "IMPORT_BATCH_NOT_FOUND", "导入批次不存在")
        val items = jdbc.query(
            "SELECT source_key, status, content_sha256, errors::text, preview::text FROM import_item WHERE batch_id = ? ORDER BY source_key",
            { result, _ ->
                val errors = mapper.readerForListOf(String::class.java)
                    .readValue<List<String>>(result.getString("errors"))
                val preview = mapper.readTree(result.getString("preview"))
                ImportItemResponse(
                    sourceKey = result.getString("source_key"),
                    status = result.getString("status"),
                    contentSha256 = result.getString("content_sha256"),
                    errors = errors,
                    title = preview.path("title").takeUnless { it.isMissingNode }?.asText(),
                    testCaseCount = preview.path("testCaseCount").asInt(0),
                )
            },
            batchId,
        )
        return ImportBatchResponse(batchId, status, items)
    }

    /** 重读原始制品并将有效条目导入为草稿版本。 */
    @Transactional
    fun commit(batchId: UUID, creator: UUID): ImportCommitResponse {
        val archiveKey = jdbc.query(
            """
            SELECT a.storage_key FROM import_batch b JOIN artifact a ON a.id = b.archive_artifact_id
            WHERE b.id = ? AND b.created_by = ? AND b.status = 'VALIDATED' FOR UPDATE OF b
            """.trimIndent(),
            { result, _ -> result.getString("storage_key") },
            batchId,
            creator,
        ).firstOrNull() ?: throw ApiException(
            HttpStatus.CONFLICT,
            "IMPORT_BATCH_NOT_READY",
            "导入批次不存在或已提交",
        )
        val parsed = artifacts.open(archiveKey).use { ProblemImportParser().parse(it.readAllBytes()) }
        var imported = 0
        var skipped = 0
        var invalid = 0
        parsed.forEach { result ->
            val problem = result.getOrNull()
            if (problem == null) {
                invalid++
                return@forEach
            }
            val unchanged = jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM problem p JOIN problem_version pv ON pv.problem_id = p.id
                    WHERE p.source_key = ? AND pv.content_sha256 = ?
                )
                """.trimIndent(),
                Boolean::class.java,
                problem.sourceKey,
                problem.contentSha256,
            ) ?: false
            if (unchanged) {
                skipped++
                jdbc.update(
                    "UPDATE import_item SET status = 'SKIPPED' WHERE batch_id = ? AND source_key = ?",
                    batchId,
                    problem.sourceKey,
                )
                return@forEach
            }
            problems.createVersion(problem.toCreateRequest(), creator, problem.contentSha256)
            imported++
            jdbc.update(
                "UPDATE import_item SET status = 'IMPORTED' WHERE batch_id = ? AND source_key = ?",
                batchId,
                problem.sourceKey,
            )
        }
        jdbc.update(
            "UPDATE import_batch SET status = 'IMPORTED', completed_at = now(), summary = ?::jsonb WHERE id = ?",
            mapper.writeValueAsString(mapOf("imported" to imported, "skipped" to skipped, "invalid" to invalid)),
            batchId,
        )
        return ImportCommitResponse(imported, skipped, invalid)
    }

    /** 将规范导入模型转换为题目草稿请求。 */
    private fun ImportProblem.toCreateRequest(): CreateProblemVersionRequest = CreateProblemVersionRequest(
        sourceKey = sourceKey,
        title = title,
        school = school,
        year = year,
        tags = tags,
        difficulty = difficulty,
        sourceUrl = sourceUrl,
        statementMarkdown = statementMarkdown,
        timeLimitMs = timeLimitMs,
        memoryLimitMiB = memoryLimitMiB,
        testCases = testCases,
        publish = false,
    )
}

/** 管理员标准 ZIP 暂存导入接口。 */
@RestController
@RequestMapping("/api/v1/admin/imports")
@PreAuthorize("hasRole('ADMIN')")
class AdminImportController(
    /** 导入批次服务。 */
    private val service: ImportService,
) {
    /** 上传 ZIP 并进入暂存预览。 */
    @PostMapping
    fun stage(
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): ImportBatchResponse = service.stage(file, principal.userId)

    /** 查看逐题预览和错误。 */
    @GetMapping("/{batchId}")
    fun batch(
        @PathVariable batchId: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): ImportBatchResponse = service.batch(batchId, principal.userId)

    /** 幂等规则检查后创建草稿版本。 */
    @PostMapping("/{batchId}/commit")
    fun commit(
        @PathVariable batchId: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): ImportCommitResponse = service.commit(batchId, principal.userId)
}
