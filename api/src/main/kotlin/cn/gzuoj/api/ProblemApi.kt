package cn.gzuoj.api

import cn.gzuoj.shared.JudgeLanguage
import cn.gzuoj.shared.LanguageLimits
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID

/** 题目难度。 */
enum class ProblemDifficulty {
    /** 基础题。 */
    EASY,
    /** 综合题。 */
    MEDIUM,
    /** 高难题。 */
    HARD,
}

/** 管理员录入的一个测试点。 */
data class CreateTestCaseRequest(
    /** 测试输入；首版单题接口直接接收文本。 */
    @field:Size(max = 16 * 1024 * 1024, message = "单个输入文件不能超过 16 MiB")
    val input: String,
    /** 标准输出；必须来自可信标程，不应由模型直接猜测。 */
    @field:Size(max = 16 * 1024 * 1024, message = "单个输出文件不能超过 16 MiB")
    val output: String,
    /** 测试点分值。 */
    @field:Min(0)
    @field:Max(100)
    val score: Int,
    /** 是否作为公开样例返回。 */
    val sample: Boolean = false,
)

/** 创建题目或新版本的请求。 */
data class CreateProblemVersionRequest(
    /** 稳定来源键；相同来源键会创建新版本。 */
    @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{1,127}$", message = "来源键格式不正确")
    val sourceKey: String,
    /** 题目标题。 */
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,
    /** 学校名称；不包含学院字段。 */
    @field:NotBlank
    @field:Size(max = 200)
    val school: String,
    /** 真题年份。 */
    @field:Min(1900)
    @field:Max(2200)
    val year: Int,
    /** 题目标签。 */
    @field:Size(max = 20)
    val tags: List<@Size(max = 40) String> = emptyList(),
    /** 题目难度。 */
    val difficulty: ProblemDifficulty,
    /** 原始来源链接。 */
    @field:Size(max = 2000)
    val sourceUrl: String? = null,
    /** Markdown 题面。 */
    @field:NotBlank
    @field:Size(max = 1_000_000)
    val statementMarkdown: String,
    /** C/C++ 基准时间限制。 */
    @field:Min(100)
    @field:Max(60_000)
    val timeLimitMs: Int,
    /** C/C++ 基准内存限制。 */
    @field:Min(16)
    @field:Max(2048)
    val memoryLimitMiB: Int,
    /** 测试点；发布版本的分值必须合计为 100。 */
    @field:Size(min = 1, max = 200)
    val testCases: List<@Valid CreateTestCaseRequest>,
    /** 是否在创建后立即发布。 */
    val publish: Boolean = false,
    /** AI 生成数据的非官方声明。 */
    @field:Size(max = 200)
    val dataNotice: String? = null,
)

/** 题库列表项。 */
data class ProblemSummary(
    /** 稳定题目标识。 */
    val id: UUID,
    /** 当前发布版本标识。 */
    val versionId: UUID,
    /** 稳定来源键。 */
    val sourceKey: String,
    /** 标题。 */
    val title: String,
    /** 学校。 */
    val school: String,
    /** 年份。 */
    val year: Int,
    /** 标签。 */
    val tags: List<String>,
    /** 难度。 */
    val difficulty: ProblemDifficulty,
)

/** 每种语言向用户展示的实际限制。 */
data class LanguageLimitResponse(
    /** 编程语言。 */
    val language: JudgeLanguage,
    /** 实际时间限制，单位毫秒。 */
    val timeLimitMs: Long,
    /** 实际内存限制，单位 MiB。 */
    val memoryLimitMiB: Long,
)

/** 用户可见的公开样例。 */
data class PublicSample(
    /** 样例序号。 */
    val ordinal: Int,
    /** 样例输入。 */
    val input: String,
    /** 样例输出。 */
    val output: String,
)

