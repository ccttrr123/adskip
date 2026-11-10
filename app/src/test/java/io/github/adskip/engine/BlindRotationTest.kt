package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同一规则配多个盲点时的轮换。
 *
 * 真机依据（2026-11-10，用户两次截图）：志愿汇 的跳过按钮至少有两种位置 ——
 * 左上药丸 `(53,123,254,202)`，右上圆钮 `(约 1102,162)`。一个盲点覆盖不了两个，
 * 所以引擎按"本次启动第几次点击"轮换：第 1 次用第一个 tap_ratio，第 2 次用第二个，如此类推。
 *
 * 没有轮换时，第一个 tap_ratio 永远会挡住后面那个（策略按顺序取第一个生效的）。
 */
class BlindRotationTest {

    private fun snapshot(taps: Int) = UiSnapshot(
        pkg = "com.zzw.october",
        activityName = "com.zzw.october.LaunchActivity",
        screenWidth = 1216,
        screenHeight = 2640,
        firstSeenAgeMs = 900,
        tapsThisLaunch = taps,
        nodes = listOf(UiNode(text = "加载中", left = 10, top = 700, right = 200, bottom = 760))
    )

    private val engine = RuleEngine(
        listOf(
            AppRule(
                pkg = "com.zzw.october",
                windowMs = 8000,
                activityHints = listOf("LaunchActivity"),
                evidence = listOf(AppRule.EVIDENCE_ACTIVITY_HINT),
                strategies = listOf(
                    Strategy(Strategy.NODE_TEXT),
                    Strategy(Strategy.TAP_RATIO, x = 0.126f, y = 0.062f),   // 左上药丸
                    Strategy(Strategy.TAP_RATIO, x = 0.906f, y = 0.062f)    // 右上圆钮
                ),
                abortIf = emptyList()
            )
        )
    )

    @Test
    fun `两个盲点按点击次数交替`() {
        val first = engine.evaluate(snapshot(0)) as Decision.TapPoint
        assertEquals("第 1 次应点左侧", 153, first.x)
        assertEquals(163, first.y)

        val second = engine.evaluate(snapshot(1)) as Decision.TapPoint
        assertEquals("第 2 次应点右侧", (1216 * 0.906f).toInt(), second.x)
        assertEquals((2640 * 0.062f).toInt(), second.y)

        // 第 3 次回到第一个点（3 次点击预算正好左右左）
        val third = engine.evaluate(snapshot(2)) as Decision.TapPoint
        assertEquals(153, third.x)
    }
}
