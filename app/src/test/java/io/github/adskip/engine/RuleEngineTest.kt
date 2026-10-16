package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定引擎的安全性测试。
 *
 * 这个文件比功能测试重要得多：功能没做到只是"没跳过"，
 * 而这里每条断言对应一种"会点到广告本体"的失效路径。
 */
class RuleEngineTest {

    // 注意：盲点策略有"启动后 700ms 才允许出手"的等待期（真机：0ms 点空、936ms 点中），
    // 所以涉及盲点的用例把启动时刻设在等待期之后；这个数字不是随便写的。

    private val screenW = 1080
    private val screenH = 2400

    private fun nodeText(text: String, clickable: Boolean = true) = UiNode(
        text = text,
        className = "android.widget.TextView",
        left = 800, top = 80, right = 1040, bottom = 160,
        clickable = clickable
    )

    private fun nodeEditText() = UiNode(
        className = "android.widget.EditText",
        left = 100, top = 1000, right = 900, bottom = 1100
    )

    private fun nodeFullscreenImage() = UiNode(
        className = "android.widget.ImageView",
        left = 0, top = 0, right = screenW, bottom = screenH
    )

    private fun snapshot(
        pkg: String = "com.target",
        activity: String = "com.target.SplashActivity",
        ageMs: Long = 900,
        taps: Int = 0,
        nodes: List<UiNode> = emptyList()
    ) = UiSnapshot(
        pkg = pkg,
        activityName = activity,
        screenWidth = screenW,
        screenHeight = screenH,
        firstSeenAgeMs = ageMs,
        tapsThisLaunch = taps,
        nodes = nodes
    )

    private fun rule(
        strategies: List<Strategy> = listOf(Strategy(Strategy.NODE_TEXT)),
        abortIf: List<AbortCondition> = listOf(
            AbortCondition(AbortCondition.HAS_EDITTEXT),
            AbortCondition(AbortCondition.TEXT_CONTAINS, "登录")
        ),
        evidence: List<String> = listOf(
            AppRule.EVIDENCE_COUNTDOWN,
            AppRule.EVIDENCE_ACTIVITY_HINT,
            AppRule.EVIDENCE_FULLSCREEN_MEDIA
        ),
        windowMs: Long = 3000,
        activityHints: List<String> = listOf("Splash")
    ) = AppRule(
        pkg = "com.target",
        name = "测试应用",
        windowMs = windowMs,
        activityHints = activityHints,
        evidence = evidence,
        strategies = strategies,
        abortIf = abortIf
    )

    // ---------------------------------------------------------- 时间窗

    @Test
    fun `没有规则的应用完全不介入`() {
        val engine = RuleEngine(listOf(rule()))
        val decision = engine.evaluate(snapshot(pkg = "com.other", nodes = listOf(nodeText("跳过"))))
        assertEquals(Decision.Ignore, decision)
    }

    @Test
    fun `超出时间窗后不再介入`() {
        val engine = RuleEngine(listOf(rule(windowMs = 3000)))
        val decision = engine.evaluate(
            snapshot(ageMs = 3001, nodes = listOf(nodeText("跳过")))
        )
        // 用独立的 Expired 而不是静默的 Ignore，这样日志里能说清"为什么没动"
        assertTrue(decision is Decision.Expired)
        assertEquals(3001L, (decision as Decision.Expired).ageMs)
    }

    @Test
    fun `时间窗边界上仍然介入`() {
        val engine = RuleEngine(listOf(rule(windowMs = 3000)))
        val decision = engine.evaluate(
            snapshot(ageMs = 3000, nodes = listOf(nodeText("跳过")))
        )
        assertTrue(decision is Decision.TapNode)
    }

    // ---------------------------------------------------------- 熔断

    @Test
    fun `出现输入框时熔断_这是登录页而不是广告`() {
        val engine = RuleEngine(listOf(rule()))
        val decision = engine.evaluate(
            snapshot(nodes = listOf(nodeEditText(), nodeText("跳过")))
        )
        assertTrue("应熔断，实际=$decision", decision is Decision.Abort)
    }

