package io.github.adskip.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盲点最小出手时间（[AppRule.blindMinAgeMs]）的行为。
 *
 * 背景（志愿汇 2026-11-10 真机）：0ms 出手时跳过按钮还没渲染，盲点必然点空、白耗一次点击预算；
 * 实测按钮要到 2 秒以后才可点（1200ms 时盲点会点进广告、打开浏览器），所以规则里配 2400ms：
 * 让文本先出手，盲点只在读不到文本时兜底。
 *
 * 本测试用 1200 作为门槛示例值（与规则库里的 2400 无关，这里只验证门槛机制本身）。
 *
 * 锁死三条性质：
 *  1. 未到门槛时**不执行盲点**（否则等于回到 0ms 点空的老问题）；
 *  2. 到门槛后盲点仍可兜底（不能把兜底一起卡死）；
 *  3. **文本策略不受门槛影响**（读得到按钮就必须立刻点，不能等）。
 */
class BlindMinAgeTest {

    private val screenW = 1216
    private val screenH = 2640

    /** 可读的跳过按钮（真机 志愿汇 tev_countdown 的位置） */
    private fun skipNode() = UiNode(
        text = "4S | 跳过",
        className = "android.widget.TextView",
        left = 53, top = 123, right = 254, bottom = 202,
        clickable = true
    )

    /** 占屏 >60% 的图片 = "广告还在屏幕上"的旁证 */
    private fun fullscreenAdNode() = UiNode(
        className = "android.widget.ImageView",
        left = 0, top = 0, right = screenW, bottom = screenH
    )

    private fun snapshot(ageMs: Long, nodes: List<UiNode> = listOf(fullscreenAdNode())) = UiSnapshot(
        pkg = "com.zzw.october",
        activityName = "com.zzw.october.LaunchActivity",
        screenWidth = screenW,
        screenHeight = screenH,
        firstSeenAgeMs = ageMs,
        tapsThisLaunch = 0,
        nodes = nodes
    )

    private fun engine(minAgeMs: Long) = RuleEngine(
        listOf(
            AppRule(
                pkg = "com.zzw.october",
                windowMs = 8000,
                blindMinAgeMs = minAgeMs,
                activityHints = listOf("LaunchActivity"),
                evidence = listOf(AppRule.EVIDENCE_FULLSCREEN_MEDIA, AppRule.EVIDENCE_ACTIVITY_HINT),
                strategies = listOf(
                    Strategy(Strategy.NODE_TEXT),
                    Strategy(Strategy.TAP_RATIO, x = 0.126f, y = 0.062f)
                ),
                abortIf = emptyList()
            )
        )
    )

    @Test
    fun `未到最小出手时间_盲点不执行`() {
        val decision = engine(1200).evaluate(snapshot(300))
        assertTrue("应该等待而不是盲点，实际=$decision", decision is Decision.Wait)
        assertTrue(
            "理由里应说明在等盲点门槛，实际=${(decision as Decision.Wait).reason}",
            decision.reason.contains("再出手")
        )
    }

    @Test
    fun `到达最小出手时间_盲点可以兜底`() {
        val decision = engine(1200).evaluate(snapshot(1200))
        assertTrue("到门槛后应允许盲点，实际=$decision", decision is Decision.TapPoint)
    }

    @Test
    fun `文本策略不受盲点最小出手时间限制`() {
        // 0ms 时如果按钮已经可读，必须立刻精确点击，不能等 1200ms
        val decision = engine(1200).evaluate(snapshot(0, listOf(skipNode(), fullscreenAdNode())))
        assertTrue("文本精确点击被时间门槛卡住了，实际=$decision", decision is Decision.TapNode)
    }

    @Test
    fun `默认0_行为与以前一致_立刻允许盲点`() {
        val decision = engine(0).evaluate(snapshot(0, listOf(fullscreenAdNode())))
        assertTrue("默认 0 时应该立刻允许盲点，实际=$decision", decision is Decision.TapPoint)
    }
}
