package cn.gzuoj.api

import cn.gzuoj.shared.ContestRanking
import cn.gzuoj.shared.ContestScoreEvent
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID

/** 用户题单中通用的题目摘要。 */
data class UserProblemSummary(
    /** 稳定题目标识。 */
    val problemId: UUID,
    /** 当前发布版本标识。 */
    val versionId: UUID,
    /** 题目标题。 */
    val title: String,
    /** 学校名称。 */
    val school: String,
    /** 真题年份。 */
    val year: Int,
    /** 题目难度。 */
    val difficulty: ProblemDifficulty,
)

/** 错题本中的一条记录。 */
data class WrongProblemResponse(
    /** 当前题目摘要。 */
    val problem: UserProblemSummary,
    /** 历史最高分。 */
    val bestScore: Int,
    /** 首次未满分时间。 */
    val firstWrongAt: Instant,
    /** 最近一次未满分时间。 */
    val lastWrongAt: Instant,
    /** 首次达到满分的时间；未解决时为空。 */
    val solvedAt: Instant?,
)

/** 用户收藏、错题和问题反馈服务。 */
@Service
class PracticeService(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
) {
    /** 收藏一个已发布题目。 */
    @Transactional
    fun favorite(userId: UUID, problemId: UUID) {
        requirePublishedProblem(problemId)
        jdbc.update(
            "INSERT INTO favorite_problem(user_id, problem_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            userId,
            problemId,
        )
    }

    /** 取消题目收藏。 */
    @Transactional
    fun unfavorite(userId: UUID, problemId: UUID) {
        jdbc.update("DELETE FROM favorite_problem WHERE user_id = ? AND problem_id = ?", userId, problemId)
    }

    /** 返回当前用户收藏的已发布题目。 */
    fun favorites(userId: UUID): List<UserProblemSummary> = jdbc.query(
        """
        SELECT p.id, pv.id AS version_id, pv.title, pv.school, pv.year, pv.difficulty
        FROM favorite_problem f
        JOIN problem p ON p.id = f.problem_id
        JOIN problem_version pv ON pv.id = p.current_published_version_id
        WHERE f.user_id = ? ORDER BY f.created_at DESC
        """.trimIndent(),
        ::mapProblem,
        userId,
    )

    /** 返回用户曾经正式通过过的题目标识，跨题目版本合并。 */
    fun solvedProblemIds(userId: UUID): List<UUID> = jdbc.query(
        """
        SELECT DISTINCT pv.problem_id
        FROM submission s
        JOIN problem_version pv ON pv.id = s.problem_version_id
        WHERE s.user_id = ? AND s.execution_mode = 'SUBMIT' AND s.status = 'AC'
        """.trimIndent(),
        { result, _ -> result.getObject("problem_id", UUID::class.java) },
        userId,
    )

    /** 返回错题历史，可选择只查看尚未解决的题目。 */
    fun wrongProblems(userId: UUID, unresolvedOnly: Boolean): List<WrongProblemResponse> {
        val solvedFilter = if (unresolvedOnly) " AND w.solved_at IS NULL" else ""
        return jdbc.query(
            """
            SELECT p.id, pv.id AS version_id, pv.title, pv.school, pv.year, pv.difficulty,
                   w.best_score, w.first_wrong_at, w.last_wrong_at, w.solved_at
            FROM wrong_problem w
            JOIN problem p ON p.id = w.problem_id
            JOIN problem_version pv ON pv.id = p.current_published_version_id
            WHERE w.user_id = ?$solvedFilter
            ORDER BY (w.solved_at IS NULL) DESC, w.last_wrong_at DESC
            """.trimIndent(),
            { result, _ ->
                WrongProblemResponse(
                    problem = mapProblem(result, 0),
                    bestScore = result.getInt("best_score"),
                    firstWrongAt = result.getTimestamp("first_wrong_at").toInstant(),
                    lastWrongAt = result.getTimestamp("last_wrong_at").toInstant(),
                    solvedAt = result.getTimestamp("solved_at")?.toInstant(),
                )
            },
            userId,
        )
    }

    /** 将一次未通过的正式提交显式加入错题本；重复加入只刷新历史最高分。 */
    @Transactional
    fun addWrongProblem(userId: UUID, problemId: UUID, submissionId: UUID) {
        requirePublishedProblem(problemId)
        val score = jdbc.query(
            """
            SELECT s.score
            FROM submission s
            JOIN problem_version pv ON pv.id = s.problem_version_id
            WHERE s.id = ? AND s.user_id = ? AND pv.problem_id = ?
              AND s.execution_mode = 'SUBMIT'
              AND s.status IN ('PARTIAL', 'WA', 'CE', 'TLE', 'MLE', 'RE', 'OLE')
            """.trimIndent(),
            { result, _ -> result.getInt("score") },
            submissionId,
            userId,
            problemId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_WRONG_PROBLEM_SUBMISSION", "只能将本人未通过的正式提交加入错题本")
        jdbc.update(
            """
            INSERT INTO wrong_problem(user_id, problem_id, best_score)
            VALUES (?, ?, ?)
            ON CONFLICT(user_id, problem_id) DO UPDATE SET
                best_score = greatest(wrong_problem.best_score, EXCLUDED.best_score),
                last_wrong_at = now(),
                solved_at = NULL
            """.trimIndent(),
            userId,
            problemId,
            score,
        )
    }

    /** 提交当前发布版本的问题反馈。 */
    @Transactional
    fun feedback(userId: UUID, problemId: UUID, content: String): UUID {
        val versionId = requirePublishedProblem(problemId)
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO problem_feedback(id, problem_version_id, user_id, content) VALUES (?, ?, ?, ?)",
            id,
            versionId,
            userId,
            content.trim(),
        )
        return id
    }

    /** 返回当前发布版本，不存在时给出稳定错误。 */
    private fun requirePublishedProblem(problemId: UUID): UUID = jdbc.queryForObject(
        "SELECT current_published_version_id FROM problem WHERE id = ? AND current_published_version_id IS NOT NULL",
        UUID::class.java,
        problemId,
    ) ?: throw ApiException(HttpStatus.NOT_FOUND, "PROBLEM_NOT_FOUND", "题目不存在")

    /** 映射用户题单摘要。 */
    private fun mapProblem(result: java.sql.ResultSet, ignored: Int): UserProblemSummary = UserProblemSummary(
        problemId = result.getObject("id", UUID::class.java),
        versionId = result.getObject("version_id", UUID::class.java),
        title = result.getString("title"),
        school = result.getString("school"),
        year = result.getInt("year"),
        difficulty = ProblemDifficulty.valueOf(result.getString("difficulty")),
    )
}

/** 新建问题反馈请求。 */
data class CreateFeedbackRequest(
    /** 反馈正文。 */
    @field:NotBlank
    @field:Size(max = 4_000)
    val content: String,
)

/** 将一次正式提交加入错题本的请求。 */
data class AddWrongProblemRequest(
    /** 只能引用当前用户本人、同一题目的未通过正式提交。 */
    val submissionId: UUID,
)

