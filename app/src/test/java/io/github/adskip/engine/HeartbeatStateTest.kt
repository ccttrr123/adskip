package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心跳状态机。
 *
 * 重点覆盖 2026-10-17 真机上撞到的那个 bug：
 * **存活核验瞬时失败把"连着"清掉之后，下一次核验成功必须能自愈** ——
 * 否则界面会一直停在「⚠ 服务已停止」，而服务其实活得好好的。
 */
class HeartbeatStateTest {

    private val t0 = 1_000_000L

    @Test
    fun `刚 beat 过就是连着`() {
        val s = HeartbeatState()
        assertFalse("还没上报过不该算连着", s.isConnected())
        s.beat(t0)
        assertTrue(s.isConnected())
    }

    @Test
    fun `disconnect 之后不再连着`() {
        val s = HeartbeatState()
        s.beat(t0)
        s.disconnect()
        assertFalse(s.isConnected())
    }

    @Test
    fun `核验瞬时失败后_下一次 beat 能自愈`() {
        // 这是那个真机 bug 的回归测试：
        // isStillEnabled() 瞬时失败 → disconnect() → 标志被清；
        // 之后核验恢复成功 → beat() → **必须重新算连着**，
        // 否则界面只能靠系统重绑才恢复，而重绑频率是系统行为、不该成为正确性的前提。
        val s = HeartbeatState()
        s.beat(t0)
        s.disconnect()
        assertFalse(s.isConnected())
        s.beat(t0 + 30_000L)
        assertTrue("beat 之后必须自愈", s.isConnected())
    }

    @Test
    fun `disconnect 保留最后活跃时间_界面能显示它是什么时候走的`() {
        val s = HeartbeatState()
        s.beat(t0)
        s.disconnect()
        assertEquals(30_000L, s.ageMs(t0 + 30_000L))
        assertEquals(t0, s.lastAliveAt())
    }

    @Test
    fun `从没上报过时年龄是负一`() {
        val s = HeartbeatState()
        assertEquals(-1L, s.ageMs(t0))
        assertEquals(ServiceHealth.UNKNOWN, s.health(t0))
    }

    @Test
    fun `健康度委托给 HeartbeatPolicy`() {
        val s = HeartbeatState()
        s.beat(t0)
        assertEquals(ServiceHealth.ALIVE, s.health(t0 + 1_000L))
        assertEquals(
            ServiceHealth.STALE,
            s.health(t0 + HeartbeatPolicy.STALE_MS + 1)
        )
    }

    @Test
    fun `connect 等价于立刻 beat 一次`() {
        val s = HeartbeatState()
        s.connect(t0)
        assertTrue(s.isConnected())
        assertEquals(t0, s.lastAliveAt())
    }
}
