package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * nodeBounds（规则声明的搜索区域）的测试。
 *
 * 补这笔账的原因很实际：这个改动当时**只有真机验证**，而真机验证在同一天骗过我一次
 * （志愿汇 的按钮坐标会移动，按坐标比对的校验把"换了位置"误判成"跳掉了"）。
 * 语义：节点必须**完全落在**区域内才被采用。
 */
class RuleEngineBoundsTest {

    private val screenW = 1216
    private val screenH = 2640

    private fun node(label: String, l: Int, t: Int, r: Int, b: Int) = UiNode(
        text = label,
        className = "android.widget.TextView",
        left = l, top = t, right = r, bottom = b,
        clickable = true
    )

    private fun snapshot(nodes: List<UiNode>) = UiSnapshot(
        pkg = "com.target",
        activityName = "com.target.MainActivity",
        screenWidth = screenW,
        screenHeight = screenH,
        firstSeenAgeMs = 900,
        tapsThisLaunch = 0,
        nodes = nodes
    )

    private fun rule(bounds: List<Int>?) = AppRule(
        pkg = "com.target",
        windowMs = 5000,
        nodeBounds = bounds,
        strategies = listOf(Strategy(Strategy.NODE_TEXT)),
        abortIf = emptyList()
    )

    @Test
    fun `区域内的候选会被点击`() {
        val engine = RuleEngine(listOf(rule(listOf(900, 60, 1216, 320))))
        val inRegion = node("跳过 5", 963, 98, 1174, 202)
        val decision = engine.evaluate(snapshot(listOf(inRegion)))
        assertTrue(decision is Decision.TapNode)
        assertEquals(inRegion, (decision as Decision.TapNode).node)
    }

    @Test
    fun `区域外的候选被否决_志愿汇的真机案例`() {
        // 真机：志愿汇 同屏有左上角 (0,0,210,88) 与右上角真按钮。
        // 限定右上角后左边那个必须出局 —— 它每次启动都会把点击预算吃光。
        val engine = RuleEngine(listOf(rule(listOf(900, 60, 1216, 320))))
        val outside = node("跳过", 0, 0, 210, 88)

        val decision = engine.evaluate(snapshot(listOf(outside)))
        assertTrue("区域外的候选必须被否决，实际=$decision", decision is Decision.Wait)
        // 引擎改动后，被否决的候选不再挂在 candidates 上（挂在那里会短路后面的策略），
        // 而是写进 reason —— 日志打印出来的内容完全一致，所以这里断言"诊断信息本身"。
        assertTrue(
            "诊断信息里应保留被否决的候选，实际=${(decision as Decision.Wait).reason}",
            (decision as Decision.Wait).reason.contains("都被安全规则否决")
        )
    }

    @Test
    fun `边界恰好相等时算在区域内`() {
        val engine = RuleEngine(listOf(rule(listOf(100, 200, 300, 400))))
        val exactly = node("跳过", 100, 200, 300, 400)
        assertTrue(engine.evaluate(snapshot(listOf(exactly))) is Decision.TapNode)
    }

    @Test
    fun `越界一个像素就不算在区域内`() {
        val engine = RuleEngine(listOf(rule(listOf(100, 200, 300, 400))))
        val onePixelOver = node("跳过", 99, 200, 300, 400)
        assertTrue(engine.evaluate(snapshot(listOf(onePixelOver))) is Decision.Wait)
    }

    @Test
    fun `不写区域时不限制位置`() {
        val engine = RuleEngine(listOf(rule(null)))
        val anywhere = node("跳过 5", 10, 10, 200, 90)
        assertTrue(engine.evaluate(snapshot(listOf(anywhere))) is Decision.TapNode)
    }

    @Test
    fun `区域只影响位置_其它防误点规则照旧生效`() {
        // 区域放到全屏，但"面积几乎占满屏幕 = 容器冒充跳过按钮"仍必须被否决
        val engine = RuleEngine(listOf(rule(listOf(0, 0, 1216, 2640))))
        val container = UiNode(
            contentDescription = "广告跳过",
            className = "android.widget.FrameLayout",
            left = 0, top = 0, right = screenW, bottom = screenH
        )
        assertTrue(engine.evaluate(snapshot(listOf(container))) is Decision.Wait)
    }
}