    @Test
    fun `出现登录文案时熔断`() {
        val engine = RuleEngine(listOf(rule()))
        val decision = engine.evaluate(
            snapshot(nodes = listOf(nodeText("登录"), nodeText("跳过")))
        )
        assertTrue(decision is Decision.Abort)
    }

    @Test
    fun `达到点击上限后熔断`() {
        val engine = RuleEngine(listOf(rule()))
        val decision = engine.evaluate(
            snapshot(taps = RuleEngine.MAX_TAPS_PER_LAUNCH, nodes = listOf(nodeText("跳过")))
        )
        assertTrue(decision is Decision.Abort)
    }

    // ---------------------------------------------------------- 精确策略

    @Test
    fun `文本命中跳过按钮时点击该节点`() {
        val engine = RuleEngine(listOf(rule()))
        val skip = nodeText("跳过 3")
        val decision = engine.evaluate(snapshot(nodes = listOf(nodeFullscreenImage(), skip)))
        assertTrue(decision is Decision.TapNode)
        assertEquals(skip, (decision as Decision.TapNode).node)
    }

    @Test
    fun `时间窗内但界面还没出现跳过按钮时等待`() {
        val engine = RuleEngine(listOf(rule()))
        val decision = engine.evaluate(snapshot(nodes = listOf(nodeFullscreenImage())))
        assertTrue(decision is Decision.Wait)
    }

    // ---------------------------------------------------------- 盲点必须要有旁证

    @Test
    fun `盲点策略在没有任何旁证时不允许执行`() {
        val engine = RuleEngine(
            listOf(rule(strategies = listOf(Strategy(Strategy.TAP_RATIO, x = 0.93f, y = 0.045f))))
        )
        // 界面只有一个普通列表项：既没倒计时、Activity 名也不含 Splash、也没有全屏媒体
        val decision = engine.evaluate(
            snapshot(
                activity = "com.target.MainActivity",
                nodes = listOf(UiNode(className = "android.widget.ListView", right = 1080, bottom = 800))
            )
        )
        assertTrue("无旁证时不该动手，实际=$decision", decision is Decision.Wait)
    }

    @Test
    fun `盲点策略在有倒计时旁证时允许执行`() {
        val engine = RuleEngine(
            listOf(rule(strategies = listOf(Strategy(Strategy.TAP_RATIO, x = 0.93f, y = 0.045f))))
        )
        val decision = engine.evaluate(
            snapshot(nodes = listOf(nodeFullscreenImage(), nodeText("跳过 3")))
        )
        assertTrue(decision is Decision.TapPoint)
        // JUnit 的 assertTrue 没有 Kotlin 契约，不会智能转换，必须显式转型后再取字段
        val tap = decision as Decision.TapPoint
        assertEquals((screenW * 0.93f).toInt(), tap.x)
        assertEquals((screenH * 0.045f).toInt(), tap.y)
    }

    @Test
    fun `盲点策略在有全屏媒体旁证时允许执行`() {
        val engine = RuleEngine(
            listOf(rule(strategies = listOf(Strategy(Strategy.TAP_RATIO, x = 0.93f, y = 0.045f))))
        )
        val decision = engine.evaluate(snapshot(nodes = listOf(nodeFullscreenImage())))
        assertTrue(decision is Decision.TapPoint)
    }

    @Test
    fun `盲点策略在 Activity 名命中旁证时允许执行`() {
        val engine = RuleEngine(
            listOf(rule(strategies = listOf(Strategy(Strategy.TAP_RATIO, x = 0.93f, y = 0.045f))))
        )
        val decision = engine.evaluate(
            snapshot(
                activity = "com.target.SplashAdActivity",
                nodes = listOf(UiNode(className = "android.widget.ListView", right = 1080, bottom = 800))
            )
        )
        assertTrue(decision is Decision.TapPoint)
    }

