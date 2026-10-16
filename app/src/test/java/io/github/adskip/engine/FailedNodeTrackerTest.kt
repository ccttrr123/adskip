package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跨启动的"屡次失败节点"拉黑逻辑 */
class FailedNodeTrackerTest {

    private val now = 1_000_000L
    private val dead = "com.zzw.october:id/tvSkip"

    @Test
    fun `偶发失败一次不拉黑`() {
        val t = FailedNodeTracker()
        t.recordFailure(dead, now)
        assertFalse(t.blacklist(now).contains(dead))
    }

    @Test
    fun `连续失败到阈值才拉黑`() {
        val t = FailedNodeTracker()
        repeat(FailedNodeTracker.DEFAULT_THRESHOLD) { t.recordFailure(dead, now) }
        assertTrue(t.blacklist(now).contains(dead))
    }

    @Test
    fun `成功一次就立刻恢复`() {
        val t = FailedNodeTracker()
        repeat(5) { t.recordFailure(dead, now) }
        assertTrue(t.blacklist(now).contains(dead))
        t.recordSuccess(dead)
        assertFalse("成功过就不该再被拉黑", t.blacklist(now).contains(dead))
    }

    @Test
    fun `超过有效期自动解除`() {
        val t = FailedNodeTracker()
        repeat(5) { t.recordFailure(dead, now) }
        assertTrue(t.blacklist(now).contains(dead))
        assertFalse(
            "过了有效期应自动放行，重新给它机会",
            t.blacklist(now + FailedNodeTracker.DEFAULT_TTL_MS + 1).contains(dead)
        )
    }

    @Test
    fun `空键不记`() {
        val t = FailedNodeTracker()
        repeat(10) { t.recordFailure("", now) }
        assertTrue(t.blacklist(now).isEmpty())
    }

    @Test
    fun `存取往返不丢信息`() {
        val t = FailedNodeTracker()
        repeat(4) { t.recordFailure(dead, now) }
        val restored = FailedNodeTracker().apply { restore(t.snapshot()) }
        assertEquals(t.blacklist(now), restored.blacklist(now))
    }
}