/** 收藏、错题本与反馈接口。 */
@RestController
@RequestMapping("/api/v1")
class PracticeController(
    /** 练习数据服务。 */
    private val service: PracticeService,
) {
    /** 收藏题目。 */
    @PostMapping("/favorites/{problemId}")
    fun favorite(@PathVariable problemId: UUID, @AuthenticationPrincipal principal: AppPrincipal) =
        service.favorite(principal.userId, problemId)

    /** 取消收藏。 */
    @DeleteMapping("/favorites/{problemId}")
    fun unfavorite(@PathVariable problemId: UUID, @AuthenticationPrincipal principal: AppPrincipal) =
        service.unfavorite(principal.userId, problemId)

    /** 查询收藏列表。 */
    @GetMapping("/favorites")
    fun favorites(@AuthenticationPrincipal principal: AppPrincipal): List<UserProblemSummary> =
        service.favorites(principal.userId)

    /** 查询当前用户已经正式通过的题目 ID。 */
    @GetMapping("/solved-problems")
    fun solvedProblemIds(@AuthenticationPrincipal principal: AppPrincipal): List<UUID> =
        service.solvedProblemIds(principal.userId)

    /** 查询错题本。 */
    @GetMapping("/wrong-problems")
    fun wrongProblems(
        @RequestParam(defaultValue = "false") unresolvedOnly: Boolean,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): List<WrongProblemResponse> = service.wrongProblems(principal.userId, unresolvedOnly)

    /** 显式将一次未通过提交加入错题本。 */
    @PostMapping("/wrong-problems/{problemId}")
    fun addWrongProblem(
        @PathVariable problemId: UUID,
        @Valid @RequestBody body: AddWrongProblemRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ) = service.addWrongProblem(principal.userId, problemId, body.submissionId)

    /** 提交题目反馈。 */
    @PostMapping("/problems/{problemId}/feedback")
    fun feedback(
        @PathVariable problemId: UUID,
        @Valid @RequestBody body: CreateFeedbackRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): Map<String, UUID> = mapOf("id" to service.feedback(principal.userId, problemId, body.content))
}

/** 创建个人计时套卷请求。 */
data class CreateTimedPaperRequest(
    /** 套卷标题。 */
    @field:NotBlank
    @field:Size(max = 120)
    val title: String,
    /** 独立计时分钟数。 */
    @field:Min(15)
    @field:Max(300)
    val durationMinutes: Int,
    /** 按顺序锁定的已发布题目。 */
    @field:Size(min = 1, max = 20)
    val problemIds: List<UUID>,
)

/** 计时套卷模板。 */
data class TimedPaperResponse(
    /** 套卷标识。 */
    val id: UUID,
    /** 套卷标题。 */
    val title: String,
    /** 限时分钟数。 */
    val durationMinutes: Int,
    /** 锁定的题目版本。 */
    val problems: List<TimedPaperProblemResponse>,
)

/** 套卷中的不可变题目版本。 */
data class TimedPaperProblemResponse(
    /** 题目顺序。 */
    val ordinal: Int,
    /** 稳定题目标识。 */
    val problemId: UUID,
    /** 锁定版本标识。 */
    val versionId: UUID,
    /** 题目标题。 */
    val title: String,
)

/** 个人计时作答状态。 */
enum class TimedAttemptStatus {
    /** 正在计时，可以运行和提交。 */
    RUNNING,
    /** 计时已经冻结，不能运行或提交。 */
    PAUSED,
    /** 作答已经结束，状态不可恢复。 */
    FINISHED,
}

/** 用于统一计算截止时间和剩余时间的计时快照。 */
internal data class TimedAttemptClock(
    /** 开始计时的时刻。 */
    val startedAt: Instant,
    /** 已经结束时的结束时刻。 */
    val finishedAt: Instant?,
    /** 当前暂停开始时刻。 */
    val pausedAt: Instant?,
    /** 已完成暂停的累计毫秒数。 */
    val accumulatedPausedMs: Long,
    /** 套卷规定时长。 */
    val durationMinutes: Int,
)

/** 计算包含已完成暂停时长的当前截止时间。 */
internal fun timedAttemptExpiresAt(clock: TimedAttemptClock): Instant = clock.startedAt
    .plus(Duration.ofMinutes(clock.durationMinutes.toLong()))
    .plusMillis(clock.accumulatedPausedMs)

/** 按每道题一百分计算套卷满分。 */
internal fun timedAttemptMaximumScore(problemCount: Int): Int = problemCount * 100

/** 汇总每道题的历史最高分。 */
internal fun timedAttemptTotalScore(scores: Map<UUID, Int>): Int = scores.values.sum()

/** 在同一个时间快照下推导作答状态。 */
internal fun timedAttemptStatusAt(clock: TimedAttemptClock, currentTime: Instant): TimedAttemptStatus = when {
    clock.finishedAt != null -> TimedAttemptStatus.FINISHED
    clock.pausedAt != null -> TimedAttemptStatus.PAUSED
    !currentTime.isBefore(timedAttemptExpiresAt(clock)) -> TimedAttemptStatus.FINISHED
    else -> TimedAttemptStatus.RUNNING
}

/** 计算供前端同步的整秒剩余时间；暂停期间使用暂停时刻冻结结果。 */
internal fun timedAttemptRemainingSecondsAt(clock: TimedAttemptClock, currentTime: Instant): Long {
    if (timedAttemptStatusAt(clock, currentTime) == TimedAttemptStatus.FINISHED) return 0
    val referenceTime = clock.pausedAt ?: currentTime
    val remainingMillis = Duration.between(referenceTime, timedAttemptExpiresAt(clock)).toMillis().coerceAtLeast(0)
    return (remainingMillis + 999) / 1_000
}

/** 个人计时状态操作。 */
internal enum class TimedAttemptAction {
    /** 请求暂停。 */
    PAUSE,
    /** 请求继续。 */
    RESUME,
    /** 请求结束。 */
    FINISH,
}

/** 状态操作需要执行的持久化变化。 */
internal enum class TimedAttemptMutation {
    /** 当前状态已经满足请求，无需更新。 */
    NONE,
    /** 写入暂停时刻。 */
    PAUSE,
    /** 累加暂停时长并恢复计时。 */
    RESUME,
    /** 写入不可逆结束状态。 */
    FINISH,
    /** 已结束作答不允许执行该操作。 */
    CONFLICT,
}

/** 将重复操作统一收敛为幂等结果，并拒绝恢复已经结束的作答。 */
internal fun timedAttemptMutation(status: TimedAttemptStatus, action: TimedAttemptAction): TimedAttemptMutation = when (action) {
    TimedAttemptAction.PAUSE -> when (status) {
        TimedAttemptStatus.RUNNING -> TimedAttemptMutation.PAUSE
        TimedAttemptStatus.PAUSED -> TimedAttemptMutation.NONE
        TimedAttemptStatus.FINISHED -> TimedAttemptMutation.CONFLICT
    }
    TimedAttemptAction.RESUME -> when (status) {
        TimedAttemptStatus.RUNNING -> TimedAttemptMutation.NONE
        TimedAttemptStatus.PAUSED -> TimedAttemptMutation.RESUME
        TimedAttemptStatus.FINISHED -> TimedAttemptMutation.CONFLICT
    }
    TimedAttemptAction.FINISH -> when (status) {
        TimedAttemptStatus.RUNNING, TimedAttemptStatus.PAUSED -> TimedAttemptMutation.FINISH
        TimedAttemptStatus.FINISHED -> TimedAttemptMutation.NONE
    }
}

