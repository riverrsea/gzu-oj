package cn.gzuoj.api

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** 适合单控制端首版的固定窗口账号/IP 限流器。 */
@Component
class RequestRateLimiter(
    /** 可替换时钟，便于测试窗口边界。 */
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 每个限流键的当前窗口。 */
    private val windows = ConcurrentHashMap<String, Window>()

    /** 检查指定键在窗口内是否超过次数上限。 */
    fun check(key: String, limit: Int, windowSeconds: Long) {
        val now = clock.millis()
        windows.compute(key) { _, current ->
            val window = if (current == null || now >= current.startedAt + windowSeconds * 1000) {
                Window(now, 1)
            } else {
                current.copy(count = current.count + 1)
            }
            if (window.count > limit) {
                throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后重试")
            }
            window
        }
        if (windows.size > 10_000) {
            windows.entries.removeIf { now >= it.value.startedAt + windowSeconds * 1000 }
        }
    }

    /** 单个固定时间窗口的计数。 */
    private data class Window(
        /** 窗口起始毫秒时间。 */
        val startedAt: Long,
        /** 窗口内请求次数。 */
        val count: Int,
    )
}
