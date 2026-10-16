package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选筛选的回归测试。
 *
 * 这里的坐标和控件信息**全部来自真机日志与抓屏**
 * （OPPO Reno 15，1216x2640，Android 16 / ColorOS 16）。
 *
 * 特别重要的一组：B站 的跳过按钮在最底部（92%），
 * 我曾经根据"正确点击都在顶部"改成只认顶部，结果误杀了它。
 * 下面这些断言锁住的就是这个教训 —— 位置不是主要判据，倒计时证据才是。
 */
class SkipTargetRankerTest {

    private val screenW = 1216
    private val screenH = 2640

    /** 用中心点构造一个尺寸正常的按钮类节点 */
    private fun node(
        label: String,
        centerX: Int,
        centerY: Int,
        width: Int = 272,
        height: Int = 136,
        className: String = "android.widget.TextView",
        viewId: String = "",
        clickable: Boolean = true
    ) = UiNode(
        text = label,
        viewId = viewId,
        className = className,
        left = centerX - width / 2,
        top = centerY - height / 2,
        right = centerX + width / 2,
        bottom = centerY + height / 2,
        clickable = clickable
    )

    // ---------------------------------------------------------- 真机：B站

    /**
     * 真机原样复制的一条节点：
     *   「跳过 5」TextView#count_down@(888,2363,1160,2499)[可点]
     * 中心 (1024,2431) 是屏幕 84%,92% —— 在底部，但它是**真的跳过按钮**。
     */
    private fun bilibiliRealSkipButton() = UiNode(
        text = "跳过 5",
        viewId = "tv.danmaku.bili:id/count_down",
        className = "android.widget.TextView",
        left = 888, top = 2363, right = 1160, bottom = 2499,
        clickable = true
    )

    @Test
    fun `B站位于屏幕底部的真跳过按钮必须被接受`() {
        val picked = SkipTargetRanker.pick(listOf(bilibiliRealSkipButton()), screenW, screenH)
        assertNotNull("这是 B站 真正的跳过按钮（view id = count_down），绝不能被拒绝", picked)
        assertEquals(2431, picked!!.centerY)
    }

    @Test
    fun `位置不是主要判据_倒计时证据才是`() {
        val bili = bilibiliRealSkipButton()
        assertTrue("带倒计时的按钮应被识别为强证据", SkipTargetRanker.hasStrongEvidence(bili))

        // 同样是底部位置，但没有倒计时文案、也没有 count_down/skip 这类 id
        val fake = node("跳过", centerX = 1024, centerY = 2431, viewId = "com.x:id/tab_me")
        // 它落在底部边缘区内，仍会被接受 —— 但排序上必须远低于真按钮
        val ranked = SkipTargetRanker.rank(listOf(fake, bili), screenW, screenH)
        assertEquals("跳过 5", ranked.first().label())
    }

    // ---------------------------------------------------------- 真机：贴吧

    @Test
    fun `贴吧的两个真机候选_应该选中右上角那个并否决中间那个`() {
        // 抓屏确认：真按钮是右上角「跳过 05」，中心约 (1033,132)
        // 另一个「广告跳过」@(1022,1965) 在 74% 高度处，位于广告内容区，不是按钮
        val wrong = node("广告跳过", centerX = 1022, centerY = 1965)
        val right = node("跳过 05", centerX = 1037, centerY = 122)

        val picked = SkipTargetRanker.pick(listOf(wrong, right), screenW, screenH)
        assertNotNull(picked)
        assertEquals("跳过 05", picked!!.label())

        val rejected = SkipTargetRanker.rejected(listOf(wrong, right), screenW, screenH)
        assertEquals(1, rejected.size)
        assertEquals("广告跳过", rejected[0].label())
    }

    @Test
    fun `广告正中的候选必须被拒绝_点它等于给广告打开落地页`() {
        // 真机：B站 出现过「广告跳过」@(305,1233)，屏幕 25%,47%，正在广告可点区域内
        val adCenter = node("广告跳过", centerX = 305, centerY = 1233)
        assertNull(SkipTargetRanker.pick(listOf(adCenter), screenW, screenH))
    }

    @Test
    fun `没有倒计时证据时中间区域一律不接受_但顶部边缘可以`() {
        val middleNoCountdown = node("跳过", centerX = 600, centerY = 1300, viewId = "com.x:id/a")
        assertNull(SkipTargetRanker.pick(listOf(middleNoCountdown), screenW, screenH))

        val topNoCountdown = node("跳过", centerX = 1065, centerY = 273, viewId = "com.x:id/b")
        assertNotNull(SkipTargetRanker.pick(listOf(topNoCountdown), screenW, screenH))
    }