/** 一次个人套卷作答结果。 */
data class TimedAttemptResponse(
    /** 作答标识。 */
    val id: UUID,
    /** 套卷信息。 */
    val paper: TimedPaperResponse,
    /** 独立开始时间。 */
    val startedAt: Instant,
    /** 截止时间。 */
    val expiresAt: Instant,
    /** 是否已经结束。 */
    val finished: Boolean,
    /** 当前持久化作答状态。 */
    val status: TimedAttemptStatus,
    /** 当前剩余秒数；暂停期间保持不变。 */
    val remainingSeconds: Long,
    /** 每题最高分。 */
    val scores: Map<UUID, Int>,
    /** 总分。 */
    val totalScore: Int,
    /** 套卷满分，每道题按一百分计算。 */
    val maximumScore: Int,
)

/** 个人计时套卷服务。 */
@Service
class TimedPaperService(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
) {
    /** 返回当前用户创建的套卷模板。 */
    fun list(userId: UUID): List<TimedPaperResponse> = jdbc.queryForList(
        "SELECT id FROM timed_paper WHERE owner_id = ? ORDER BY created_at DESC LIMIT 100",
        UUID::class.java,
        userId,
    ).filterNotNull().map { getPaper(it, userId) }

    /** 返回当前用户已经开始过的套卷作答，用于重新进入页面时恢复计时状态。 */
    @Transactional
    fun listAttempts(userId: UUID): List<TimedAttemptResponse> = jdbc.queryForList(
        "SELECT id FROM timed_paper_attempt WHERE user_id = ? ORDER BY started_at DESC LIMIT 100",
        UUID::class.java,
        userId,
    ).filterNotNull().map { getAttempt(it, userId) }

    /** 创建并锁定当前发布题目版本。 */
    @Transactional
    fun create(userId: UUID, request: CreateTimedPaperRequest): TimedPaperResponse {
        if (request.problemIds.distinct().size != request.problemIds.size) {
            throw ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_PROBLEM", "套卷题目不能重复")
        }
        val versions = lockPublishedVersions(request.problemIds)
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO timed_paper(id, owner_id, title, duration_minutes) VALUES (?, ?, ?, ?)",
            id,
            userId,
            request.title.trim(),
            request.durationMinutes,
        )
        request.problemIds.forEachIndexed { index, problemId ->
            jdbc.update(
                "INSERT INTO timed_paper_problem(timed_paper_id, problem_version_id, ordinal) VALUES (?, ?, ?)",
                id,
                versions.getValue(problemId),
                index + 1,
            )
        }
        return getPaper(id, userId)
    }

    /** 首次进入时创建独立计时作答。 */
    @Transactional
    fun start(userId: UUID, paperId: UUID): TimedAttemptResponse {
        requireOwner(paperId, userId)
        // 唯一约束与冲突忽略共同保证并发重复进入不会创建第二次计时。
        val attemptId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO timed_paper_attempt(id, timed_paper_id, user_id) VALUES (?, ?, ?) ON CONFLICT (timed_paper_id, user_id) DO NOTHING",
            attemptId,
            paperId,
            userId,
        )
        val existingAttemptId = jdbc.query(
            "SELECT id FROM timed_paper_attempt WHERE timed_paper_id = ? AND user_id = ?",
            { result, _ -> result.getObject("id", UUID::class.java) },
            paperId,
            userId,
        ).singleOrNull() ?: throw IllegalStateException("个人套卷作答创建后不存在")
        return getAttempt(existingAttemptId, userId)
    }

    /** 读取私有作答，超过截止时间时自动结束。 */
    @Transactional
    fun getAttempt(attemptId: UUID, userId: UUID): TimedAttemptResponse {
        finishExpired(attemptId)
        return loadAttempt(attemptId, "a.user_id = ?", userId)
    }

    /** 暂停正在进行的作答；已经暂停时直接返回当前状态。 */
    @Transactional(noRollbackFor = [ApiException::class])
    fun pause(attemptId: UUID, userId: UUID): TimedAttemptResponse {
        val row = lockAttempt(attemptId, userId)
        val currentTime = Instant.now()
        val mutation = timedAttemptMutation(timedAttemptStatusAt(row.clock(), currentTime), TimedAttemptAction.PAUSE)
        when (mutation) {
            TimedAttemptMutation.PAUSE -> jdbc.update(
                "UPDATE timed_paper_attempt SET paused_at = ? WHERE id = ?",
                Timestamp.from(currentTime),
                attemptId,
            )
            TimedAttemptMutation.CONFLICT -> {
                finishIfExpired(row, currentTime)
                throw ApiException(HttpStatus.CONFLICT, "TIMED_ATTEMPT_FINISHED", "套卷作答已经结束")
            }
            else -> Unit
        }
        return loadAttempt(attemptId, "a.user_id = ?", userId)
    }

    /** 继续暂停中的作答，并将本次暂停时长顺延到截止时间。 */
    @Transactional(noRollbackFor = [ApiException::class])
    fun resume(attemptId: UUID, userId: UUID): TimedAttemptResponse {
        val row = lockAttempt(attemptId, userId)
        val currentTime = Instant.now()
        val mutation = timedAttemptMutation(timedAttemptStatusAt(row.clock(), currentTime), TimedAttemptAction.RESUME)
        when (mutation) {
            TimedAttemptMutation.RESUME -> {
                val pausedMillis = Duration.between(requireNotNull(row.pausedAt), currentTime).toMillis().coerceAtLeast(0)
                jdbc.update(
                    "UPDATE timed_paper_attempt SET paused_at = NULL, accumulated_paused_ms = accumulated_paused_ms + ? WHERE id = ?",
                    pausedMillis,
                    attemptId,
                )
            }
            TimedAttemptMutation.CONFLICT -> {
                finishIfExpired(row, currentTime)
                throw ApiException(HttpStatus.CONFLICT, "TIMED_ATTEMPT_FINISHED", "套卷作答已经结束")
            }
            else -> Unit
        }
        return loadAttempt(attemptId, "a.user_id = ?", userId)
    }

    /** 提前结束作答；重复调用保持结束状态不变。 */
    @Transactional
    fun finish(attemptId: UUID, userId: UUID): TimedAttemptResponse {
        val row = lockAttempt(attemptId, userId)
        val currentTime = Instant.now()
        val mutation = timedAttemptMutation(timedAttemptStatusAt(row.clock(), currentTime), TimedAttemptAction.FINISH)
        if (mutation == TimedAttemptMutation.FINISH) {
            val pausedMillis = row.pausedAt
                ?.let { Duration.between(it, currentTime).toMillis().coerceAtLeast(0) }
                ?: 0
            jdbc.update(
                """
                UPDATE timed_paper_attempt
                SET finished_at = ?, paused_at = NULL,
                    accumulated_paused_ms = accumulated_paused_ms + ?
                WHERE id = ?
                """.trimIndent(),
                Timestamp.from(currentTime),
                pausedMillis,
                attemptId,
            )
        } else if (row.finishedAt == null) {
            finishIfExpired(row, currentTime)
        }
        return loadAttempt(attemptId, "a.user_id = ?", userId)
    }

    /** 生成新的分享令牌并覆盖旧令牌。 */
    @Transactional
    fun share(attemptId: UUID, userId: UUID): String {
        getAttempt(attemptId, userId)
        val token = SecureValues.randomToken(32)
        jdbc.update(
            "UPDATE timed_paper_attempt SET share_token_hash = ?, share_revoked_at = NULL WHERE id = ? AND user_id = ?",
            SecureValues.sha256(token),
            attemptId,
            userId,
        )
        return token
    }

    /** 撤销现有分享链接。 */
    @Transactional
    fun revokeShare(attemptId: UUID, userId: UUID) {
        val updated = jdbc.update(
            "UPDATE timed_paper_attempt SET share_revoked_at = now() WHERE id = ? AND user_id = ?",
            attemptId,
            userId,
        )
        if (updated == 0) throw ApiException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "套卷作答不存在")
    }

    /** 使用只读令牌公开查看结果。 */
    fun shared(token: String): TimedAttemptResponse {
        if (token.length < 40) throw ApiException(HttpStatus.NOT_FOUND, "SHARE_NOT_FOUND", "分享链接无效")
        return loadAttempt(
            attemptId = null,
            extraCondition = "a.share_token_hash = ? AND a.share_revoked_at IS NULL",
            argument = SecureValues.sha256(token),
        )
    }

    /** 查询套卷模板和题目顺序。 */
    private fun getPaper(paperId: UUID, ownerId: UUID? = null): TimedPaperResponse {
        val ownerFilter = if (ownerId == null) "" else " AND owner_id = ?"
        val arguments = if (ownerId == null) arrayOf<Any>(paperId) else arrayOf(paperId, ownerId)
        val paper = jdbc.query(
            "SELECT id, title, duration_minutes FROM timed_paper WHERE id = ?$ownerFilter",
            { result, _ -> Triple(result.getObject("id", UUID::class.java), result.getString("title"), result.getInt("duration_minutes")) },
            *arguments,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "TIMED_PAPER_NOT_FOUND", "计时套卷不存在")
        val problems = jdbc.query(
            """
            SELECT tpp.ordinal, p.id AS problem_id, pv.id AS version_id, pv.title
            FROM timed_paper_problem tpp
            JOIN problem_version pv ON pv.id = tpp.problem_version_id
            JOIN problem p ON p.id = pv.problem_id
            WHERE tpp.timed_paper_id = ? ORDER BY tpp.ordinal
            """.trimIndent(),
            { result, _ ->
                TimedPaperProblemResponse(
                    result.getInt("ordinal"),
                    result.getObject("problem_id", UUID::class.java),
                    result.getObject("version_id", UUID::class.java),
                    result.getString("title"),
                )
            },
            paperId,
        )
        return TimedPaperResponse(paper.first, paper.second, paper.third, problems)
    }

    /** 读取私有或分享作答及逐题最高分。 */
    private fun loadAttempt(attemptId: UUID?, extraCondition: String, argument: Any): TimedAttemptResponse {
        val idCondition = if (attemptId == null) "" else "a.id = ? AND "
        val arguments = if (attemptId == null) arrayOf(argument) else arrayOf(attemptId, argument)
        val row = jdbc.query(
            """
            SELECT a.id, a.timed_paper_id, a.started_at, a.finished_at, a.paused_at,
                   a.accumulated_paused_ms, t.duration_minutes
            FROM timed_paper_attempt a JOIN timed_paper t ON t.id = a.timed_paper_id
            WHERE $idCondition$extraCondition
            """.trimIndent(),
            { result, _ ->
                AttemptRow(
                    result.getObject("id", UUID::class.java),
                    result.getObject("timed_paper_id", UUID::class.java),
                    result.getTimestamp("started_at").toInstant(),
                    result.getTimestamp("finished_at")?.toInstant(),
                    result.getTimestamp("paused_at")?.toInstant(),
                    result.getLong("accumulated_paused_ms"),
                    result.getInt("duration_minutes"),
                )
            },
            *arguments,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "套卷作答或分享不存在")
        val scores = jdbc.query(
            """
            SELECT pv.problem_id, max(s.score) AS score
            FROM submission s JOIN problem_version pv ON pv.id = s.problem_version_id
            WHERE s.timed_paper_attempt_id = ? AND s.execution_mode = 'SUBMIT'
              AND s.finished_at IS NOT NULL
            GROUP BY pv.problem_id
            """.trimIndent(),
            { result, _ -> result.getObject("problem_id", UUID::class.java) to result.getInt("score") },
            row.id,
        ).toMap()
        val paper = getPaper(row.paperId)
        val currentTime = Instant.now()
        val status = timedAttemptStatusAt(row.clock(), currentTime)
        return TimedAttemptResponse(
            id = row.id,
            paper = paper,
            startedAt = row.startedAt,
            expiresAt = timedAttemptExpiresAt(row.clock()),
            finished = status == TimedAttemptStatus.FINISHED,
            status = status,
            remainingSeconds = timedAttemptRemainingSecondsAt(row.clock(), currentTime),
            scores = scores,
            totalScore = timedAttemptTotalScore(scores),
            maximumScore = timedAttemptMaximumScore(paper.problems.size),
        )
    }

    /** 将已经超过截止时间的作答持久化为结束状态。 */
    private fun finishExpired(attemptId: UUID) {
        jdbc.update(
            """
            UPDATE timed_paper_attempt a
            SET finished_at = a.started_at + make_interval(mins => t.duration_minutes)
                + (a.accumulated_paused_ms * interval '1 millisecond')
            FROM timed_paper t
            WHERE a.id = ? AND t.id = a.timed_paper_id AND a.finished_at IS NULL
              AND a.paused_at IS NULL
              AND now() >= a.started_at + make_interval(mins => t.duration_minutes)
                  + (a.accumulated_paused_ms * interval '1 millisecond')
            """.trimIndent(),
            attemptId,
        )
    }

    /** 锁定当前用户的一行作答，串行化暂停、继续、结束与提交操作。 */
    private fun lockAttempt(attemptId: UUID, userId: UUID): AttemptRow = jdbc.query(
        """
        SELECT a.id, a.timed_paper_id, a.started_at, a.finished_at, a.paused_at,
               a.accumulated_paused_ms, t.duration_minutes
        FROM timed_paper_attempt a
        JOIN timed_paper t ON t.id = a.timed_paper_id
        WHERE a.id = ? AND a.user_id = ?
        FOR UPDATE OF a
        """.trimIndent(),
        { result, _ ->
            AttemptRow(
                result.getObject("id", UUID::class.java),
                result.getObject("timed_paper_id", UUID::class.java),
                result.getTimestamp("started_at").toInstant(),
                result.getTimestamp("finished_at")?.toInstant(),
                result.getTimestamp("paused_at")?.toInstant(),
                result.getLong("accumulated_paused_ms"),
                result.getInt("duration_minutes"),
            )
        },
        attemptId,
        userId,
    ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "套卷作答不存在")

    /** 在行锁内持久化自然到期；暂停状态不参与自然到期。 */
    private fun finishIfExpired(row: AttemptRow, currentTime: Instant): Boolean {
        if (row.finishedAt != null || row.pausedAt != null || currentTime.isBefore(timedAttemptExpiresAt(row.clock()))) {
            return false
        }
        jdbc.update(
            "UPDATE timed_paper_attempt SET finished_at = ? WHERE id = ? AND finished_at IS NULL",
            Timestamp.from(timedAttemptExpiresAt(row.clock())),
            row.id,
        )
        return true
    }

    /** 确认套卷属于当前用户。 */
    private fun requireOwner(paperId: UUID, userId: UUID) {
        val exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM timed_paper WHERE id = ? AND owner_id = ?)",
            Boolean::class.java,
            paperId,
            userId,
        ) ?: false
        if (!exists) throw ApiException(HttpStatus.NOT_FOUND, "TIMED_PAPER_NOT_FOUND", "计时套卷不存在")
    }

    /** 锁定所选题目的当前已发布版本。 */
    private fun lockPublishedVersions(problemIds: List<UUID>): Map<UUID, UUID> {
        val placeholders = problemIds.joinToString(",") { "?" }
        val versions = jdbc.query(
            "SELECT id, current_published_version_id FROM problem WHERE id IN ($placeholders) AND current_published_version_id IS NOT NULL FOR UPDATE",
            { result, _ -> result.getObject("id", UUID::class.java) to result.getObject("current_published_version_id", UUID::class.java) },
            *problemIds.toTypedArray(),
        ).toMap()
        if (versions.keys != problemIds.toSet()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "UNPUBLISHED_PROBLEM", "套卷只能包含已发布题目")
        }
        return versions
    }

    /** 套卷作答查询投影。 */
    private data class AttemptRow(
        /** 作答标识。 */
        val id: UUID,
        /** 套卷标识。 */
        val paperId: UUID,
        /** 开始时间。 */
        val startedAt: Instant,
        /** 结束时间。 */
        val finishedAt: Instant?,
        /** 当前暂停开始时间。 */
        val pausedAt: Instant?,
        /** 已完成暂停的累计毫秒数。 */
        val accumulatedPausedMs: Long,
        /** 时长分钟数。 */
        val durationMinutes: Int,
    ) {
        /** 转换为不包含数据库标识的计时快照。 */
        fun clock(): TimedAttemptClock = TimedAttemptClock(
            startedAt = startedAt,
            finishedAt = finishedAt,
            pausedAt = pausedAt,
            accumulatedPausedMs = accumulatedPausedMs,
            durationMinutes = durationMinutes,
        )
    }
}

