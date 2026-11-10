package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盲点校验判定（[BlindVerifier]）的单元测试。
 *
 * 这些用例直接来自真机证据（志愿汇 自绘开屏广告，2026-11-10）：
 * 广告在屏时无障碍树读到的是底层主页面（105 个节点、0 个含"跳过"），
 * 所以"跳过字样还在不在"不能用作判据；能用的只有窗口层面的事实——
 * **点击时还在的目标应用窗口是否消失**。
 */
class BlindVerifierTest {

    private val target = "com.zzw.october"

    private fun window(
        id: Int,
        pkg: String? = target,
        type: Int = 1
    ) = WindowFingerprint(
        id = id,
        type = type,
        layer = 0,
        active = true,
        focused = true,
        rootPackage = pkg,
        left = 0,
        top = 0,
        right = 1216,
        bottom = 2640
    )

    private fun obs(
        pkg: String? = target,
        windows: List<WindowFingerprint>? = listOf(window(11))
    ) = BlindObservation(activePackage = pkg, windows = windows)

    // ------------------------------------------------------------ 成功：点击时的窗口消失了

    @Test
    fun `点击时的目标应用窗口在校验时消失_判定成功`() {
        // 真机日志（2026-11-10 21:16）：点击前窗口 id=7797，校验时 7798
        // （启动 Activity 的窗口被 MainActivity 的窗口换掉）——这是本判据的真机依据。
        val v = BlindVerifier.judge(target, obs(windows = listOf(window(7797))), obs(windows = listOf(window(7798))))
        assertTrue("应该判成功，实际=$v", v is BlindVerifier.Verdict.Success)
    }

    @Test
    fun `广告窗口消失_窗口数量减少_判定成功`() {
        // 广告若是独立窗口（根包名读不到、类型是普通应用窗口）：点击前 [广告, 主窗口]，关掉后只剩 [主窗口]
        val v = BlindVerifier.judge(
            target,
            obs(windows = listOf(window(11), window(12, pkg = null))),
            obs(windows = listOf(window(11)))
        )
        assertTrue("应该判成功，实际=$v", v is BlindVerifier.Verdict.Success)
    }

    @Test
    fun `前台包名读不到但窗口消失了_判定成功`() {
        // 包名这条信号读不到不影响窗口判据
        val v = BlindVerifier.judge(
            target,
            obs(pkg = target, windows = listOf(window(7618))),
            obs(pkg = null, windows = listOf(window(7619)))
        )
        assertTrue("应该判成功，实际=$v", v is BlindVerifier.Verdict.Success)
    }

    // ------------------------------------------------------------ 未生效：点击时的窗口都还在

    @Test
    fun `点击时的窗口都还在_判定未生效`() {
        val v = BlindVerifier.judge(
            target,
            obs(windows = listOf(window(11))),
            obs(windows = listOf(window(11)))
        )
        assertTrue("应该判未生效，实际=$v", v is BlindVerifier.Verdict.Unchanged)
    }

    @Test
    fun `只多出新窗口但旧窗口没消失_判定未生效`() {
        // 新出现的窗口可能是任何弹窗/新 Activity，不能当"广告关掉"的证据
        val v = BlindVerifier.judge(
            target,
            obs(windows = listOf(window(11))),
            obs(windows = listOf(window(11), window(13)))
        )
        assertTrue("只出现新窗口不能判成功，实际=$v", v is BlindVerifier.Verdict.Unchanged)
    }

    @Test
    fun `前台包名变化不再是成功判据_窗口没消失则未生效`() {
        // 真机抓到过的假成功：0ms 盲点后 WINDOW_STATE_CHANGED 才到，currentActivity 从旧值
        // 变成新值；包名同理会有"可读性/事件滞后"变化，不能当证据。
        val v = BlindVerifier.judge(
            target,
            obs(pkg = "上次残留/读不到", windows = listOf(window(11))),
            obs(pkg = target, windows = listOf(window(11)))
        )
        assertTrue("包名变化不能判成功，实际=$v", v is BlindVerifier.Verdict.Unchanged)
    }

    @Test
    fun `系统窗口进出不算广告消失_判定未生效`() {
        // 真机日志里成功那轮恰好同时出现了状态栏窗口；它不是广告，不能当成功依据
        val v = BlindVerifier.judge(
            target,
            obs(windows = listOf(window(11))),
            obs(
                windows = listOf(
                    window(900, pkg = "com.android.systemui", type = 3),
                    window(11)
                )
            )
        )
        assertTrue("系统窗口变化不能判成功，实际=$v", v is BlindVerifier.Verdict.Unchanged)
    }

    @Test
    fun `别的应用的窗口出现不算成功_判定未生效`() {
        val v = BlindVerifier.judge(
            target,
            obs(windows = listOf(window(11))),
            obs(windows = listOf(window(11), window(77, pkg = "com.example.other")))
        )
        assertTrue("应该判未生效，实际=$v", v is BlindVerifier.Verdict.Unchanged)
    }

    // ------------------------------------------------------------ 无法判断

    @Test
    fun `校验时包名和窗口列表都读不到_无法判断`() {
        val v = BlindVerifier.judge(target, obs(), BlindObservation(null, null))
        assertTrue("应该无法判断，实际=$v", v is BlindVerifier.Verdict.Unknown)
    }

    @Test
    fun `点击时两个信号都读不到_没有基线_无法判断`() {
        val v = BlindVerifier.judge(target, BlindObservation(null, null), obs())
        assertTrue("应该无法判断，实际=$v", v is BlindVerifier.Verdict.Unknown)
    }

    @Test
    fun `窗口列表读不到_无法判断`() {
        // 包名读到了且没变，但窗口列表读不到 → 不能确认"窗口没消失"，不记结论
        val v = BlindVerifier.judge(
            target,
            obs(windows = null),
            obs(windows = null)
        )
        assertTrue("应该无法判断，实际=$v", v is BlindVerifier.Verdict.Unknown)
    }

    @Test
    fun `窗口列表是空列表_无法判断`() {
        // 空列表不是"读到了、只是没有窗口"，而是没有比较基线
        val v = BlindVerifier.judge(
            target,
            obs(windows = emptyList()),
            obs(windows = emptyList())
        )
        assertTrue("应该无法判断，实际=$v", v is BlindVerifier.Verdict.Unknown)
    }

    @Test
    fun `点击时只有系统窗口_没有可比较基线_无法判断`() {
        val v = BlindVerifier.judge(
            target,
            obs(windows = listOf(window(900, pkg = "com.android.systemui", type = 3))),
            obs(windows = listOf(window(900, pkg = "com.android.systemui", type = 3)))
        )
        assertTrue("应该无法判断，实际=$v", v is BlindVerifier.Verdict.Unknown)
    }

    // ------------------------------------------------------------ 窗口指纹过滤

    @Test
    fun `窗口指纹只取目标应用与根包名读不到的普通应用窗口`() {
        val keys = BlindVerifier.relevantWindowKeys(
            listOf(
                window(11),
                window(900, pkg = "com.android.systemui", type = 3),
                window(12, pkg = null, type = 1),
                window(13, pkg = null, type = 2),
                window(88, pkg = "com.example.other")
            ),
            target
        )
        assertEquals(listOf("11/1", "12/1"), keys)
    }
}
