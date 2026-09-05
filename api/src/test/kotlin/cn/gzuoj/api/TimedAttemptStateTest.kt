package cn.gzuoj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** 个人计时状态机的时间边界与幂等操作测试。 */
class TimedAttemptStateTest {
    /** 暂停后无论墙上时间如何推进，剩余时间都固定在暂停时刻。 */
    @Test
    fun `pause freezes remaining time`() {
        val startedAt = Instant.parse("2026-09-05T01:00:00Z")
        val clock = TimedAttemptClock(
            startedAt = startedAt,
            finishedAt = null,
            pausedAt = startedAt.plusSeconds(180),
            accumulatedPausedMs = 0,
            durationMinutes = 15,
        )

        assertEquals(TimedAttemptStatus.PAUSED, timedAttemptStatusAt(clock, startedAt.plusSeconds(300)))
        assertEquals(720, timedAttemptRemainingSecondsAt(clock, startedAt.plusSeconds(300)))
        assertEquals(720, timedAttemptRemainingSecondsAt(clock, startedAt.plusSeconds(3_000)))
    }

    /** 恢复后累计暂停时长会等量顺延截止时间。 */
    @Test
    fun `resume extends expiration by accumulated pause`() {
        val startedAt = Instant.parse("2026-09-05T01:00:00Z")
        val resumedClock = TimedAttemptClock(
            startedAt = startedAt,
            finishedAt = null,
            pausedAt = null,
            accumulatedPausedMs = 300_000,
            durationMinutes = 15,
        )

        assertEquals(startedAt.plusSeconds(1_200), timedAttemptExpiresAt(resumedClock))
        assertEquals(720, timedAttemptRemainingSecondsAt(resumedClock, startedAt.plusSeconds(480)))
    }

    /** 未暂停作答在精确截止边界自然结束。 */
    @Test
    fun `running attempt finishes at expiration boundary`() {
        val startedAt = Instant.parse("2026-09-05T01:00:00Z")
        val clock = TimedAttemptClock(startedAt, null, null, 0, 15)

        assertEquals(TimedAttemptStatus.RUNNING, timedAttemptStatusAt(clock, startedAt.plusSeconds(899)))
        assertEquals(TimedAttemptStatus.FINISHED, timedAttemptStatusAt(clock, startedAt.plusSeconds(900)))
        assertEquals(0, timedAttemptRemainingSecondsAt(clock, startedAt.plusSeconds(900)))
    }

    /** 提前结束立即覆盖尚未耗尽的剩余时间。 */
    @Test
    fun `finished timestamp makes attempt irreversible`() {
        val startedAt = Instant.parse("2026-09-05T01:00:00Z")
        val clock = TimedAttemptClock(startedAt, startedAt.plusSeconds(60), null, 0, 15)

        assertEquals(TimedAttemptStatus.FINISHED, timedAttemptStatusAt(clock, startedAt.plusSeconds(61)))
        assertEquals(0, timedAttemptRemainingSecondsAt(clock, startedAt.plusSeconds(61)))
    }

    /** 重复暂停、继续和结束保持幂等，结束后不能再次暂停或继续。 */
    @Test
    fun `repeated state actions are idempotent`() {
        assertEquals(TimedAttemptMutation.NONE, timedAttemptMutation(TimedAttemptStatus.PAUSED, TimedAttemptAction.PAUSE))
        assertEquals(TimedAttemptMutation.NONE, timedAttemptMutation(TimedAttemptStatus.RUNNING, TimedAttemptAction.RESUME))
        assertEquals(TimedAttemptMutation.NONE, timedAttemptMutation(TimedAttemptStatus.FINISHED, TimedAttemptAction.FINISH))
        assertEquals(TimedAttemptMutation.CONFLICT, timedAttemptMutation(TimedAttemptStatus.FINISHED, TimedAttemptAction.PAUSE))
        assertEquals(TimedAttemptMutation.CONFLICT, timedAttemptMutation(TimedAttemptStatus.FINISHED, TimedAttemptAction.RESUME))
    }

    /** 多题套卷总分上限按题目数线性增长。 */
    @Test
    fun `maximum score is one hundred per problem`() {
        assertEquals(200, timedAttemptMaximumScore(2))
        assertEquals(
            160,
            timedAttemptTotalScore(
                mapOf(
                    UUID.fromString("11111111-1111-4111-8111-111111111111") to 100,
                    UUID.fromString("22222222-2222-4222-8222-222222222222") to 60,
                ),
            ),
        )
    }
}