/** 个人计时套卷接口。 */
@RestController
@RequestMapping("/api/v1/timed-papers")
class TimedPaperController(
    /** 套卷服务。 */
    private val service: TimedPaperService,
) {
    /** 查询当前用户创建的套卷模板。 */
    @GetMapping
    fun list(@AuthenticationPrincipal principal: AppPrincipal): List<TimedPaperResponse> =
        service.list(principal.userId)

    /** 创建套卷。 */
    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateTimedPaperRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): TimedPaperResponse = service.create(principal.userId, body)

    /** 查询当前用户已经开始过的套卷作答。 */
    @GetMapping("/attempts")
    fun attempts(@AuthenticationPrincipal principal: AppPrincipal): List<TimedAttemptResponse> =
        service.listAttempts(principal.userId)

    /** 首次进入并开始计时。 */
    @PostMapping("/{paperId}/attempts")
    fun start(@PathVariable paperId: UUID, @AuthenticationPrincipal principal: AppPrincipal): TimedAttemptResponse =
        service.start(principal.userId, paperId)

    /** 查看自己的作答。 */
    @GetMapping("/attempts/{attemptId}")
    fun attempt(@PathVariable attemptId: UUID, @AuthenticationPrincipal principal: AppPrincipal): TimedAttemptResponse =
        service.getAttempt(attemptId, principal.userId)

    /** 暂停个人计时。 */
    @PostMapping("/attempts/{attemptId}/pause")
    fun pause(@PathVariable attemptId: UUID, @AuthenticationPrincipal principal: AppPrincipal): TimedAttemptResponse =
        service.pause(attemptId, principal.userId)

    /** 继续个人计时。 */
    @PostMapping("/attempts/{attemptId}/resume")
    fun resume(@PathVariable attemptId: UUID, @AuthenticationPrincipal principal: AppPrincipal): TimedAttemptResponse =
        service.resume(attemptId, principal.userId)

    /** 提前结束个人计时。 */
    @PostMapping("/attempts/{attemptId}/finish")
    fun finish(@PathVariable attemptId: UUID, @AuthenticationPrincipal principal: AppPrincipal): TimedAttemptResponse =
        service.finish(attemptId, principal.userId)

    /** 生成可撤销只读分享链接。 */
    @PostMapping("/attempts/{attemptId}/share")
    fun share(@PathVariable attemptId: UUID, @AuthenticationPrincipal principal: AppPrincipal): Map<String, String> =
        mapOf("token" to service.share(attemptId, principal.userId))

    /** 撤销只读分享链接。 */
    @DeleteMapping("/attempts/{attemptId}/share")
    fun revoke(@PathVariable attemptId: UUID, @AuthenticationPrincipal principal: AppPrincipal) =
        service.revokeShare(attemptId, principal.userId)
}