    @Test
    fun `规则声明不需要某些旁证时_对应旁证不再解锁盲点`() {
        // 只认倒计时：全屏媒体不算数
        val engine = RuleEngine(
            listOf(
                rule(
                    strategies = listOf(Strategy(Strategy.TAP_RATIO, x = 0.93f, y = 0.045f)),
                    evidence = listOf(AppRule.EVIDENCE_COUNTDOWN)
                )
            )
        )
        val decision = engine.evaluate(snapshot(nodes = listOf(nodeFullscreenImage())))
        assertTrue(decision is Decision.Wait)
    }

    @Test
    fun `屏幕尺寸未知时不执行盲点`() {
        val engine = RuleEngine(
            listOf(rule(strategies = listOf(Strategy(Strategy.TAP_RATIO, x = 0.93f, y = 0.045f))))
        )
        val bad = snapshot(nodes = listOf(nodeFullscreenImage())).copy(screenWidth = 0, screenHeight = 0)
        assertTrue(engine.evaluate(bad) is Decision.Wait)
    }

    @Test
    fun `返回键兜底同样需要旁证`() {
        val engine = RuleEngine(
            listOf(rule(strategies = listOf(Strategy(Strategy.GLOBAL_BACK))))
        )
        val noEvidence = engine.evaluate(
            snapshot(
                activity = "com.target.MainActivity",
                nodes = listOf(UiNode(className = "android.widget.ListView", right = 1080, bottom = 800))
            )
        )
        assertTrue(noEvidence is Decision.Wait)

        val withEvidence = engine.evaluate(snapshot(nodes = listOf(nodeFullscreenImage())))
        assertTrue(withEvidence is Decision.GlobalBack)
    }

    // ---------------------------------------------------------- 策略顺序

    @Test
    fun `策略按声明顺序生效_先精确后粗略`() {
        val engine = RuleEngine(
            listOf(
                rule(
                    strategies = listOf(
                        Strategy(Strategy.NODE_TEXT),
                        Strategy(Strategy.TAP_RATIO, x = 0.93f, y = 0.045f)
                    )
                )
            )
        )
        val decision = engine.evaluate(
            snapshot(nodes = listOf(nodeFullscreenImage(), nodeText("跳过")))
        )
        assertTrue("应该优先用文本节点，实际=$decision", decision is Decision.TapNode)
    }

    @Test
    fun `requireActivityHint 未命中时跳过该策略`() {
        val engine = RuleEngine(
            listOf(
                rule(
                    strategies = listOf(Strategy(Strategy.NODE_TEXT, requireActivityHint = true))
                )
            )
        )
        val decision = engine.evaluate(
            snapshot(activity = "com.target.MainActivity", nodes = listOf(nodeText("跳过")))
        )
        assertTrue(decision is Decision.Wait)
    }

    @Test
    fun `被禁用的规则不生效`() {
        val engine = RuleEngine(listOf(rule().copy(enabled = false)))
        assertEquals(Decision.Ignore, engine.evaluate(snapshot(nodes = listOf(nodeText("跳过")))))
        assertEquals(0, engine.ruleCount)
    }

    // ---------------------------------------------------------- 候选筛选（真机回归）

    private fun nodeAt(label: String, centerX: Int, centerY: Int) = UiNode(
        text = label,
        className = "android.widget.TextView",
        left = centerX - 80, top = centerY - 30,
        right = centerX + 80, bottom = centerY + 30,
        clickable = true
    )