    @Test
    fun `视图 id 含 skip 或 countdown 也算强证据`() {
        val byId = node("跳过", centerX = 600, centerY = 1300, viewId = "com.x:id/skip_btn")
        assertTrue(SkipTargetRanker.hasStrongEvidence(byId))
        assertNotNull(SkipTargetRanker.pick(listOf(byId), screenW, screenH))
    }

    // ---------------------------------------------------------- 通用安全规则

    @Test
    fun `全屏容器冒充跳过按钮时被拒绝`() {
        // 广告 SDK 常给整块广告容器挂上含"跳过"的描述，
        // 这种节点面积接近整屏，点它的中心就是点广告本身
        val container = UiNode(
            contentDescription = "广告跳过",
            className = "android.widget.FrameLayout",
            left = 0, top = 0, right = screenW, bottom = screenH
        )
        assertNull(SkipTargetRanker.pick(listOf(container), screenW, screenH))
    }

    @Test
    fun `容器与真按钮同时存在时选真按钮`() {
        val container = UiNode(
            contentDescription = "广告跳过",
            className = "android.widget.FrameLayout",
            left = 0, top = 0, right = screenW, bottom = screenH
        )
        val button = node("跳过 3", centerX = 1069, centerY = 189)

        val picked = SkipTargetRanker.pick(listOf(container, button), screenW, screenH)
        assertEquals("跳过 3", picked?.label())
    }

    @Test
    fun `真机上所有正确的点击都落在可接受范围内`() {
        // 这四个坐标全部来自日志中"点了没问题"的记录，用来防止把规则收得过紧
        val 微博 = node("跳过", centerX = 1065, centerY = 273)
        val 知乎 = node("跳过4", centerX = 1069, centerY = 189)
        val 贴吧A = node("跳过 05", centerX = 1037, centerY = 122)
        val 贴吧B = node("跳过", centerX = 1016, centerY = 190)

        listOf(微博, 知乎, 贴吧A, 贴吧B).forEach { candidate ->
            val picked = SkipTargetRanker.pick(listOf(candidate), screenW, screenH)
            assertNotNull("这个坐标在真机上点对了，不该被拒绝：${candidate.describe()}", picked)
        }
    }

    @Test
    fun `规则可以额外收紧到只认顶部`() {
        val bottom = bilibiliRealSkipButton()
        assertNotNull(SkipTargetRanker.pick(listOf(bottom), screenW, screenH))
        assertNull(
            "显式设置 region=top 时，底部的候选应被否决",
            SkipTargetRanker.pick(listOf(bottom), screenW, screenH, SkipTargetRanker.REGION_TOP)
        )
    }

    @Test
    fun `零尺寸节点被拒绝`() {
        val zero = UiNode(text = "跳过", left = 100, top = 100, right = 100, bottom = 100)
        assertNull(SkipTargetRanker.pick(listOf(zero), screenW, screenH))
    }

    @Test
    fun `屏幕尺寸未知时不返回任何候选`() {
        val button = node("跳过 3", centerX = 1069, centerY = 189)
        assertNull(SkipTargetRanker.pick(listOf(button), 0, 0))
        assertTrue(SkipTargetRanker.rank(listOf(button), 0, 0).isEmpty())
    }

    @Test
    fun `没有候选时返回空`() {
        assertNull(SkipTargetRanker.pick(emptyList(), screenW, screenH))
        assertFalse(SkipTargetRanker.hasStrongEvidence(UiNode()))
    }

    @Test
    fun `排序把右上角排在前面`() {
        val topLeft = node("跳过 3", centerX = 100, centerY = 150)
        val topRight = node("跳过 3", centerX = 1100, centerY = 150)

        val ranked = SkipTargetRanker.rank(listOf(topLeft, topRight), screenW, screenH)
        assertEquals(2, ranked.size)
        assertEquals(1100, ranked[0].centerX)
    }