/** 无需登录的只读套卷分享接口。 */
@RestController
@RequestMapping("/api/v1/shares/timed-papers")
class TimedPaperShareController(
    /** 套卷服务。 */
    private val service: TimedPaperService,
) {
    /** 使用不可逆哈希匹配分享令牌。 */
    @GetMapping("/{token}")
    fun shared(@PathVariable token: String): TimedAttemptResponse = service.shared(token)
}

/** 训练赛可见性。 */
enum class ContestVisibility {
    /** 无需口令公开加入。 */
    PUBLIC,
    /** 需要创建者设置的口令。 */
    PASSWORD,
}

/** 创建公开或口令训练赛请求。 */
data class CreateContestRequest(
    /** 比赛标题。 */
    @field:NotBlank
    @field:Size(max = 120)
    val title: String,
    /** 公开或口令模式。 */
    val visibility: ContestVisibility,
    /** 口令模式的原始口令。 */
    @field:Size(min = 8, max = 100)
    val password: String? = null,
    /** 统一开赛时间。 */
    val startsAt: Instant,
    /** 比赛分钟数。 */
    @field:Min(15)
    @field:Max(300)
    val durationMinutes: Int,
    /** 依次锁定的当前发布题目。 */
    @field:Size(min = 1, max = 20)
    val problemIds: List<UUID>,
)

/** 加入口令训练赛请求。 */
data class JoinContestRequest(
    /** 公开赛为空，口令赛必填。 */
    val password: String? = null,
)

