package io.github.adskip.engine

import com.google.gson.annotations.SerializedName

/**
 * 规则库根节点。
 *
 * 规则库是整个项目真正的资产：代码只写一次，规则要跟着几百个 App 的版本长期维护。
 * 因此这里刻意保持"人能看懂、能手写、能 review"。
 */
data class RuleSet(
    val version: Int = 1,
    val updatedAt: String? = null,
    val rules: List<AppRule> = emptyList()
)

/**
 * 单个 App 的跳过规则。
 *
 * @param windowMs 该 App 进入前台后的"允许介入时间窗"。超出这个时间一律不再碰屏幕，
 *                 这是最重要的安全阀：开屏广告只可能出现在启动后的最初几秒。
 */
data class AppRule(
    val pkg: String,
    val name: String? = null,
    val enabled: Boolean = true,
    val windowMs: Long = 3000,
    /**
     * Activity 名包含其中任一关键字时，视为"疑似开屏/广告页"。
     * 很多 App 的开屏广告和主界面是同一个 Activity，因此这个字段是可选的旁证，不是硬条件。
     */
    val activityHints: List<String> = emptyList(),
    /**
     * 允许解锁"盲点"类策略的旁证类型。只要命中其中任意一种，tap_ratio / global_back 才允许执行。
     * 取值：countdown（界面上有倒计时）/ activity_hint（Activity 名命中）/ fullscreen_media（全屏图片或 WebView）
     *
     * 为什么需要它：盲点坐标本身没有任何依据，如果不加旁证就执行，
     * 等于每次启动都往右上角点一下，迟早会点到广告本体或正常按钮。
     */
    val evidence: List<String> = listOf(EVIDENCE_COUNTDOWN, EVIDENCE_ACTIVITY_HINT, EVIDENCE_FULLSCREEN_MEDIA),
    /**
     * 是否额外把候选限制在屏幕顶部。
     *
     * 取值："full"（默认，不限位置）/ "top"（只认顶部 25%）。
     *
     * 默认 full 是**被真机数据纠正过的结论**：一开始我根据"所有正确点击都在顶部"
     * 设成只认顶部，结果误杀了 B站 —— 它的跳过按钮 view id 是 count_down，
     * 中心在 (1024,2431)，屏幕 92% 高度处，是真按钮。
     * 不同 App 的跳过按钮位置本来就不同，所以位置不能当主要判据；
     * 主要判据交给了 SkipTargetRanker 里的"倒计时证据"。
     *
     * 保留这个开关只是为了给"底部存在危险元素"的个别 App 留一个收紧的口子。
     */
    val skipRegion: String = SkipTargetRanker.REGION_FULL,
    /**
     * 只在这个矩形内寻找跳过节点，格式 [left, top, right, bottom]（屏幕坐标）；null 表示不限。
     *
     * 借鉴自同类项目 SKIP 的 `skipBounds`，但比它的用法更保守：节点必须**完全落在**区域内。
     * 它比 `skipRegion` 的"顶部/不限"两档精细得多，也比盲点坐标安全 ——
     * 要求"在这个区域里找到一个真节点"，找不到就不动手；
     * 而盲点坐标是闭着眼睛点一个位置，广告已渲染但按钮还没出来时会点进广告。
     *
     * 真机用例（志愿汇）：开屏上同时存在左上角一个点不动的 `tvSkip` 和右上角真正的「跳过 5」。
     * 我们原来把每次启动的 3 次点击预算全浪费在假的上面，真按钮出现时预算已用光 ——
     * 实测 3 次启动 9 次点击只成功 1 次（11%）。用 bounds 排除假候选即可解决。
     */
    val nodeBounds: List<Int>? = null,
    val strategies: List<Strategy> = emptyList(),
    val abortIf: List<AbortCondition> = emptyList()
) {
    companion object {
        const val EVIDENCE_COUNTDOWN = "countdown"
        const val EVIDENCE_ACTIVITY_HINT = "activity_hint"
        const val EVIDENCE_FULLSCREEN_MEDIA = "fullscreen_media"
    }
}

/**
 * 一条跳过策略，按声明顺序依次尝试，第一条命中的生效。
 */
data class Strategy(
    /** node_text | tap_ratio | global_back */
    val type: String,
    /** node_text 使用的文本特征；留空则使用内置默认特征集 */
    val patterns: List<String> = emptyList(),
    /** tap_ratio 使用：相对屏幕宽高的比例 */
    val x: Float = 0f,
    val y: Float = 0f,
    /**
     * 为 true 时，必须 Activity 名命中 activityHints 才允许执行本条策略。
     *
     * 注意这里刻意没有 repeat/intervalMs 这类"连续点击"配置：
     * 重试由事件驱动的再次判定自然完成，并由 RuleEngine.MAX_TAPS_PER_LAUNCH 兜住上限。
     * 配置里能写出"连点 10 次"这种东西，早晚会有人在真实设备上误点。
     */
    val requireActivityHint: Boolean = false
) {
    companion object {
        const val NODE_TEXT = "node_text"
        const val TAP_RATIO = "tap_ratio"
        const val GLOBAL_BACK = "global_back"
    }
}

/**
 * 熔断条件：命中任意一条就立刻放弃本次启动的一切介入。
 *
 * 这一组是"保命"用的。开屏广告和登录页在时间窗上完全重合，
 * 区别只在于登录页有输入框 —— 所以 has_edittext 几乎是必配项。
 */
data class AbortCondition(
    /** has_edittext | text_contains */
    val type: String,
    val value: String? = null
) {
    companion object {
        const val HAS_EDITTEXT = "has_edittext"
        const val TEXT_CONTAINS = "text_contains"
    }
}
