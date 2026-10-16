package io.github.adskip.engine

/**
 * 跳过按钮候选的筛选与排序。
 *
 * ## 为什么不能用"位置"当主要依据
 *
 * 第一版取"遍历到的第一个文本命中节点"，真机上点错了地方。
 * 第二版改成"只认屏幕顶部 25%"，结果**误杀了 B站**：
 *
 *   真机日志（OPPO Reno 15，1216x2640，Android 16 / ColorOS 16）：
 *     「跳过 5」TextView#count_down@(888,2363,1160,2499)[可点]
 *   中心 (1024,2431) = 屏幕 84%,**92%** —— 它的跳过按钮真的在最底部。
 *
 * 而同一批数据里，贴吧/微博/知乎 的跳过按钮都在右上角（4.6%~10.3%）。
 * 也就是说：**不同 App 的跳过按钮位置本来就不同，位置不能作为主要判据。**
 *
 * ## 真正的主判据：倒计时文本
 *
 * 开屏广告的跳过按钮几乎总是带倒计时（"跳过 5"、"跳过 05"、"跳过4"），
 * 而"长得像跳过按钮但其实不是"的节点通常不带。实测数据全部符合：
 *
 *   | 节点 | 是否倒计时 | 判定 |
 *   |---|---|---|
 *   | B站「跳过 5」#count_down @92% | 是 | ✅ 真按钮 |
 *   | 贴吧「跳过 05」@4.6% | 是 | ✅ 真按钮 |
 *   | 贴吧「广告跳过」@74% | 否 | ⛔ 不是按钮 |
 *   | B站「广告跳过」@47% | 否 | ⛔ 广告可点区域正中 |
 *
 * 因此策略是：**有倒计时证据的候选接受任意位置；没有的只接受屏幕边缘**。
 * 中间区域是内容区，那里出现"跳过"文案基本都不是按钮，点它等于点广告。
 *
 * 另外无条件拒绝"容器冒充按钮"：广告 SDK 常给整块广告容器挂上含"跳过"的描述，
 * 这种节点面积接近整屏，点它的中心就是点广告本身。
 */
object SkipTargetRanker {

    /** 接受任意位置（默认） */
    const val REGION_FULL = "full"

    /** 额外限制：只接受顶部区域。给"底部存在危险元素"的个别 App 用 */
    const val REGION_TOP = "top"

    val KNOWN_REGIONS = setOf(REGION_FULL, REGION_TOP)

    /** 面积超过屏幕这个比例的候选，视为"容器冒充跳过按钮"直接否决 */
    const val MAX_AREA_RATIO = 0.40f

    /** 顶部区域的边界（用于无倒计时证据时的边缘判定） */
    const val TOP_RATIO = 0.25f

    /** 右上角判定里的右侧比例 */
    const val RIGHT_RATIO = 0.60f

    /**
     * 底部边缘的起点。
     * B站 的真按钮在 92% 处，所以这里必须放到 88% 以下才可能接纳它 ——
     * 这也解释了为什么"禁止底部"的思路是错的。
     */
    const val BOTTOM_EDGE_RATIO = 0.80f

    /** 视图 id 里出现这些片段，视为"这是跳过/倒计时控件"的强证据 */
    private val STRONG_ID_HINTS = listOf("count_down", "countdown", "skip", "jump")

    private val STRONG_LABEL_BONUS = 200

    /**
     * 标签**精确等于**某个特征词时的加分。
     *
     * 为什么需要它：真机实测 爱奇艺 同时存在两个含"关闭"的节点 ——
     * 真按钮标签就是「关闭」，而另一个是「关闭广告动画」（一个坐标已失效的残留节点，
     * 点它等于在广告画面上白点一下，甚至有打开落地页的风险）。
     * 精确等于特征词是比"包含"强得多的信号。
     *
     * 权重刻意设为 80：要低于倒计时的 200，这样 志愿汇 那种
     * "假候选精确匹配、真候选带倒计时"的情况仍然是真候选胜出。
     */
    const val EXACT_LABEL_BONUS = 80