/** 比赛进行阶段。 */
enum class ContestPhase {
    /** 尚未开始。 */
    UPCOMING,
    /** 正在进行。 */
    RUNNING,
    /** 已经结束。 */
    FINISHED,
}

/** 使用同一个时间快照判断比赛阶段，保证列表排序和响应字段保持一致。 */
internal fun contestPhaseAt(currentTime: Instant, startsAt: Instant, endsAt: Instant): ContestPhase = when {
    currentTime.isBefore(startsAt) -> ContestPhase.UPCOMING
    currentTime.isBefore(endsAt) -> ContestPhase.RUNNING
    else -> ContestPhase.FINISHED
}

/** 生成按普通文本匹配的 PostgreSQL ILIKE 包含模式。 */
internal fun contestKeywordPattern(value: String): String = "%${value
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")}%"

/** 比赛题目摘要。 */
data class ContestProblemResponse(
    /** 题目顺序。 */
    val ordinal: Int,
    /** 稳定题目标识。 */
    val problemId: UUID,
    /** 锁定版本标识。 */
    val versionId: UUID,
    /** 题目标题。 */
    val title: String,
)

/** 公开比赛开始后可见的一行排名。 */
data class ContestRankResponse(
    /** 排名，从一开始。 */
    val rank: Int,
    /** 用户名。 */
    val username: String,
    /** 总分。 */
    val totalScore: Int,
    /** 达到最终总分的比赛用时秒数。 */
    val elapsedSeconds: Long,
    /** 比赛期间每道题的最高分，键为稳定题目标识。 */
    val problemScores: Map<UUID, Int>,
)

/** 训练赛列表中的轻量元数据，不包含题目、个人得分和排名。 */
data class ContestSummaryResponse(
    /** 比赛标识。 */
    val id: UUID,
    /** 比赛标题。 */
    val title: String,
    /** 可见性。 */
    val visibility: ContestVisibility,
    /** 创建者用户名。 */
    val ownerUsername: String,
    /** 创建时间。 */
    val createdAt: Instant,
    /** 开始时间。 */
    val startsAt: Instant,
    /** 结束时间。 */
    val endsAt: Instant,
    /** 当前阶段。 */
    val phase: ContestPhase,
    /** 人数上限。 */
    val maxParticipants: Int,
    /** 当前参与人数。 */
    val participantCount: Int,
    /** 当前用户是否已加入。 */
    val joined: Boolean,
)

/** 用户可见的训练赛详情。 */
data class ContestResponse(
    /** 比赛标识。 */
    val id: UUID,
    /** 比赛标题。 */
    val title: String,
    /** 可见性。 */
    val visibility: ContestVisibility,
    /** 创建者用户名。 */
    val ownerUsername: String,
    /** 创建时间。 */
    val createdAt: Instant,
    /** 开始时间。 */
    val startsAt: Instant,
    /** 结束时间。 */
    val endsAt: Instant,
    /** 当前阶段。 */
    val phase: ContestPhase,
    /** 人数上限。 */
    val maxParticipants: Int,
    /** 当前参与人数。 */
    val participantCount: Int,
    /** 当前用户是否已加入。 */
    val joined: Boolean,
    /** 锁定的题目。 */
    val problems: List<ContestProblemResponse>,
    /** 已加入用户在比赛开始后可见的本人每题最高分。 */
    val myScores: Map<UUID, Int>?,
    /** 公开赛开始后可见的实时排名；比赛开始前为空。 */
    val ranking: List<ContestRankResponse>?,
)

