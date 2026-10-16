package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchTrackerTest {

    @Test
    fun `包名不变时年龄持续增长`() {
        val tracker = LaunchTracker()
        tracker.observe("com.a", 1_000L)
        assertEquals(0L, tracker.ageMs(1_000L))
        assertEquals(500L, tracker.ageMs(1_500L))
        assertEquals(3_000L, tracker.ageMs(4_000L))
    }

    @Test
    fun `包名切换会重置起点`() {
        val tracker = LaunchTracker()
        tracker.observe("com.a", 1_000L)
        tracker.observe("com.a", 2_000L)
        assertEquals(1_000L, tracker.ageMs(2_000L))

        tracker.observe("com.android.launcher", 3_000L)
        tracker.observe("com.a", 4_000L)

        // 离开又回来 = 新的一次启动，时间窗必须重置
        assertEquals(0L, tracker.ageMs(4_000L))
    }

    @Test
    fun `离开桌面再回来也算新启动`() {
        // 这是最容易被写错的地方：不能靠"停留过久就重置"，
        // 只能靠包名变化，否则时间窗永远关不掉。
        val tracker = LaunchTracker()
        tracker.observe("com.a", 0L)
        assertEquals(60_000L, tracker.ageMs(60_000L)) // 在前台待了 60 秒
        tracker.observe("com.android.launcher", 60_100L)
        tracker.observe("com.a", 60_200L)
        assertEquals(0L, tracker.ageMs(60_200L))
    }

    @Test
    fun `点击计数在本次启动内累计_切换包名后归零`() {
        val tracker = LaunchTracker()
        tracker.observe("com.a", 0L)
        assertEquals(0, tracker.tapsThisLaunch())
        assertEquals(1, tracker.recordTap())
        assertEquals(2, tracker.recordTap())

        tracker.observe("com.b", 10L)
        assertEquals(0, tracker.tapsThisLaunch())
    }

    @Test
    fun `未观察到任何包名时状态为空`() {
        val tracker = LaunchTracker()
        assertNull(tracker.currentPkg())
        assertEquals(0L, tracker.ageMs(999L))
    }

    @Test
    fun `reset 会清空状态`() {
        val tracker = LaunchTracker()
        tracker.observe("com.a", 0L)
        tracker.recordTap()
        tracker.reset()
        assertNull(tracker.currentPkg())
        assertEquals(0, tracker.tapsThisLaunch())
        assertEquals(0L, tracker.ageMs(1_000L))
    }
}
