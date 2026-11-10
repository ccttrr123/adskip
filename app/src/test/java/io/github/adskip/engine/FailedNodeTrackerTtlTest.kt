package io.github.adskip.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分级 TTL —— 由 24 天真实数据驱动：志愿汇 11 次广告 / 50 次点击 / 41 次失败，
 * 因为它每天只启动约 1.5 次，30 分钟的 TTL 每次都在下次启动前过期，黑名单从未生效。
 */
class FailedNodeTrackerTtlTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `失败到阈值就拉黑`() {
        val t = FailedNodeTracker()
        repeat(3) { t.recordFailure("a", 0L) }
        assertTrue(t.blacklist(0L).contains("a"))
    }

    @Test
    fun `过了 TTL 就不再拉黑_但计数要保留`() {
        val t = FailedNodeTracker()
        repeat(3) { t.recordFailure("a", 0L) }
        assertFalse("31 分钟后应放行重试", t.blacklist(31 * minute).contains("a"))
        // 关键回归：计数不能被清空（旧实现会清空，导致黑名单永远从头开始）
        t.recordFailure("a", 32 * minute)
        assertTrue("计时应累加到 4", t.snapshot()["a"]!!.count == 4)
    }

    @Test
    fun `失败 6 次后 TTL 变 6 小时`() {
        val t = FailedNodeTracker()
        repeat(6) { t.recordFailure("a", 0L) }
        assertTrue("1 小时后仍应拉黑", t.blacklist(hour).contains("a"))
        assertFalse("7 小时后应放行", t.blacklist(7 * hour).contains("a"))
    }

    @Test
    fun `失败 10 次后 TTL 变 24 小时`() {
        val t = FailedNodeTracker()
        repeat(10) { t.recordFailure("a", 0L) }
        assertTrue("23 小时后仍应拉黑", t.blacklist(23 * hour).contains("a"))
        assertFalse("25 小时后应放行", t.blacklist(25 * hour).contains("a"))
    }

    @Test
    fun `成功一次就清空计数`() {
        val t = FailedNodeTracker()
        repeat(10) { t.recordFailure("a", 0L) }
        t.recordSuccess("a")
        assertFalse(t.blacklist(0L).contains("a"))
        assertTrue(t.snapshot()["a"] == null)
    }

    @Test
    fun `30 天没再失败就忘掉`() {
        val t = FailedNodeTracker()
        repeat(10) { t.recordFailure("a", 0L) }
        t.blacklist(31L * 24 * hour)
        assertTrue("记录应被清理", t.snapshot().isEmpty())
    }

    @Test
    fun `重启后_上一轮开机的失败记录立即作废`() {
        // 时间戳是 SystemClock.elapsedRealtime()，重启后从 0 重数。
        // 所以"记录里的时间 > 现在"只可能来自上一次开机 —— 必须直接丢弃，
        // 否则差值恒为负，分级 TTL 与 30 天遗忘全部失效（黑名单会多黑"上次开机时长 + TTL"）。
        val t = FailedNodeTracker()
        repeat(10) { t.recordFailure("a", 5_000_000L) }   // 上次开机累计 5000 秒时失败 10 次
        assertFalse("重启后不应沿用旧记录拉黑", t.blacklist(60_000L).contains("a"))
        assertTrue("旧记录应被一并清理", t.snapshot().isEmpty())
    }
}