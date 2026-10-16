package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计的纯逻辑测试。
 *
 * 重点在"别把没有样本当成 0%" —— 用 0 次尝试算出的 0% 成功率会让人以为某个应用完全不能用，
 * 而实际上只是还没遇到过广告。这种误判会直接影响 M1→M2 的取舍。
 */
class StatsReportTest {

    private val nameOf: (String) -> String = {
        when (it) {
            "com.sina.weibo" -> "微博"
            "com.baidu.tieba" -> "百度贴吧"
            else -> it
        }
    }

    @Test
    fun `没有已决样本时成功率是未知而不是 0 百分比`() {
        assertNull(AppStat().successRate)
        assertNull(AppStat(launches = 5).successRate)
        assertNull("只点击过但校验还没回来，也不能算 0%", AppStat(clicks = 3).successRate)
    }

    @Test
    fun `成功率以遇到广告的启动次数为分母_向下取整`() {
        assertEquals(100, AppStat(verifiedOk = 3, adsSeen = 3).successRate)
        assertEquals(66, AppStat(verifiedOk = 2, verifiedFail = 1, adsSeen = 3).successRate)
        assertEquals(0, AppStat(verifiedFail = 2, adsSeen = 2).successRate)
    }

    @Test
    fun `启动次数多但广告少时_不该被启动数拉低`() {
        // 真机场景：B站 一天切到前台 100 次，只弹了 8 次广告、成功 7 次。
        // 若用启动次数当分母会算成 7%，用点击次数会算成 7/8 但含义混乱；
        // 正确口径是 7/8 = 87%（遇到广告时跳掉的比例）
        val bili = AppStat(launches = 100, clicks = 12, verifiedOk = 7, verifiedFail = 1, adsSeen = 8)
        assertEquals(87, bili.successRate)
        // 从没遇到广告的应用显示"-"（未知），而不是 0%
        assertNull(AppStat(launches = 50, clicks = 0).successRate)
    }

    @Test
    fun `空统计给出提示而不是空表`() {
        val text = StatsReport.build(emptyMap(), nameOf, 0)
        assertTrue("应给出可操作提示，实际=$text", text.contains("还没有统计数据"))
    }

    @Test
    fun `报表按点击次数降序_并带合计行`() {
        val stats = mapOf(
            "com.sina.weibo" to AppStat(launches = 2, clicks = 2, verifiedOk = 2, adsSeen = 2),
            "com.baidu.tieba" to
                AppStat(launches = 5, clicks = 6, verifiedOk = 4, verifiedFail = 2, adsSeen = 6)
        )
        val text = StatsReport.build(stats, nameOf, 12)
        val lines = text.lines()

        assertTrue("应含运行时长", lines[0].contains("12 分钟"))
        // 贴吧点得多，应排在微博前面
        val tiebaAt = lines.indexOfFirst { it.startsWith("百度贴吧") }
        val weiboAt = lines.indexOfFirst { it.startsWith("微博") }
        assertTrue("点击多的应排在前面：tieba=$tiebaAt weibo=$weiboAt", tiebaAt in 1 until weiboAt)

        assertTrue("贴吧成功率应显示 66%", lines[tiebaAt].trimEnd().endsWith("66%"))
        assertTrue("微博成功率应显示 100%", lines[weiboAt].trimEnd().endsWith("100%"))

        val total = lines.first { it.startsWith("合计") }
        assertTrue("合计成功率 = 6/8 = 75%，实际=$total", total.endsWith("75%"))
    }

    @Test
    fun `只有启动没有点击的应用也要出现在表里`() {
        val stats = mapOf("com.sina.weibo" to AppStat(launches = 3))
        val text = StatsReport.build(stats, nameOf, 1)
        assertTrue("没点过也要能看到它被观察到过", text.contains("微博"))
        assertTrue("成功率未知时显示短横线", text.lines().any { it.startsWith("微博") && it.trimEnd().endsWith("-") })
    }