    /**
     * 从候选里挑出最可能是"跳过按钮"的那个。
     *
     * @param exactLabels 当前生效的特征词，用于给"精确等于"的候选加分
     * @return 最优候选；null 表示所有候选都被安全规则否决（此时应当什么都不做）
     */
    fun pick(
        candidates: List<UiNode>,
        screenWidth: Int,
        screenHeight: Int,
        region: String = REGION_FULL,
        exactLabels: List<String> = emptyList()
    ): UiNode? = rank(candidates, screenWidth, screenHeight, region, exactLabels).firstOrNull()

    /** 排序结果，按得分从高到低。被否决的候选不会出现在结果里 */
    fun rank(
        candidates: List<UiNode>,
        screenWidth: Int,
        screenHeight: Int,
        region: String = REGION_FULL,
        exactLabels: List<String> = emptyList()
    ): List<UiNode> {
        if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
        return candidates
            .filter { isPlausible(it, screenWidth, screenHeight, region) }
            .sortedByDescending { score(it, screenWidth, screenHeight, exactLabels) }
    }

    /** 被安全规则否决的候选，仅用于日志诊断 —— 知道"为什么没点"和"点了"同样重要 */
    fun rejected(
        candidates: List<UiNode>,
        screenWidth: Int,
        screenHeight: Int,
        region: String = REGION_FULL
    ): List<UiNode> =
        candidates.filterNot { isPlausible(it, screenWidth, screenHeight, region) }

    /**
     * 是否有"这是跳过控件"的强证据。
     *
     * 倒计时文案是最强的：真机上两个被确认的正确按钮都带倒计时，
     * 而两个误点候选都不带。
     *
     * 注意必须要求**秒数大于 0**：SkipTextMatcher.countdownSeconds("跳过") 返回的是 0
     * （表示"是跳过文案但没有倒计时"），这里如果只判断 `!= null`，
     * 光秃秃的「跳过」就会被当成有倒计时证据，整个策略会被架空。
     * 这个 bug 是被单元测试抓出来的。
     */
    fun hasStrongEvidence(node: UiNode): Boolean {
        val seconds = SkipTextMatcher.countdownSeconds(node.label())
        if (seconds != null && seconds > 0) return true
        val id = node.viewId.lowercase()
        return STRONG_ID_HINTS.any { id.contains(it) }
    }

    private fun isPlausible(
        node: UiNode,
        screenWidth: Int,
        screenHeight: Int,
        region: String
    ): Boolean {
        // 零尺寸节点没有可点中心
        if (node.width <= 0 || node.height <= 0) return false

        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        if (screenArea <= 0L) return false

        // 面积过大 = 容器冒充跳过按钮。点它的中心等于点广告本身
        if (node.area.toFloat() / screenArea > MAX_AREA_RATIO) return false

        val cy = node.centerY.toFloat() / screenHeight

        // 规则可以额外收紧到只认顶部
        if (region == REGION_TOP && cy > TOP_RATIO) return false

        // 有倒计时/控件 id 之类的强证据：任意位置都接受（B站 的按钮就在 92% 处）
        if (hasStrongEvidence(node)) return true

        // 没有强证据：只接受屏幕边缘。中间区域是内容区，
        // 那里出现"跳过"文案基本都不是按钮，点它等于点广告
        return cy <= TOP_RATIO || cy >= BOTTOM_EDGE_RATIO
    }

    private fun score(
        node: UiNode,
        screenWidth: Int,
        screenHeight: Int,
        exactLabels: List<String> = emptyList()
    ): Int {
        val cx = node.centerX.toFloat() / screenWidth
        val cy = node.centerY.toFloat() / screenHeight

        var score = 0

        // 标签精确等于特征词：比"包含"强得多的信号
        if (exactLabels.any { it.trim().equals(node.label().trim(), ignoreCase = true) }) {
            score += EXACT_LABEL_BONUS
        }

        // 倒计时类证据权重最大，它是区分"真按钮"和"长得像按钮"的关键
        if (hasStrongEvidence(node)) score += STRONG_LABEL_BONUS

        val inTop = cy <= TOP_RATIO
        val inRight = cx >= RIGHT_RATIO
        if (inTop) score += 40
        if (inRight) score += 20
        // 右上角是跳过按钮最常见的位置，组合命中权重更高
        if (inTop && inRight) score += 60

        if (node.clickable) score += 15

        // 小而精的控件更像按钮；用面积百分比做惩罚
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        if (screenArea > 0L) {
            score -= ((node.area * 100L) / screenArea).toInt()
        }

        return score
    }
}