/** 公开题目详情。 */
data class ProblemDetail(
    /** 稳定题目标识。 */
    val id: UUID,
    /** 不可变版本标识。 */
    val versionId: UUID,
    /** 版本号。 */
    val versionNumber: Int,
    /** 稳定来源键。 */
    val sourceKey: String,
    /** 标题。 */
    val title: String,
    /** 学校。 */
    val school: String,
    /** 年份。 */
    val year: Int,
    /** 标签。 */
    val tags: List<String>,
    /** 难度。 */
    val difficulty: ProblemDifficulty,
    /** 来源链接。 */
    val sourceUrl: String?,
    /** Markdown 题面。 */
    val statementMarkdown: String,
    /** 四种语言的实际限制。 */
    val languageLimits: List<LanguageLimitResponse>,
    /** 公开样例。 */
    val samples: List<PublicSample>,
    /** AI 练习数据声明。 */
    val dataNotice: String?,
)

/** 创建题目版本后的结果。 */
data class CreatedProblemVersionResponse(
    /** 稳定题目标识。 */
    val problemId: UUID,
    /** 新版本标识。 */
    val versionId: UUID,
    /** 新版本号。 */
    val versionNumber: Int,
    /** 当前状态。 */
    val status: String,
)

/** 管理员题库中的题目版本摘要。 */
data class AdminProblemSummary(
    /** 跨版本稳定的题目标识。 */
    val problemId: UUID,
    /** 不可变题目版本标识。 */
    val versionId: UUID,
    /** 稳定来源键。 */
    val sourceKey: String,
    /** 题目标题。 */
    val title: String,
    /** 学校名称。 */
    val school: String,
    /** 真题年份。 */
    val year: Int,
    /** 题目标签。 */
    val tags: List<String>,
    /** 题目难度。 */
    val difficulty: ProblemDifficulty,
    /** 版本号。 */
    val versionNumber: Int,
    /** 版本状态。 */
    val status: String,
    /** 测试点数量。 */
    val testCaseCount: Int,
    /** 测试点分值总和。 */
    val scoreSum: Int,
    /** 公开样例数量。 */
    val sampleCount: Int,
    /** 创建时间。 */
    val createdAt: Instant,
    /** 发布时间；草稿和未发布版本为空。 */
    val publishedAt: Instant?,
)

/** 管理员题库分页结果。 */
data class AdminProblemPage(
    /** 当前页的题目版本。 */
    val items: List<AdminProblemSummary>,
    /** 从零开始的页码。 */
    val page: Int,
    /** 每页条数。 */
    val size: Int,
    /** 符合筛选条件的总版本数。 */
    val total: Long,
)