    @Test
    fun `中间区域且无倒计时证据的候选被否决时什么都不做`() {
        // 真机回归：贴吧 出现过「广告跳过」@(1022,1965)，屏幕 74% 高度处，
        // 位于广告内容区。它不是跳过按钮，点它等于给广告打开落地页。
        val engine = RuleEngine(listOf(rule()))
        val adContent = nodeAt("广告跳过", centerX = 1022 * screenW / 1216, centerY = 1965 * screenH / 2640)

        val decision = engine.evaluate(snapshot(nodes = listOf(adContent)))
        assertTrue("这类候选应该什么都不做，实际=$decision", decision is Decision.Wait)
        // 引擎改动后，被否决的候选不再挂在 candidates 上（挂在那里会短路后面的策略），
        // 而是写进 reason —— 日志打印出来的内容完全一致，所以这里断言"诊断信息本身"。
        assertTrue(
            "诊断信息里应保留被否决的候选，实际=${(decision as Decision.Wait).reason}",
            (decision as Decision.Wait).reason.contains("都被安全规则否决")
        )
    }

    @Test
    fun `位于屏幕底部的真按钮仍然会被点击`() {
        // 真机回归（这条纠正了我自己的错误判断）：
        // B站 的跳过按钮 view id 是 count_down，中心 (1024,2431) = 屏幕 92% 高度处。
        // 它长在底部，但它是**真按钮**，必须被点。
        val engine = RuleEngine(listOf(rule()))
        val real = UiNode(
            text = "跳过 5",
            viewId = "tv.danmaku.bili:id/count_down",
            className = "android.widget.TextView",
            left = 888, top = 2363, right = 1160, bottom = 2499,
            clickable = true
        )

        val decision = engine.evaluate(snapshot(nodes = listOf(real)))
        assertTrue("底部不等于错误，B站 的真按钮就在底部，实际=$decision", decision is Decision.TapNode)
        assertEquals("跳过 5", (decision as Decision.TapNode).node.label())
    }

    @Test
    fun `多个候选时选右上角并把其余候选带出来用于诊断`() {
        // 真机回归：贴吧 同一次启动里同时有 (1022,1965) 的「广告跳过」和 (1037,122) 的「跳过 05」
        val engine = RuleEngine(listOf(rule()))
        val wrong = nodeAt("广告跳过", centerX = 1022 * screenW / 1216, centerY = 1965 * screenH / 2640)
        val right = nodeAt("跳过 05", centerX = 1037 * screenW / 1216, centerY = 122 * screenH / 2640)

        val decision = engine.evaluate(snapshot(nodes = listOf(wrong, right)))
        assertTrue(decision is Decision.TapNode)
        val tap = decision as Decision.TapNode
        assertEquals("跳过 05", tap.node.label())
        assertEquals(1, tap.rejected.size)
        assertEquals("广告跳过", tap.rejected[0].label())
    }

    @Test
    fun `盲点策略因缺少旁证未解锁时_理由必须能说清原因`() {
        // 规则只认 fullscreen_media 作为旁证，而界面既没有全屏媒体、Activity 名也不含关键字
        val engine = RuleEngine(
            listOf(
                rule(
                    strategies = listOf(Strategy(Strategy.TAP_RATIO, x = 0.93f, y = 0.045f)),
                    evidence = listOf(AppRule.EVIDENCE_FULLSCREEN_MEDIA)
                )
            )
        )
        val decision = engine.evaluate(
            snapshot(
                activity = "com.target.MainActivity",
                nodes = listOf(UiNode(className = "android.widget.ListView", right = 1080, bottom = 800))
            )
        )
        assertTrue(decision is Decision.Wait)
        val reason = (decision as Decision.Wait).reason

        // 这三条对应"配了盲点规则却不生效"时必须能回答的三个问题
        assertTrue("要说清是盲点未解锁，实际=$reason", reason.contains("盲点策略未解锁"))
        assertTrue("要带出规则要求的旁证，实际=$reason", reason.contains("fullscreen_media"))
        assertTrue("要带出 Activity 名以便改用 activity_hint，实际=$reason", reason.contains("MainActivity"))
        // 且不能退化成通用文案，否则调用方的日志会把它当成"什么都没找到"
        assertNotEquals(RuleEngine.REASON_NOTHING_FOUND, reason)
    }
}
