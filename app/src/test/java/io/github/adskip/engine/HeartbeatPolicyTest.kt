package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 心跳的判定逻辑：多久没上报算"服务已停止" */
class HeartbeatPolicyTest {

    @Test
    fun `从没上报过是 UNKNOWN`() {
        assertEquals(ServiceHealth.UNKNOWN, HeartbeatPolicy.classify(0L, 999_999L))
    }

    @Test
    fun `刚上报过是 ALIVE`() {
        assertEquals(ServiceHealth.ALIVE, HeartbeatPolicy.classify(1_000L, 31_000L))
    }

    @Test
    fun `超过阈值是 STALE`() {
        val last = 1_000L
        assertEquals(ServiceHealth.STALE, HeartbeatPolicy.classify(last, last + HeartbeatPolicy.STALE_MS))
    }

    @Test
    fun `差一毫秒还不算掉线`() {
        val last = 1_000L
        assertEquals(
            ServiceHealth.ALIVE,
            HeartbeatPolicy.classify(last, last + HeartbeatPolicy.STALE_MS - 1)
        )
    }

    @Test
    fun `阈值至少是两个上报周期_避免系统延迟导致误报`() {
        assertTrue(
            "阈值太紧会把正常的服务误报成已停止，反而让人不再信任这个提示",
            HeartbeatPolicy.STALE_MS >= HeartbeatPolicy.INTERVAL_MS * 2
        )
    }

    @Test
    fun `时长描述是人话`() {
        assertEquals("从未上报", HeartbeatPolicy.describeAge(-1L))
        assertEquals("12 秒前", HeartbeatPolicy.describeAge(12_000L))
        assertEquals("5 分钟前", HeartbeatPolicy.describeAge(5 * 60_000L))
        assertEquals("2 小时前", HeartbeatPolicy.describeAge(2 * 3_600_000L))
    }

    // ---------------------------------------------------------------- 界面判据

    private fun verdict(connected: Boolean, ageMs: Long, uptimeMs: Long) =
        HeartbeatPolicy.classifyService(
            connected = connected,
            ageMs = ageMs,
            uptimeMs = uptimeMs,
            graceMs = 4_000L
        )

    @Test
    fun `连着且心跳新鲜_才算正在工作`() {
        assertEquals(ServiceVerdict.RUNNING, verdict(connected = true, ageMs = 3_000L, uptimeMs = 60_000L))
    }

    @Test
    fun `刚打开界面的宽限期内不下结论`() {
        // 系统可能还在绑定服务；此时既不能报"正在工作"，也不能报"已停止"
        assertEquals(ServiceVerdict.CHECKING, verdict(connected = false, ageMs = -1L, uptimeMs = 1_000L))
    }

    @Test
    fun `宽限期过了还是没心跳_才算已停止`() {
        assertEquals(ServiceVerdict.STOPPED, verdict(connected = false, ageMs = -1L, uptimeMs = 10_000L))
        assertEquals(ServiceVerdict.STOPPED, verdict(connected = false, ageMs = 30_000L, uptimeMs = 10_000L))
    }

    @Test
    fun `标志为假_即使心跳新鲜也仍判已停止`() {
        // 这一条锁的是**判据本身**没有偷偷放宽：
        // 只要"我在"标志为假，就仍然判已停止 —— 不拿心跳新鲜度去把它"洗白"。
        //
        // 那真机那次误报是怎么修的？修在**服务侧**：不再让 `onUnbind` 把标志置假
        // （ColorOS 会脱离服务实际状态地触发它，两分钟触发 12 次）。见 AdSkipService.onUnbind。
        // 两个条件都保留"任一条不成立就报停止"，是为了不漏报真停 —— 宁可偶发误报，
        // 也不能在服务真的没了的时候还显示"正在工作"（那正是这个功能最初要修的谎）。
        assertEquals(ServiceVerdict.STOPPED, verdict(connected = false, ageMs = 3_000L, uptimeMs = 60_000L))
    }

    @Test
    fun `标志为真但心跳已经过时_不许说正在工作`() {
        // 服务实例已销毁、只剩一个陈旧标志（或定时器早停了）时不冒功
        assertEquals(
            ServiceVerdict.STOPPED,
            verdict(connected = true, ageMs = HeartbeatPolicy.STALE_MS, uptimeMs = 60_000L)
        )
        assertEquals(
            ServiceVerdict.STOPPED,
            verdict(connected = true, ageMs = -1L, uptimeMs = 60_000L)
        )
    }

    @Test
    fun `心跳差一毫秒仍算新鲜`() {
        assertEquals(
            ServiceVerdict.RUNNING,
            verdict(connected = true, ageMs = HeartbeatPolicy.STALE_MS - 1, uptimeMs = 60_000L)
        )
    }
}