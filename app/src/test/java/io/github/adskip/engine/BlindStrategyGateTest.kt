package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盲点策略的解锁条件。
 *
 * 背景（真机）：志愿汇 有个广告变体的「跳过」是自绘的（带进度环），文字节点读不到，
 * 所以给规则加了 `tap_ratio` 兜底 + `evidence: [fullscreen_media]` 作为解锁条件。
 *
 * 这里锁死两条性质，防止将来有人"顺手"把 evidence 套到 node_text 上：
 *  1. 盲点必须有旁证才解锁（否则等于闭着眼睛点）
 *  2. **node_text 不受 evidence 影响** —— 否则已经能用的精确点击会被一起卡死
 */
class BlindStrategyGateTest {

    private val screenW = 1216
    private val screenH = 2640

    private fun skipTextNode() = UiNode(
        text = "跳过", className = "android.widget.TextView",
        left = 1057, top = 139, right = 1151, bottom = 236, clickable = true
    )

    /** 占屏 >60% 的图片节点 = "广告还在屏幕上" */
    private fun fullscreenAdNode() = UiNode(
        className = "android.widget.ImageView",
        left = 0, top = 0, right = screenW, bottom = screenH
    )

    private fun snapshot(nodes: List<UiNode>) = UiSnapshot(
        pkg = "com.zzw.october",
        activityName = "com.zzw.october.SplashActivity",
        screenWidth = screenW,
        screenHeight = screenH,
        firstSeenAgeMs = 900,
        tapsThisLaunch = 0,
        nodes = nodes
    )

    private fun rule() = AppRule(
        pkg = "com.zzw.october",
        windowMs = 8000,
        evidence = listOf(AppRule.EVIDENCE_FULLSCREEN_MEDIA),
        strategies = listOf(
            Strategy(Strategy.NODE_TEXT),
            Strategy(Strategy.TAP_RATIO, x = 0.908f, y = 0.071f)
        ),
        abortIf = emptyList()
    )

    private val engine = RuleEngine(listOf(rule()))

    @Test
    fun `有全屏广告时盲点兜底解锁`() {
        val decision = engine.evaluate(snapshot(listOf(fullscreenAdNode())))
        assertTrue("有广告却没解锁盲点，实际=$decision", decision is Decision.TapPoint)
    }

    @Test
    fun `没有全屏广告时盲点必须锁住`() {
        // 只有普通界面（无大图、无跳过文字）→ 什么都不该做
        val decision = engine.evaluate(
            snapshot(listOf(UiNode(text = "首页", left = 10, top = 300, right = 200, bottom = 360)))
        )
        assertTrue("没有旁证却动手了，实际=$decision", decision is Decision.Wait)
    }

    @Test
    fun `node_text 不受 evidence 约束`() {
        // 只有可读的跳过按钮、**没有**全屏大图 —— 精确点击必须照常生效。
        // 这条如果挂了，说明 evidence 被错误地套到了 node_text 上，
        // 所有"读得到按钮但没大图"的应用都会静默失效。
        val node = skipTextNode()
        val decision = engine.evaluate(snapshot(listOf(node)))
        assertTrue("精确点击被 evidence 卡住了，实际=$decision", decision is Decision.TapNode)
        assertEquals(node, (decision as Decision.TapNode).node)
    }

    @Test
    fun `能精确点击时不会走到盲点`() {
        val node = skipTextNode()
        val decision = engine.evaluate(snapshot(listOf(node, fullscreenAdNode())))
        assertTrue("应该优先精确点击，实际=$decision", decision is Decision.TapNode)
    }
}