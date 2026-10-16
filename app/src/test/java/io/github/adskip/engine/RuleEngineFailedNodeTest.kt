package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "点过没生效的节点，本次启动不再点"的测试。
 *
 * 背景（真机）：志愿汇 开屏上有个怎么点都不动的节点，每次启动都把点击预算吃光，
 * 真正的「跳过 N」轮不到 —— 实测 3 次启动 9 次点击只成功 1 次（11%）。
 */
class RuleEngineFailedNodeTest {

    private val screenW = 1216
    private val screenH = 2640

    /** 志愿汇 那个点不动的节点：坐标会变，但 id 始终是 tvSkip */
    private fun deadNode(top: Int = 0, bottom: Int = 88) = UiNode(
        text = "跳过",
        viewId = "com.zzw.october:id/tvSkip",
        className = "android.widget.TextView",
        left = 0, top = top, right = 210, bottom = bottom
    )

    private fun realNode() = UiNode(
        text = "跳过 5",
        className = "android.widget.TextView",
        left = 963, top = 98, right = 1174, bottom = 202,
        clickable = true
    )

    private fun snapshot(nodes: List<UiNode>, failed: Set<String> = emptySet()) = UiSnapshot(
        pkg = "com.zzw.october",
        activityName = "com.zzw.october.MainActivity",
        screenWidth = screenW,
        screenHeight = screenH,
        firstSeenAgeMs = 900,
        tapsThisLaunch = 1,
        failedNodeKeys = failed,
        nodes = nodes
    )

    private val engine = RuleEngine(
        listOf(
            AppRule(
                pkg = "com.zzw.october",
                windowMs = 5000,
                strategies = listOf(Strategy(Strategy.NODE_TEXT)),
                abortIf = emptyList()
            )
        )
    )

    @Test
    fun `没有被标记时照常点它`() {
        assertTrue(engine.evaluate(snapshot(listOf(deadNode()))) is Decision.TapNode)
    }

    @Test
    fun `标记失败后同一个节点不再被点_但仍在日志里可见`() {
        val dead = deadNode()
        val decision = engine.evaluate(snapshot(listOf(dead), failed = setOf(dead.nodeKey())))
        assertTrue("标记失败的节点不该再被点，实际=$decision", decision is Decision.Wait)
        // 仍要出现在候选里，否则"为什么不动手"无从排查
        // 引擎改动后，被否决的候选不再挂在 candidates 上（挂在那里会短路后面的策略），
        // 而是写进 reason —— 日志打印出来的内容完全一致，所以这里断言"诊断信息本身"。
        assertTrue(
            "诊断信息里应保留被否决的候选，实际=${(decision as Decision.Wait).reason}",
            (decision as Decision.Wait).reason.contains("都被安全规则否决")
        )
    }

    @Test
    fun `同一位置会被认出_换了位置要允许再试一次`() {
        // 同一位置必须认得出是同一个节点，否则会在同一个地方重复浪费点击
        assertEquals(deadNode().nodeKey(), deadNode().nodeKey())
        // 位置变了算不同的键 —— 这一条是修回归的关键：
        // 只按 view id 做键时，"未落位(0,0)点失败"会把"落位后(0,70)的重试"一并排除，
        // 而落位后的那一下正是以前能成功的位置。
        assertNotEquals(deadNode().nodeKey(), deadNode(top = 70, bottom = 158).nodeKey())
    }

    @Test
    fun `标记失败不影响其它节点`() {
        val dead = deadNode()
        val decision = engine.evaluate(
            snapshot(listOf(dead, realNode()), failed = setOf(dead.nodeKey()))
        )
        assertTrue(decision is Decision.TapNode)
        assertEquals("跳过 5", (decision as Decision.TapNode).node.label())
    }

    @Test
    fun `没有 id 时用标签加量化坐标做键_对小幅移动稳定`() {
        fun n(left: Int) = UiNode(text = "跳过", left = left, top = 100, right = left + 100, bottom = 160)
        assertEquals(n(900).nodeKey(), n(905).nodeKey())
    }
}