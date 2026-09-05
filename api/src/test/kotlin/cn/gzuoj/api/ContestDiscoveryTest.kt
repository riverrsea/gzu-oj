package cn.gzuoj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** 训练赛发现接口中不依赖数据库的阶段与关键词边界测试。 */
class ContestDiscoveryTest {
    /** 开始和结束时刻分别属于进行中和已结束，避免排序分组出现一秒偏差。 */
    @Test
    fun `uses exact contest phase boundaries`() {
        val startsAt = Instant.parse("2026-09-05T01:00:00Z")
        val endsAt = Instant.parse("2026-09-05T03:00:00Z")

        assertEquals(ContestPhase.UPCOMING, contestPhaseAt(startsAt.minusNanos(1), startsAt, endsAt))
        assertEquals(ContestPhase.RUNNING, contestPhaseAt(startsAt, startsAt, endsAt))
        assertEquals(ContestPhase.FINISHED, contestPhaseAt(endsAt, startsAt, endsAt))
    }

    /** 百分号、下划线和转义字符必须按关键词原文匹配，而不是扩大 SQL 查询范围。 */
    @Test
    fun `escapes like wildcard characters in keyword`() {
        assertEquals("%100\\%\\_完成\\\\%", contestKeywordPattern("100%_完成\\"))
    }
}