/** 受限用户训练赛服务。 */
@Service
class ContestService(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
    /** 口令使用 Argon2id 存储。 */
    private val passwordEncoder: PasswordEncoder,
    /** 可配置的比赛限制。 */
    private val properties: AppProperties,
) {
    /** 创建训练赛并自动让创建者加入。 */
    @Transactional
    fun create(userId: UUID, request: CreateContestRequest): ContestResponse {
        if (request.startsAt.isBefore(Instant.now().minusSeconds(5))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_START_TIME", "开赛时间不能早于当前时间")
        }
        if (request.problemIds.distinct().size != request.problemIds.size) {
            throw ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_PROBLEM", "比赛题目不能重复")
        }
        validatePassword(request)
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtext(?))",
            { _, _ -> Unit },
            userId.toString(),
        )
        enforceCreationLimits(userId)
        val versions = lockPublishedVersions(request.problemIds)
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO contest(id, owner_id, title, visibility, password_hash, starts_at, duration_minutes, max_participants)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            userId,
            request.title.trim(),
            request.visibility.name,
            request.password?.let(passwordEncoder::encode),
            Timestamp.from(request.startsAt),
            request.durationMinutes,
            properties.contest.maxParticipants,
        )
        request.problemIds.forEachIndexed { index, problemId ->
            jdbc.update(
                "INSERT INTO contest_problem(contest_id, problem_version_id, ordinal) VALUES (?, ?, ?)",
                id,
                versions.getValue(problemId),
                index + 1,
            )
        }
        jdbc.update("INSERT INTO contest_participant(contest_id, user_id) VALUES (?, ?)", id, userId)
        return detail(id, userId)
    }

    /** 加入公开或口令比赛，并在行锁内检查人数。 */
    @Transactional
    fun join(contestId: UUID, userId: UUID, request: JoinContestRequest): ContestResponse {
        val contest = loadContestRow(contestId, lock = true)
        if (!Instant.now().isBefore(contest.endsAt)) {
            throw ApiException(HttpStatus.CONFLICT, "CONTEST_FINISHED", "比赛已经结束")
        }
        if (contest.visibility == ContestVisibility.PASSWORD &&
            (request.password == null || !passwordEncoder.matches(request.password, contest.passwordHash))) {
            throw ApiException(HttpStatus.FORBIDDEN, "CONTEST_PASSWORD_INVALID", "比赛口令不正确")
        }
        val alreadyJoined = isJoined(contestId, userId)
        if (!alreadyJoined && participantCount(contestId) >= contest.maxParticipants) {
            throw ApiException(HttpStatus.CONFLICT, "CONTEST_FULL", "比赛人数已满")
        }
        jdbc.update(
            "INSERT INTO contest_participant(contest_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            contestId,
            userId,
        )
        return detail(contestId, userId)
    }

    /**
     * 查询公开赛和口令赛的轻量摘要。
     *
     * 列表只聚合比赛元数据与当前用户的加入状态，不读取题目、得分或排名；口令赛的
     * 完整内容仍由详情接口校验参赛关系。关键词中的 SQL 通配符按普通字符处理。
     */
    fun contests(
        viewerId: UUID,
        keyword: String?,
        visibility: ContestVisibility?,
    ): List<ContestSummaryResponse> {
        val currentTime = Instant.now()
        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<Any>(Timestamp.from(currentTime), viewerId)
        keyword?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            conditions += "(c.title ILIKE ? ESCAPE '\\' OR u.username ILIKE ? ESCAPE '\\')"
            val pattern = contestKeywordPattern(value)
            arguments += pattern
            arguments += pattern
        }
        visibility?.let {
            conditions += "c.visibility = ?"
            arguments += it.name
        }
        val whereClause = conditions.takeIf(List<String>::isNotEmpty)
            ?.joinToString(prefix = "WHERE ", separator = " AND ")
            .orEmpty()
        return jdbc.query(
            """
            WITH current_clock AS (
                SELECT ?::timestamptz AS value
            ), contest_summary AS (
                SELECT c.id, c.title, c.visibility, c.starts_at,
                       c.starts_at + make_interval(mins => c.duration_minutes) AS ends_at,
                       c.max_participants, c.created_at, u.username,
                       count(cp.user_id)::integer AS participant_count,
                       coalesce(bool_or(cp.user_id = ?), false) AS joined
                FROM contest c
                JOIN app_user u ON u.id = c.owner_id
                LEFT JOIN contest_participant cp ON cp.contest_id = c.id
                $whereClause
                GROUP BY c.id, u.username
            )
            SELECT contest_summary.*
            FROM contest_summary CROSS JOIN current_clock
            ORDER BY
                CASE
                    WHEN current_clock.value >= starts_at AND current_clock.value < ends_at THEN 0
                    WHEN current_clock.value < starts_at THEN 1
                    ELSE 2
                END,
                CASE WHEN current_clock.value >= starts_at AND current_clock.value < ends_at THEN ends_at END ASC,
                CASE WHEN current_clock.value < starts_at THEN starts_at END ASC,
                CASE WHEN current_clock.value >= ends_at THEN ends_at END DESC,
                created_at DESC,
                id ASC
            LIMIT 100
            """.trimIndent(),
            { result, _ ->
                val startsAt = result.getTimestamp("starts_at").toInstant()
                val endsAt = result.getTimestamp("ends_at").toInstant()
                ContestSummaryResponse(
                    id = result.getObject("id", UUID::class.java),
                    title = result.getString("title"),
                    visibility = ContestVisibility.valueOf(result.getString("visibility")),
                    ownerUsername = result.getString("username"),
                    createdAt = result.getTimestamp("created_at").toInstant(),
                    startsAt = startsAt,
                    endsAt = endsAt,
                    phase = contestPhaseAt(currentTime, startsAt, endsAt),
                    maxParticipants = result.getInt("max_participants"),
                    participantCount = result.getInt("participant_count"),
                    joined = result.getBoolean("joined"),
                )
            },
            *arguments.toTypedArray(),
        )
    }

    /** 按比赛阶段控制本人得分和公开排名。 */
    fun detail(contestId: UUID, viewerId: UUID?): ContestResponse {
        val contest = loadContestRow(contestId, lock = false)
        if (contest.visibility == ContestVisibility.PASSWORD && (viewerId == null || !isJoined(contestId, viewerId))) {
            throw ApiException(HttpStatus.NOT_FOUND, "CONTEST_NOT_FOUND", "比赛不存在")
        }
        val now = Instant.now()
        val phase = contestPhaseAt(now, contest.startsAt, contest.endsAt)
        val joined = viewerId != null && isJoined(contestId, viewerId)
        val problems = loadProblems(contestId)
        return ContestResponse(
            id = contest.id,
            title = contest.title,
            visibility = contest.visibility,
            ownerUsername = contest.ownerUsername,
            createdAt = contest.createdAt,
            startsAt = contest.startsAt,
            endsAt = contest.endsAt,
            phase = phase,
            maxParticipants = contest.maxParticipants,
            participantCount = participantCount(contestId),
            joined = joined,
            problems = problems,
            myScores = viewerId?.takeIf { joined && phase != ContestPhase.UPCOMING }
                ?.let { loadUserScores(contestId, it) },
            // 公开赛从开始后即可查看实时排名；口令赛仍只展示给已加入用户，避免泄露受限比赛信息。
            ranking = if (contest.visibility == ContestVisibility.PUBLIC && phase != ContestPhase.UPCOMING) ranking(contest, problems.map { it.problemId }) else null,
        )
    }

    /** 返回公开比赛的轻量排名数据，供进行中的排名页轮询。 */
    fun rankingOnly(contestId: UUID): List<ContestRankResponse> {
        val contest = loadContestRow(contestId, lock = false)
        if (contest.visibility != ContestVisibility.PUBLIC) {
            throw ApiException(HttpStatus.NOT_FOUND, "CONTEST_NOT_FOUND", "比赛不存在")
        }
        val now = Instant.now()
        if (now.isBefore(contest.startsAt)) return emptyList()
        return ranking(contest, loadProblemIds(contestId))
    }

    /** 按共享 OI 算法计算公开用户名、总分和最后一道得分题的用时。 */
    private fun ranking(contest: ContestRow, problemIds: List<UUID>): List<ContestRankResponse> {
        val users = jdbc.query(
            """
            SELECT u.id, u.username FROM contest_participant cp
            JOIN app_user u ON u.id = cp.user_id WHERE cp.contest_id = ?
            """.trimIndent(),
            { result, _ -> result.getObject("id", UUID::class.java) to result.getString("username") },
            contest.id,
        ).toMap()
        val events = jdbc.query(
            """
            SELECT s.user_id, pv.problem_id, s.score,
                   greatest(0, extract(epoch FROM (s.finished_at - c.starts_at)))::bigint AS elapsed
            FROM submission s
            JOIN problem_version pv ON pv.id = s.problem_version_id
            JOIN contest c ON c.id = s.contest_id
            JOIN contest_problem cp ON cp.contest_id = c.id AND cp.problem_version_id = s.problem_version_id
            WHERE s.contest_id = ? AND s.finished_at IS NOT NULL
              AND s.created_at >= c.starts_at
              AND s.created_at <= c.starts_at + make_interval(mins => c.duration_minutes)
            """.trimIndent(),
            { result, _ ->
                ContestScoreEvent(
                    result.getObject("user_id", UUID::class.java).toString(),
                    result.getObject("problem_id", UUID::class.java).toString(),
                    result.getInt("score"),
                    result.getLong("elapsed"),
                )
            },
            contest.id,
        ).toMutableList()
        users.keys.forEach { userId ->
            if (events.none { it.userId == userId.toString() } && problemIds.isNotEmpty()) {
                events += ContestScoreEvent(userId.toString(), problemIds.first().toString(), 0, 0)
            }
        }
        return ContestRanking.calculate(events).mapIndexed { index, row ->
            ContestRankResponse(
                rank = index + 1,
                username = users.getValue(UUID.fromString(row.userId)),
                totalScore = row.totalScore,
                elapsedSeconds = row.reachedFinalScoreAtSeconds,
                problemScores = row.problemScores.mapKeys { UUID.fromString(it.key) },
            )
        }
    }

    /** 仅读取比赛绑定的题目标识，供轻量排名轮询使用。 */
    private fun loadProblemIds(contestId: UUID): List<UUID> = jdbc.queryForList(
        "SELECT pv.problem_id FROM contest_problem cp JOIN problem_version pv ON pv.id = cp.problem_version_id WHERE cp.contest_id = ? ORDER BY cp.ordinal",
        UUID::class.java,
        contestId,
    ).filterNotNull()

    /** 读取当前用户在比赛期间的逐题最高分。 */
    private fun loadUserScores(contestId: UUID, userId: UUID): Map<UUID, Int> = jdbc.query(
        """
        SELECT pv.problem_id, max(s.score) AS score
        FROM submission s JOIN problem_version pv ON pv.id = s.problem_version_id
        WHERE s.contest_id = ? AND s.user_id = ? AND s.execution_mode = 'SUBMIT'
          AND s.finished_at IS NOT NULL
        GROUP BY pv.problem_id
        """.trimIndent(),
        { result, _ -> result.getObject("problem_id", UUID::class.java) to result.getInt("score") },
        contestId,
        userId,
    ).toMap()

    /** 加载比赛锁定的题目版本。 */
    private fun loadProblems(contestId: UUID): List<ContestProblemResponse> = jdbc.query(
        """
        SELECT cp.ordinal, p.id AS problem_id, pv.id AS version_id, pv.title
        FROM contest_problem cp
        JOIN problem_version pv ON pv.id = cp.problem_version_id
        JOIN problem p ON p.id = pv.problem_id
        WHERE cp.contest_id = ? ORDER BY cp.ordinal
        """.trimIndent(),
        { result, _ ->
            ContestProblemResponse(
                result.getInt("ordinal"),
                result.getObject("problem_id", UUID::class.java),
                result.getObject("version_id", UUID::class.java),
                result.getString("title"),
            )
        },
        contestId,
    )

    /** 检查口令与可见性组合。 */
    private fun validatePassword(request: CreateContestRequest) {
        val valid = when (request.visibility) {
            ContestVisibility.PUBLIC -> request.password == null
            ContestVisibility.PASSWORD -> !request.password.isNullOrBlank()
        }
        if (!valid) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTEST_PASSWORD", "比赛可见性与口令不一致")
    }

    /** 强制每用户活动比赛数量和创建间隔。 */
    private fun enforceCreationLimits(userId: UUID) {
        val active = jdbc.queryForObject(
            """
            SELECT count(*) FROM contest
            WHERE owner_id = ? AND starts_at + make_interval(mins => duration_minutes) > now()
            """.trimIndent(),
            Int::class.java,
            userId,
        ) ?: 0
        if (active >= properties.contest.maxActiveOwned) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ACTIVE_CONTESTS", "待开始或进行中的比赛数量已达上限")
        }
        val latest = jdbc.queryForObject("SELECT max(created_at) FROM contest WHERE owner_id = ?", Instant::class.java, userId)
        if (latest != null && latest.plus(Duration.ofMinutes(properties.contest.minimumCreateIntervalMinutes)).isAfter(Instant.now())) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "CONTEST_CREATE_TOO_FREQUENT", "创建比赛过于频繁")
        }
    }

    /** 锁定题目并返回当前发布版本。 */
    private fun lockPublishedVersions(problemIds: List<UUID>): Map<UUID, UUID> {
        val placeholders = problemIds.joinToString(",") { "?" }
        val versions = jdbc.query(
            "SELECT id, current_published_version_id FROM problem WHERE id IN ($placeholders) AND current_published_version_id IS NOT NULL FOR UPDATE",
            { result, _ -> result.getObject("id", UUID::class.java) to result.getObject("current_published_version_id", UUID::class.java) },
            *problemIds.toTypedArray(),
        ).toMap()
        if (versions.keys != problemIds.toSet()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "UNPUBLISHED_PROBLEM", "比赛只能包含已发布题目")
        }
        return versions
    }

    /** 读取比赛并按需加行锁。 */
    private fun loadContestRow(contestId: UUID, lock: Boolean): ContestRow {
        val lockClause = if (lock) " FOR UPDATE OF c" else ""
        return jdbc.query(
            """
            SELECT c.id, c.title, c.visibility, c.password_hash, c.starts_at, c.duration_minutes,
                   c.max_participants, c.created_at, u.username
            FROM contest c JOIN app_user u ON u.id = c.owner_id
            WHERE c.id = ?$lockClause
            """.trimIndent(),
            { result, _ ->
                val startsAt = result.getTimestamp("starts_at").toInstant()
                val durationMinutes = result.getLong("duration_minutes")
                ContestRow(
                    result.getObject("id", UUID::class.java),
                    result.getString("title"),
                    ContestVisibility.valueOf(result.getString("visibility")),
                    result.getString("password_hash"),
                    startsAt,
                    startsAt.plus(Duration.ofMinutes(durationMinutes)),
                    result.getInt("max_participants"),
                    result.getTimestamp("created_at").toInstant(),
                    result.getString("username"),
                )
            },
            contestId,
        ).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "CONTEST_NOT_FOUND", "比赛不存在")
    }

    /** 返回用户是否已经加入比赛。 */
    private fun isJoined(contestId: UUID, userId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM contest_participant WHERE contest_id = ? AND user_id = ?)",
        Boolean::class.java,
        contestId,
        userId,
    ) ?: false

    /** 返回当前参与人数。 */
    private fun participantCount(contestId: UUID): Int = jdbc.queryForObject(
        "SELECT count(*) FROM contest_participant WHERE contest_id = ?",
        Int::class.java,
        contestId,
    ) ?: 0

    /** 比赛查询投影。 */
    private data class ContestRow(
        /** 比赛标识。 */
        val id: UUID,
        /** 标题。 */
        val title: String,
        /** 可见性。 */
        val visibility: ContestVisibility,
        /** Argon2id 口令哈希。 */
        val passwordHash: String?,
        /** 开始时间。 */
        val startsAt: Instant,
        /** 结束时间。 */
        val endsAt: Instant,
        /** 人数上限。 */
        val maxParticipants: Int,
        /** 创建时间。 */
        val createdAt: Instant,
        /** 创建者用户名。 */
        val ownerUsername: String,
    )
}