    @Test
    fun `精确等于特征词的候选优先于仅仅包含它的_爱奇艺真机案例`() {
        // 真机：爱奇艺 同时存在两个含"关闭"的节点
        //   真按钮：标签就是「关闭」            @(963,100,1061,166)
        //   残留节点：「关闭广告动画」          @(824,498,1118,564)  ← 点它是在广告画面上白点
        fun at(label: String, l: Int, t: Int, r: Int, b: Int) = UiNode(
            text = label,
            viewId = "com.qiyi.video:id/unused_res_a",
            className = "android.widget.TextView",
            left = l, top = t, right = r, bottom = b
        )
        val real = at("关闭", 963, 100, 1061, 166)
        val leftover = at("关闭广告动画", 824, 498, 1118, 564)
        val patterns = listOf("关闭", "跳过", "skip")

        val picked = SkipTargetRanker.pick(
            candidates = listOf(leftover, real),
            screenWidth = screenW,
            screenHeight = screenH,
            exactLabels = patterns
        )
        assertEquals("精确匹配的「关闭」应该胜出", "关闭", picked?.label())

        // 但权重不能高到压过倒计时证据：志愿汇 的假候选精确匹配「跳过」，
        // 而真候选是「跳过 5」（倒计时）—— 必须仍然是真候选胜出
        val fake = at("跳过", 0, 0, 210, 88)
        val realWithCountdown = at("跳过 5", 963, 98, 1174, 202)
        val picked2 = SkipTargetRanker.pick(
            candidates = listOf(fake, realWithCountdown),
            screenWidth = screenW,
            screenHeight = screenH,
            exactLabels = listOf("跳过")
        )
        assertEquals("倒计时证据必须能压过精确匹配", "跳过 5", picked2?.label())
    }

    /**
     * 真机节点的证据分级。
     *
     * 这个测试的用途是**评估一个方案的代价**，而不是验证功能：
     * 曾经考虑过"窗口前半段只接受强候选、后半段才允许弱候选"，
     * 用来避免 志愿汇 在假候选上白点三次。但把真机日志里每个被点中的节点
     * 代入分级后可以算出：这个方案会拖慢 6 个已经能用的应用，而且**救不了 志愿汇**。
     */
    @Test
    fun `真机节点的证据分级_用来核算延迟弱候选方案的代价`() {
        fun n(label: String, id: String) = UiNode(
            text = label,
            viewId = id,
            className = "android.widget.TextView",
            left = 900, top = 100, right = 1100, bottom = 200,
            clickable = true
        )

        // ---- 强证据：倒计时文案，或 view id 含 skip / countdown / jump ----
        assertTrue("贴吧", SkipTargetRanker.hasStrongEvidence(n("跳过 05", "")))
        assertTrue("知乎", SkipTargetRanker.hasStrongEvidence(n("跳过4", "com.zhihu.android:id/btn_skip")))
        assertTrue("B站", SkipTargetRanker.hasStrongEvidence(n("跳过 5", "tv.danmaku.bili:id/count_down")))
        assertTrue("菜鸟", SkipTargetRanker.hasStrongEvidence(n("跳过 3", "com.cainiao.wireless:id/homesplash_close_fullscreen")))
        assertTrue("网易云音乐", SkipTargetRanker.hasStrongEvidence(n("跳过", "com.netease.cloudmusic:id/skipBtn")))
        assertTrue("易校园", SkipTargetRanker.hasStrongEvidence(n("5s | 跳过", "com.anythink:id/myoffer_splash_skip")))
        assertTrue("学习通", SkipTargetRanker.hasStrongEvidence(n("跳过3s", "com.chaoxing.mobile:id/btn_jump")))

        // ---- 弱证据：只有"跳过"两个字，没有倒计时也没有 id 线索 ----
        // 这些应用目前正是靠"屏幕边缘位置"这条规则被接纳的。
        // 一旦要求"窗口前半段只接受强候选"，它们全都会从 0.1~1.5 秒退到 2.5 秒以后。
        assertFalse("微博", SkipTargetRanker.hasStrongEvidence(n("跳过", "")))
        assertFalse("京东", SkipTargetRanker.hasStrongEvidence(n("跳过", "com.jingdong.app.mall:id/b22")))
        assertFalse("腾讯视频", SkipTargetRanker.hasStrongEvidence(n("跳过", "")))
        assertFalse("掌上无畏契约", SkipTargetRanker.hasStrongEvidence(n("跳过", "")))
        assertFalse("KOOK", SkipTargetRanker.hasStrongEvidence(n("跳过", "")))
        assertFalse("云运动", SkipTargetRanker.hasStrongEvidence(n("skip_button", "")))

        // ---- 决定性的一条 ----
        // 志愿汇 那个"点了没反应"的假候选，view id 是 tvSkip，小写后含 "skip"，
        // 因此被判为**强证据**。"延迟弱候选"对它完全无效 ——
        // 那个方案是"6 个应用变慢"换"0 个应用变好"。
        assertTrue(
            "tvSkip 含 skip，会被当成强证据，所以延迟弱候选根本救不了志愿汇",
            SkipTargetRanker.hasStrongEvidence(n("跳过", "com.zzw.october:id/tvSkip"))
        )
    }
}