/** 题库、版本和测试数据事务服务。 */
@Service
class ProblemService(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
    /** 文件制品存储。 */
    private val artifactStore: ArtifactStore,
) {
    /** 按条件检索已发布题目。 */
    fun list(school: String?, year: Int?, tag: String?, difficulty: ProblemDifficulty?): List<ProblemSummary> {
        val sql = StringBuilder(
            """
            SELECT p.id, pv.id AS version_id, p.source_key, pv.title, pv.school, pv.year, pv.tags, pv.difficulty
            FROM problem p JOIN problem_version pv ON pv.id = p.current_published_version_id
            WHERE pv.status = 'PUBLISHED'
            """.trimIndent(),
        )
        val args = mutableListOf<Any>()
        if (!school.isNullOrBlank()) { sql.append(" AND lower(pv.school) = lower(?)"); args += school.trim() }
        if (year != null) { sql.append(" AND pv.year = ?"); args += year }
        if (!tag.isNullOrBlank()) { sql.append(" AND ? = ANY(pv.tags)"); args += tag.trim() }
        if (difficulty != null) { sql.append(" AND pv.difficulty = ?"); args += difficulty.name }
        sql.append(" ORDER BY pv.year DESC, pv.title ASC LIMIT 500")
        return jdbc.query(sql.toString(), ::mapSummary, *args.toTypedArray())
    }

    /** 按状态和元数据筛选管理员可见的全部题目版本。 */
    fun adminList(
        keyword: String?,
        school: String?,
        year: Int?,
        tag: String?,
        difficulty: ProblemDifficulty?,
        status: String?,
        page: Int,
        size: Int,
    ): AdminProblemPage {
        val normalizedPage = page.coerceAtLeast(0)
        val normalizedSize = size.coerceIn(1, 100)
        val filters = adminProblemFilters(keyword, school, year, tag, difficulty, status)
        val total = jdbc.queryForObject(
            "SELECT count(*) FROM problem p JOIN problem_version pv ON pv.problem_id = p.id ${filters.first}",
            Long::class.javaObjectType,
            *filters.second.toTypedArray(),
        ) ?: 0L
        val items = jdbc.query(
            """
            SELECT p.id AS problem_id, pv.id AS version_id, p.source_key, pv.title, pv.school, pv.year,
                   pv.tags, pv.difficulty, pv.version_number, pv.status,
                   count(tc.id) AS test_case_count,
                   coalesce(sum(tc.score), 0) AS score_sum,
                   count(tc.id) FILTER (WHERE tc.sample) AS sample_count,
                   pv.created_at, pv.published_at
            FROM problem p
            JOIN problem_version pv ON pv.problem_id = p.id
            LEFT JOIN problem_test_case tc ON tc.problem_version_id = pv.id
            ${filters.first}
            GROUP BY p.id, pv.id
            ORDER BY pv.created_at DESC, pv.version_number DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            ::mapAdminSummary,
            *(filters.second + listOf(normalizedSize, normalizedPage.toLong() * normalizedSize)).toTypedArray(),
        )
        return AdminProblemPage(items, normalizedPage, normalizedSize, total)
    }

    /** 构造管理员题库查询的安全筛选条件和参数。 */
    private fun adminProblemFilters(
        keyword: String?,
        school: String?,
        year: Int?,
        tag: String?,
        difficulty: ProblemDifficulty?,
        status: String?,
    ): Pair<String, List<Any>> {
        val where = StringBuilder("WHERE 1 = 1")
        val args = mutableListOf<Any>()
        keyword?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND (p.source_key ILIKE ? OR pv.title ILIKE ?)")
            val pattern = "%$it%"
            args += pattern
            args += pattern
        }
        school?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND pv.school ILIKE ?")
            args += "%$it%"
        }
        if (year != null) {
            where.append(" AND pv.year = ?")
            args += year
        }
        tag?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND ? = ANY(pv.tags)")
            args += it
        }
        if (difficulty != null) {
            where.append(" AND pv.difficulty = ?")
            args += difficulty.name
        }
        status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in setOf("DRAFT", "PUBLISHED", "WITHDRAWN")) {
                throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROBLEM_STATUS", "题目版本状态不合法")
            }
            where.append(" AND pv.status = ?")
            args += it
        }
        return where.toString() to args
    }

    /** 读取当前已发布版本及公开样例。 */
    fun detail(problemId: UUID): ProblemDetail {
        val detail = jdbc.query(
            """
            SELECT p.id, p.source_key, pv.id AS version_id, pv.version_number, pv.title, pv.school,
                   pv.year, pv.tags, pv.difficulty, pv.source_url, pv.statement_markdown,
                   pv.time_limit_ms, pv.memory_limit_mib, pv.data_notice
            FROM problem p JOIN problem_version pv ON pv.id = p.current_published_version_id
            WHERE p.id = ? AND pv.status = 'PUBLISHED'
            """.trimIndent(),
            { result, _ -> mapDetail(result) },
            problemId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "PROBLEM_NOT_FOUND", "题目不存在")
        return detail.copy(samples = loadSamples(detail.versionId))
    }

    /** 读取一个已经发布的不可变历史版本，供比赛和套卷锁定使用。 */
    fun detailVersion(versionId: UUID): ProblemDetail {
        val detail = jdbc.query(
            """
            SELECT p.id, p.source_key, pv.id AS version_id, pv.version_number, pv.title, pv.school,
                   pv.year, pv.tags, pv.difficulty, pv.source_url, pv.statement_markdown,
                   pv.time_limit_ms, pv.memory_limit_mib, pv.data_notice
            FROM problem_version pv JOIN problem p ON p.id = pv.problem_id
            WHERE pv.id = ? AND pv.status = 'PUBLISHED'
            """.trimIndent(),
            { result, _ -> mapDetail(result) },
            versionId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "PROBLEM_VERSION_NOT_FOUND", "题目版本不存在或尚未发布")
        return detail.copy(samples = loadSamples(detail.versionId))
    }

    /** 映射公开题目详情及语言倍率。 */
    private fun mapDetail(result: java.sql.ResultSet): ProblemDetail {
        val baseTime = result.getLong("time_limit_ms")
        val baseMemory = result.getLong("memory_limit_mib")
        return ProblemDetail(
            id = result.getObject("id", UUID::class.java),
            versionId = result.getObject("version_id", UUID::class.java),
            versionNumber = result.getInt("version_number"),
            sourceKey = result.getString("source_key"),
            title = result.getString("title"),
            school = result.getString("school"),
            year = result.getInt("year"),
            tags = (result.getArray("tags").array as Array<*>).map { it.toString() },
            difficulty = ProblemDifficulty.valueOf(result.getString("difficulty")),
            sourceUrl = result.getString("source_url"),
            statementMarkdown = result.getString("statement_markdown"),
            languageLimits = JudgeLanguage.entries.map { language ->
                val multiplier = LanguageLimits.multiplier(language)
                LanguageLimitResponse(language, baseTime * multiplier.time, baseMemory * multiplier.memory)
            },
            samples = emptyList(),
            dataNotice = result.getString("data_notice"),
        )
    }

    /** 创建稳定题目或在已有题目下创建不可变新版本。 */
    @Transactional
    fun createVersion(
        request: CreateProblemVersionRequest,
        creator: UUID,
        contentHashOverride: String? = null,
    ): CreatedProblemVersionResponse {
        if (request.publish && request.testCases.sumOf { it.score } != 100) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCORE_SUM", "发布版本的测试点分值之和必须为 100")
        }
        if (request.tags.map { it.trim().lowercase() }.distinct().size != request.tags.size) {
            throw ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_TAG", "题目标签不能重复")
        }
        val stored = mutableListOf<StoredArtifact>()
        try {
            request.testCases.forEach { test ->
                stored += artifactStore.put("test-data", test.input.toByteArray(), "text/plain; charset=utf-8")
                stored += artifactStore.put("test-data", test.output.toByteArray(), "text/plain; charset=utf-8")
            }
            val problemId = findOrCreateProblem(request.sourceKey, creator)
            val versionNumber = jdbc.queryForObject(
                "SELECT coalesce(max(version_number), 0) + 1 FROM problem_version WHERE problem_id = ?",
                Int::class.java,
                problemId,
            ) ?: 1
            val versionId = UUID.randomUUID()
            val contentHash = contentHashOverride ?: hashProblem(request)
            jdbc.update(
                """
                INSERT INTO problem_version(
                    id, problem_id, version_number, title, school, year, tags, difficulty, source_url,
                    statement_markdown, time_limit_ms, memory_limit_mib, status, data_notice,
                    content_sha256, created_by, published_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                versionId,
                problemId,
                versionNumber,
                request.title.trim(),
                request.school.trim(),
                request.year,
                request.tags.map { it.trim() }.toTypedArray(),
                request.difficulty.name,
                request.sourceUrl?.trim()?.takeIf { it.isNotEmpty() },
                request.statementMarkdown,
                request.timeLimitMs,
                request.memoryLimitMiB,
                if (request.publish) "PUBLISHED" else "DRAFT",
                request.dataNotice,
                contentHash,
                creator,
                if (request.publish) Timestamp.from(Instant.now()) else null,
            )
            request.testCases.forEachIndexed { index, test ->
                val inputId = insertArtifact(stored[index * 2], creator)
                val outputId = insertArtifact(stored[index * 2 + 1], creator)
                jdbc.update(
                    """
                    INSERT INTO problem_test_case(id, problem_version_id, ordinal, score, input_artifact_id, output_artifact_id, sample)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(), versionId, index + 1, test.score, inputId, outputId, test.sample,
                )
            }
            if (request.publish) {
                jdbc.update("UPDATE problem SET current_published_version_id = ? WHERE id = ?", versionId, problemId)
            }
            return CreatedProblemVersionResponse(
                problemId,
                versionId,
                versionNumber,
                if (request.publish) "PUBLISHED" else "DRAFT",
            )
        } catch (exception: Exception) {
            stored.forEach { runCatching { artifactStore.delete(it.storageKey) } }
            throw exception
        }
    }

    /** 发布既有草稿版本。 */
    @Transactional
    fun publish(versionId: UUID): CreatedProblemVersionResponse {
        val record = jdbc.query(
            "SELECT problem_id, version_number, status FROM problem_version WHERE id = ? FOR UPDATE",
            { result, _ -> Triple(result.getObject("problem_id", UUID::class.java), result.getInt("version_number"), result.getString("status")) },
            versionId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "题目版本不存在")
        if (record.third != "DRAFT") {
            throw ApiException(HttpStatus.CONFLICT, "VERSION_IMMUTABLE", "只有草稿版本可以发布")
        }
        val scoreSum = jdbc.queryForObject(
            "SELECT coalesce(sum(score), 0) FROM problem_test_case WHERE problem_version_id = ?",
            Int::class.java,
            versionId,
        ) ?: 0
        if (scoreSum != 100) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCORE_SUM", "发布版本的测试点分值之和必须为 100")
        }
        jdbc.update("UPDATE problem_version SET status = 'PUBLISHED', published_at = now() WHERE id = ?", versionId)
        jdbc.update("UPDATE problem SET current_published_version_id = ? WHERE id = ?", versionId, record.first)
        return CreatedProblemVersionResponse(record.first, versionId, record.second, "PUBLISHED")
    }

    /** 读取公开样例的文本制品。 */
    private fun loadSamples(versionId: UUID): List<PublicSample> = jdbc.query(
        """
        SELECT tc.ordinal, ia.storage_key AS input_key, oa.storage_key AS output_key
        FROM problem_test_case tc
        JOIN artifact ia ON ia.id = tc.input_artifact_id
        JOIN artifact oa ON oa.id = tc.output_artifact_id
        WHERE tc.problem_version_id = ? AND tc.sample = TRUE ORDER BY tc.ordinal
        """.trimIndent(),
        { result, _ ->
            PublicSample(
                result.getInt("ordinal"),
                artifactStore.open(result.getString("input_key")).bufferedReader().use { it.readText() },
                artifactStore.open(result.getString("output_key")).bufferedReader().use { it.readText() },
            )
        },
        versionId,
    )

    /** 查找或创建稳定题目记录，并对来源键串行化。 */
    private fun findOrCreateProblem(sourceKey: String, creator: UUID): UUID {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            sourceKey,
        )
        val existing = jdbc.query(
            "SELECT id FROM problem WHERE source_key = ?",
            { result, _ -> result.getObject("id", UUID::class.java) },
            sourceKey,
        ).firstOrNull()
        if (existing != null) return existing
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO problem(id, source_key, created_by) VALUES (?, ?, ?)", id, sourceKey, creator)
        return id
    }

    /** 写入单个制品元数据。 */
    private fun insertArtifact(stored: StoredArtifact, creator: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO artifact(id, storage_key, sha256, size_bytes, media_type, created_by) VALUES (?, ?, ?, ?, ?, ?)",
            id, stored.storageKey, stored.sha256, stored.sizeBytes, stored.mediaType, creator,
        )
        return id
    }

    /** 生成用于导入幂等判断的规范内容哈希。 */
    private fun hashProblem(request: CreateProblemVersionRequest): String {
        val canonical = buildString {
            appendLine(request.sourceKey)
            appendLine(request.title.trim())
            appendLine(request.school.trim())
            appendLine(request.year)
            appendLine(request.tags.joinToString(",") { it.trim() })
            appendLine(request.difficulty)
            appendLine(request.timeLimitMs)
            appendLine(request.memoryLimitMiB)
            appendLine(request.statementMarkdown.replace("\r\n", "\n"))
            request.testCases.forEach { appendLine(SecureValues.sha256(it.input)); appendLine(SecureValues.sha256(it.output)); appendLine(it.score) }
        }
        return SecureValues.sha256(canonical)
    }

    /** 映射题库列表行。 */
    private fun mapSummary(result: java.sql.ResultSet, ignored: Int): ProblemSummary = ProblemSummary(
        id = result.getObject("id", UUID::class.java),
        versionId = result.getObject("version_id", UUID::class.java),
        sourceKey = result.getString("source_key"),
        title = result.getString("title"),
        school = result.getString("school"),
        year = result.getInt("year"),
        tags = (result.getArray("tags").array as Array<*>).map { it.toString() },
        difficulty = ProblemDifficulty.valueOf(result.getString("difficulty")),
    )

    /** 将管理员题库查询结果映射为版本摘要。 */
    private fun mapAdminSummary(result: java.sql.ResultSet, ignored: Int): AdminProblemSummary = AdminProblemSummary(
        problemId = result.getObject("problem_id", UUID::class.java),
        versionId = result.getObject("version_id", UUID::class.java),
        sourceKey = result.getString("source_key"),
        title = result.getString("title"),
        school = result.getString("school"),
        year = result.getInt("year"),
        tags = (result.getArray("tags").array as Array<*>).map { it.toString() },
        difficulty = ProblemDifficulty.valueOf(result.getString("difficulty")),
        versionNumber = result.getInt("version_number"),
        status = result.getString("status"),
        testCaseCount = result.getInt("test_case_count"),
        scoreSum = result.getInt("score_sum"),
        sampleCount = result.getInt("sample_count"),
        createdAt = result.getTimestamp("created_at").toInstant(),
        publishedAt = result.getTimestamp("published_at")?.toInstant(),
    )
}

/** 公开题库接口。 */
@RestController
@RequestMapping("/api/v1/problems")
class ProblemController(
    /** 题库服务。 */
    private val service: ProblemService,
) {
    /** 按学校、年份、标签和难度筛选题库。 */
    @GetMapping
    fun list(
        @RequestParam(required = false) school: String?,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) difficulty: ProblemDifficulty?,
    ): List<ProblemSummary> = service.list(school, year, tag, difficulty)

    /** 查看题面、语言限制和公开样例。 */
    @GetMapping("/{problemId}")
    fun detail(@PathVariable problemId: UUID): ProblemDetail = service.detail(problemId)

    /** 查看比赛或套卷锁定的已发布历史版本。 */
    @GetMapping("/versions/{versionId}")
    fun detailVersion(@PathVariable versionId: UUID): ProblemDetail = service.detailVersion(versionId)
}

/** 管理员题目录入接口。 */
@RestController
@RequestMapping("/api/v1/admin/problems")
@PreAuthorize("hasRole('ADMIN')")
class AdminProblemController(
    /** 题库服务。 */
    private val service: ProblemService,
) {
    /** 分页查询全部题目版本，供管理员查看草稿、发布版本和历史版本。 */
    @GetMapping
    fun list(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) school: String?,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) difficulty: ProblemDifficulty?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): AdminProblemPage = service.adminList(keyword, school, year, tag, difficulty, status, page, size)

    /** 创建题目草稿或新版本。 */
    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateProblemVersionRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): CreatedProblemVersionResponse = service.createVersion(body, principal.userId)

    /** 发布测试点分值合格的草稿版本。 */
    @PostMapping("/versions/{versionId}/publish")
    fun publish(@PathVariable versionId: UUID): CreatedProblemVersionResponse = service.publish(versionId)
}
