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

    /** 比例形式的搜索区域（规则库跨设备共用时应当用这个） */
    private fun ruleRatio(ratio: List<Double>?) = AppRule(
        pkg = "com.target",
        windowMs = 5000,
        nodeBoundsRatio = ratio,
        strategies = listOf(Strategy(Strategy.NODE_TEXT)),
        abortIf = emptyList()
    )

    private fun snapshotAt(nodes: List<UiNode>, w: Int, h: Int) = UiSnapshot(
        pkg = "com.target",
        activityName = "com.target.MainActivity",
        screenWidth = w,
        screenHeight = h,
        firstSeenAgeMs = 900,
        tapsThisLaunch = 0,
        nodes = nodes
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
    fun `志愿汇_顶部死节点出局_左右两种真按钮都保留`() {
        // 2026-11-10 真机日志（19:26~19:28）逐次核对：
        //   死节点     tvSkip            (0,0,210,88)     —— 每次启动都先被点一次，判定未生效
        //   真按钮(左) tev_countdown     (53,123,254,202) —— "4S | 跳过"
        //   真按钮(右) sdm_myoffer_skip  (925,191,1173,275) —— "5s | 跳过"
        //   真按钮(旧) 无 id            (963,98,1174,202) —— 24 天前的日志里出现过
        // 所以区域取"只砍掉顶部 90px 那条带"，三种真按钮的 top 都 >= 90，全部保留。
        val engine = RuleEngine(listOf(rule(listOf(0, 90, 1216, 2640))))
        val dead = node("跳过", 0, 0, 210, 88)
        val leftReal = node("4S | 跳过", 53, 123, 254, 202)

        val decision = engine.evaluate(snapshot(listOf(dead, leftReal)))
        assertTrue("应选中左侧真按钮，实际=$decision", decision is Decision.TapNode)
        assertEquals("必须跳过顶部死节点", leftReal, (decision as Decision.TapNode).node)

        // 同屏只剩死节点时：不点，交给后面的策略（盲点/等待）
        assertTrue(
            "只剩死节点时必须否决，实际=${engine.evaluate(snapshot(listOf(dead)))}",
            engine.evaluate(snapshot(listOf(dead))) is Decision.Wait
        )

        // 右侧与旧式按钮都在区域内（防止区域写得过窄把它们一起排除）
        assertTrue(engine.evaluate(snapshot(listOf(node("5s | 跳过", 925, 191, 1173, 275)))) is Decision.TapNode)
        assertTrue(engine.evaluate(snapshot(listOf(node("跳过 5", 963, 98, 1174, 202)))) is Decision.TapNode)
    }

    @Test
    fun `比例区域在两种分辨率上等价_志愿汇案例`() {
        // 规则库是所有设备共用的，所以正式规则用比例而不是绝对像素。
        // 志愿汇 取 [0.0, 0.034, 1.0, 1.0]：只砍顶部 3.4% 那条带。
        val engine = RuleEngine(listOf(ruleRatio(listOf(0.0, 0.034, 1.0, 1.0))))

        // 1216×2640：顶带 = 0.034*2640 ≈ 89px
        assertTrue(
            "1216 宽：死节点应出局",
            engine.evaluate(snapshotAt(listOf(node("跳过", 0, 0, 210, 88)), 1216, 2640)) is Decision.Wait
        )
        assertTrue(
            "1216 宽：左侧真按钮应保留",
            engine.evaluate(snapshotAt(listOf(node("4S | 跳过", 53, 123, 254, 202)), 1216, 2640)) is Decision.TapNode
        )

        // 1080×2400：顶带 = 0.034*2400 ≈ 81px；坐标按同比例缩放后结论必须一致
        assertTrue(
            "1080 宽：死节点同样应出局（绝对像素写法在这是错的）",
            engine.evaluate(snapshotAt(listOf(node("跳过", 0, 0, 187, 78)), 1080, 2400)) is Decision.Wait
        )
        assertTrue(
            "1080 宽：左侧真按钮同样应保留",
            engine.evaluate(snapshotAt(listOf(node("4S | 跳过", 47, 112, 226, 184)), 1080, 2400)) is Decision.TapNode
        )
    }

    @Test
    fun `比例优先于绝对像素`() {
        // 两个字段都写时以比例为准：绝对像素在别的设备上会错
        val both = AppRule(
            pkg = "com.target",
            windowMs = 5000,
            nodeBounds = listOf(0, 2000, 1216, 2640),   // 绝对区域：只允许屏幕下半部分
            nodeBoundsRatio = listOf(0.0, 0.0, 1.0, 1.0), // 比例区域：全屏
            strategies = listOf(Strategy(Strategy.NODE_TEXT)),
            abortIf = emptyList()
        )
        val engine = RuleEngine(listOf(both))
        // 顶部节点：绝对区域会排除，比例区域允许 —— 应被比例救回来
        assertTrue(engine.evaluate(snapshotAt(listOf(node("跳过", 53, 123, 254, 202)), 1216, 2640)) is Decision.TapNode)
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