    @Test
    fun `编解码往返一致`() {
        val original = mapOf(
            "com.sina.weibo" to AppStat(2, 3, 2, 1, 0),
            "cn.kaiheila" to AppStat(1, 1, 1, 0, 0)
        )
        val decoded = StatCodec.decode(StatCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `坏行被丢弃但不影响其它行`() {
        val raw = """
            com.sina.weibo|2|3|2|1|0
            这一行是垃圾
            |1|1|1|0|0
            cn.kaiheila|1|1|1|0|0
            com.x|a|b|c|d|e
        """.trimIndent()

        val decoded = StatCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals(AppStat(2, 3, 2, 1, 0), decoded["com.sina.weibo"])
        assertEquals(AppStat(1, 1, 1, 0, 0), decoded["cn.kaiheila"])
    }

    @Test
    fun `空字符串解码为空表`() {
        assertTrue(StatCodec.decode("").isEmpty())
    }

    @Test
    fun `启动计数要对包名抖动去抖_真机贴吧七次启动的教训`() {
        val recorder = LaunchRecorder(debounceMs = 10_000L)

        // 正常的一次启动
        assertTrue(recorder.shouldRecord("com.baidu.tieba", 1_000L))
        // 系统弹窗/悬浮窗把包名切走又切回来（真机上这被算成了好几次启动）
        assertTrue(recorder.shouldRecord("com.android.systemui", 1_500L))
        assertTrue(recorder.shouldRecord("com.baidu.tieba", 1_600L) == false)
        assertTrue(recorder.shouldRecord("com.baidu.tieba", 5_000L) == false)
        // 超过去抖窗口才算新的一次启动
        assertTrue(recorder.shouldRecord("com.baidu.tieba", 11_500L))

        // 不同应用各算各的，互不影响
        assertTrue(recorder.shouldRecord("com.qiyi.video", 1_700L))
    }

    @Test
    fun `去抖窗口可以重置`() {
        val recorder = LaunchRecorder(debounceMs = 10_000L)
        assertTrue(recorder.shouldRecord("com.a", 0L))
        assertFalse(recorder.shouldRecord("com.a", 100L))
        recorder.reset()
        assertTrue(recorder.shouldRecord("com.a", 200L))
    }

    @Test
    fun `被去抖丢掉的启动_不许再记一次广告`() {
        // 真机上的缺陷：微博 启动=1 有广告=2 —— 广告计数比启动计数还多。
        // 成因是"探测层按包名变化判新启动"和"统计层按时间判去抖"两个判断不一致：
        // A →（系统弹窗/输入法抢前台）→ A 时，前者成立、后者被丢掉，
        // 于是 adsSeen 多记一次，成功率的分母虚高。
        val recorder = LaunchRecorder(debounceMs = 10_000L)
        val pkg = "com.sina.weibo"

        // 第 1 次启动：被计数，这次看到的广告要进分母
        assertEquals(true, recorder.shouldRecord(pkg, 1_000L))
        assertEquals(true, recorder.acceptsAd(pkg, 1_200L))

        // 700ms 后弹窗抢走前台又还回来（包名变化 → 探测层认为"新启动"）
        // 统计层必须拒绝：这一轮不是新的一次启动，广告也不许进分母
        assertEquals(false, recorder.shouldRecord(pkg, 1_700L))
        assertEquals(false, recorder.acceptsAd(pkg, 1_700L))

        // 关键回归（早期实现就是在这里错的）：被计数的那次启动之后，**同一次观察里**
        // 广告门禁必须仍然开着；而紧接着那一轮被去抖丢掉后必须关上。
        // 早期实现拿 lastAt 里的时间戳来比，会被 shouldRecord 顺手写进去的 now 冲掉 ——
        // now - lastAt 恰好等于 0，永远落在窗口内，于是 5 次观察全判成"可以记广告"。
        assertEquals(true, recorder.shouldRecord(pkg, 12_000L))
        assertEquals(true, recorder.acceptsAd(pkg, 12_000L))
        assertEquals(false, recorder.shouldRecord(pkg, 12_300L))
        assertEquals(false, recorder.acceptsAd(pkg, 12_300L))

        // 从没启动过的包：不能凭空给分母
        assertEquals(false, recorder.acceptsAd("com.qiyi.video", 12_300L))
    }

    @Test
    fun `广告计数不该超过启动计数_去抖场景回归`() {
        val recorder = LaunchRecorder(debounceMs = 10_000L)
        val pkg = "com.sina.weibo"
        val timeline = longArrayOf(1_000L, 1_700L, 2_400L, 12_000L, 12_300L)

        var launches = 0
        var adsSeen = 0
        for (t in timeline) {
            if (recorder.shouldRecord(pkg, t)) launches++
            // 每一次"启动观察"里都看到过一次广告
            if (recorder.acceptsAd(pkg, t)) adsSeen++
        }

        assertEquals(2, launches)
        assertEquals(2, adsSeen)
    }
}