/** 训练赛用户接口。 */
@RestController
@RequestMapping("/api/v1/contests")
class ContestController(
    /** 比赛服务。 */
    private val service: ContestService,
) {
    /** 按关键词和可见性查询训练赛摘要；未传筛选条件时返回全部训练赛。 */
    @GetMapping
    fun list(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) visibility: ContestVisibility?,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): List<ContestSummaryResponse> = service.contests(principal.userId, keyword, visibility)

    /** 创建受限训练赛。 */
    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateContestRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): ContestResponse = service.create(principal.userId, body)

    /** 查询比赛详情。 */
    @GetMapping("/{contestId}")
    fun detail(@PathVariable contestId: UUID, @AuthenticationPrincipal principal: AppPrincipal?): ContestResponse =
        service.detail(contestId, principal?.userId)

    /** 轮询公开比赛排名，不重复传输题目和比赛元数据。 */
    @GetMapping("/{contestId}/ranking")
    fun ranking(@PathVariable contestId: UUID): List<ContestRankResponse> =
        service.rankingOnly(contestId)

    /** 加入公开或口令比赛。 */
    @PostMapping("/{contestId}/participants")
    fun join(
        @PathVariable contestId: UUID,
        @Valid @RequestBody body: JoinContestRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): ContestResponse = service.join(contestId, principal.userId, body)
}
