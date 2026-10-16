package io.github.adskip.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选被安全规则全部否决时，**不能短路后面的策略**。
 *
 * 真机 bug（志愿汇 AM广告变体）：屏上有个点不动的 tvSkip 一直占着候选位，
 * 引擎每次都在"都被安全规则否决"处 return，导致后面的盲点兜底永远轮不到 ——
 * 表现就是"兜底策略好像没生效"，而日志里连"盲点策略未解锁"都不再出现。
 */
class RejectedFallthroughTest {

    private val deadNode = UiNode(
        text = "跳过",
        viewId = "com.zzw.october:id/tvSkip",
        className = "android.widget.TextView",
        left = 0, top = 0, right = 210, bottom = 88
    )

    private fun snapshot(failed: Set<String>) = UiSnapshot(
        pkg = "com.zzw.october",
        activityName = "com.zzw.october.LaunchActivity",
        screenWidth = 1216,
        screenHeight = 2640,
        firstSeenAgeMs = 900,
        tapsThisLaunch = 1,
        failedNodeKeys = failed,
        nodes = listOf(deadNode)
    )

    private fun engine(withBlind: Boolean) = RuleEngine(
        listOf(
            AppRule(
                pkg = "com.zzw.october",
                windowMs = 8000,
                activityHints = listOf("LaunchActivity"),
                evidence = listOf(AppRule.EVIDENCE_FULLSCREEN_MEDIA, AppRule.EVIDENCE_ACTIVITY_HINT),
                strategies = if (withBlind) {
                    listOf(
                        Strategy(Strategy.NODE_TEXT),
                        Strategy(Strategy.TAP_RATIO, x = 0.908f, y = 0.071f)
                    )
                } else {
                    listOf(Strategy(Strategy.NODE_TEXT))
                },
                abortIf = emptyList()
            )
        )
    )

    @Test
    fun `候选全被否决时_后面的盲点策略仍会被尝试`() {
        val decision = engine(withBlind = true)
            .evaluate(snapshot(failed = setOf(deadNode.nodeKey())))
        assertTrue("兜底被短路了，实际=$decision", decision is Decision.TapPoint)
    }

    @Test
    fun `没有后续策略时_仍然是什么都不做且保留诊断信息`() {
        val decision = engine(withBlind = false)
            .evaluate(snapshot(failed = setOf(deadNode.nodeKey())))
        assertTrue(decision is Decision.Wait)
        val reason = (decision as Decision.Wait).reason
        assertTrue("诊断信息里应保留候选详情，实际=$reason", reason.contains("都被安全规则否决"))
    }
}