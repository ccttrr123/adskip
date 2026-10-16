package io.github.adskip.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盲点解锁的**或**语义。
 *
 * 真机依据：志愿汇 的 "AM广告" 变体不是 ImageView/WebView，所以
 * "屏幕上有全屏媒体" 这条旁证一个都命中不了，日志里是
 * `盲点策略未解锁：规则要求旁证 [fullscreen_media]，当前一个都没出现（实际命中：无）`。
 * 但日志同时告诉我们它发生在 `com.zzw.october.LaunchActivity`（开屏页）——
 * 所以加上 activity_hint 作为另一条旁证：**只要在开屏页，盲点就允许出手**。
 */
class BlindUnlockTest {

    private fun snapshot(nodes: List<UiNode>, activity: String) = UiSnapshot(
        pkg = "com.zzw.october",
        activityName = activity,
        screenWidth = 1216,
        screenHeight = 2640,
        firstSeenAgeMs = 900,
        tapsThisLaunch = 0,
        nodes = nodes
    )

    private val engine = RuleEngine(
        listOf(
            AppRule(
                pkg = "com.zzw.october",
                windowMs = 8000,
                activityHints = listOf("LaunchActivity"),
                evidence = listOf(AppRule.EVIDENCE_FULLSCREEN_MEDIA, AppRule.EVIDENCE_ACTIVITY_HINT),
                strategies = listOf(
                    Strategy(Strategy.NODE_TEXT),
                    Strategy(Strategy.TAP_RATIO, x = 0.908f, y = 0.071f)
                ),
                abortIf = emptyList()
            )
        )
    )

    private val emptyScreen = listOf(UiNode(text = "加载中", left = 10, top = 700, right = 200, bottom = 760))

    @Test
    fun `在开屏页时_即使识别不出广告图片也允许盲点出手`() {
        val decision = engine.evaluate(snapshot(emptyScreen, "com.zzw.october.LaunchActivity"))
        assertTrue("开屏页没解锁盲点，实际=$decision", decision is Decision.TapPoint)
    }

    @Test
    fun `离开开屏页后_盲点必须重新锁上`() {
        // 到了主界面就不该再闭着眼睛点右上角（那里可能是头像/搜索等真实功能）
        val decision = engine.evaluate(snapshot(emptyScreen, "com.zzw.october.MainActivity"))
        assertTrue("主界面不该盲点，实际=$decision", decision is Decision.Wait)
    }
}