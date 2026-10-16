package io.github.adskip.engine

/**
 * 判定引擎：决定"现在该不该动手、动哪里"。
 *
 * 这是整个项目唯一真正需要正确性的部分，因此它完全不依赖 Android：
 * 输入是一个界面快照，输出是一个 Decision。所有安全规则都在这里集中体现：
 *
 *   1. 时间窗      —— 只在该 App 启动后的最初几秒内介入
 *   2. 熔断        —— 一旦发现输入框等"正常页面"特征，立刻永久放弃本次启动
 *   3. 点击上限    —— 每次启动最多注入固定次数的点击，避免在界面上乱点
 *   4. 盲点需旁证  —— 没有文本依据的坐标点击，必须先有倒计时/全屏媒体等旁证解锁
 *   5. 策略有次序  —— 按规则声明顺序尝试，先精确后粗略
 */
class RuleEngine(rules: List<AppRule>) {

    private val byPkg: Map<String, AppRule> =
        rules.filter { it.enabled && it.pkg.isNotBlank() }.associateBy { it.pkg }

    val ruleCount: Int get() = byPkg.size

    fun isTarget(pkg: String): Boolean = byPkg.containsKey(pkg)

    fun ruleFor(pkg: String): AppRule? = byPkg[pkg]

    fun evaluate(snapshot: UiSnapshot): Decision {
        val rule = byPkg[snapshot.pkg] ?: return Decision.Ignore

        if (snapshot.firstSeenAgeMs > rule.windowMs) {
            return Decision.Expired(snapshot.firstSeenAgeMs, rule.windowMs)
        }

        if (snapshot.tapsThisLaunch >= MAX_TAPS_PER_LAUNCH) {
            return Decision.Abort("本次启动已达点击上限($MAX_TAPS_PER_LAUNCH)")
        }

        abortReason(rule, snapshot)?.let { return Decision.Abort(it) }

        val evidence = collectEvidence(rule, snapshot)
        val blocked = mutableListOf<String>()

        for (strategy in rule.strategies) {
            applyStrategy(rule, strategy, snapshot, evidence, blocked)?.let { return it }
        }

        // 把"盲点策略为什么没执行"写进理由里。
        // 没有这一条时，"规则里配了盲点但一直不生效"和"这个应用没出广告"在日志里长得一模一样，
        // 规则作者无法判断该补哪种旁证。顺带打印 Activity 名，方便改用 activity_hint 旁证。
        val reason = if (blocked.isEmpty()) {
            REASON_NOTHING_FOUND
        } else {
            blocked.joinToString("；") + "｜当前 Activity=${snapshot.activityName.ifEmpty { "未知" }}"
        }
        return Decision.Wait(reason)
    }

    // ---------------------------------------------------------------- 熔断

    private fun abortReason(rule: AppRule, snapshot: UiSnapshot): String? {
        for (cond in rule.abortIf) {
            when (cond.type) {
                AbortCondition.HAS_EDITTEXT ->
                    if (snapshot.nodes.any { it.isEditText() }) {
                        return "存在输入框，判定为登录/正常页面"
                    }

                AbortCondition.TEXT_CONTAINS -> {
                    val v = cond.value
                    if (!v.isNullOrBlank() && snapshot.textOfAllNodes().contains(v, ignoreCase = true)) {
                        return "界面出现「$v」，判定为非广告页面"
                    }
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 旁证

    /**
     * 收集当前界面支持哪些旁证。返回命中的旁证集合。
     */
    fun collectEvidence(rule: AppRule, snapshot: UiSnapshot): Set<String> {
        val hit = mutableSetOf<String>()

        if (matchesActivityHint(rule, snapshot)) {
            hit += AppRule.EVIDENCE_ACTIVITY_HINT
        }

        if (snapshot.nodes.any { SkipTextMatcher.countdownSeconds(it.label()) != null }) {
            hit += AppRule.EVIDENCE_COUNTDOWN
        }

        if (hasFullscreenMedia(snapshot)) {
            hit += AppRule.EVIDENCE_FULLSCREEN_MEDIA
        }

        return hit
    }

    private fun matchesActivityHint(rule: AppRule, snapshot: UiSnapshot): Boolean {
        if (rule.activityHints.isEmpty()) return false
        val name = snapshot.activityName
        if (name.isBlank()) return false
        return rule.activityHints.any { it.isNotBlank() && name.contains(it, ignoreCase = true) }
    }

    /**
     * 屏幕上是否存在占屏面积 ≥60% 的图片/网页/视频类节点。
     * 开屏广告几乎都是这种形态，而正常的功能界面极少如此。
     */
    private fun hasFullscreenMedia(snapshot: UiSnapshot): Boolean =
        hasFullscreenMedia(snapshot.nodes, snapshot.screenWidth, snapshot.screenHeight)

    // ---------------------------------------------------------------- 策略

    private fun applyStrategy(
        rule: AppRule,
        strategy: Strategy,
        snapshot: UiSnapshot,
        evidence: Set<String>,
        blocked: MutableList<String>
    ): Decision? {
        if (strategy.requireActivityHint && !matchesActivityHint(rule, snapshot)) {
            return null
        }

        return when (strategy.type) {
            Strategy.NODE_TEXT -> {
                val patterns = strategy.patterns.ifEmpty { SkipTextMatcher.DEFAULT_PATTERNS }

                // 先收齐所有文本命中的候选，再按"像不像一个跳过按钮"排序。
                // 不能直接取第一个命中项 —— 真机日志里第一个命中项经常是错的（见 SkipTargetRanker 注释）。
                // 先收齐所有文本命中的候选，**包含**被区域排除的那些：
                // 后者也必须能出现在日志里，否则"规则配了搜索区域却一直不动手"无从排查。
                // 这个缺口是单元测试抓出来的（区域过滤原来在日志之外悄悄丢候选）。
                val candidates = snapshot.nodes.filter {
                    SkipTextMatcher.isSkipLabel(it.label(), patterns)
                }
                val inRegion = candidates.filter { node ->
                    // 规则声明的搜索区域：节点必须完全落在里面。
                    // 这是排除"同屏其它 跳过/关闭 节点"最可靠的手段 ——
                    // 志愿汇 那个点不动的 tvSkip 就靠它出局，从而不再浪费点击预算。
                    val b = rule.nodeBounds
                    b == null || (node.left >= b[0] && node.top >= b[1] &&
                        node.right <= b[2] && node.bottom <= b[3])
                }
                if (candidates.isEmpty()) return null

                val picked = SkipTargetRanker.pick(
                    // 本次启动里点过没生效的节点直接跳过：反馈驱动的排除，
                    // 不依赖"位置大概在哪"这种会误伤的推断
                    candidates = inRegion.filter { it.nodeKey() !in snapshot.failedNodeKeys },
                    screenWidth = snapshot.screenWidth,
                    screenHeight = snapshot.screenHeight,
                    region = rule.skipRegion,
                    exactLabels = patterns
                )

                if (picked == null) {
                    // 有候选但全被安全规则否决。
                    //
                    // 这里**绝对不能直接 return** —— 那会把后面所有策略一起跳过。
                    // 真机教训（志愿汇 的 AM广告变体）：屏上有个点不动的 tvSkip 一直占着候选位，
                    // 每次判定都在这里返回，后面的盲点兜底**永远轮不到**，
                    // 日志里从此再也看不到"盲点安全区"或"盲点策略未解锁"，看起来像兜底没生效。
                    //
                    // 正确做法：把原因记进 blocked 后继续尝试下一条策略；
                    // 若所有策略都失败，总的 Wait 会带上这些原因（候选信息不会丢）。
                    blocked += "发现 ${candidates.size} 个跳过候选，但都被安全规则否决：" +
                        candidates.take(3).joinToString("；") { it.describe() }
                    return null
                }

                val rejected = SkipTargetRanker.rejected(
                    candidates = candidates,
                    screenWidth = snapshot.screenWidth,
                    screenHeight = snapshot.screenHeight,
                    region = rule.skipRegion
                ).filter { it !== picked }

                Decision.TapNode(picked, "文本命中「${picked.label().take(16)}」", rejected)
            }

            Strategy.TAP_RATIO -> {
                // 盲点必须有依据：没有旁证就不允许执行，否则等于每次启动乱点一下

                val unlocked = rule.evidence.any { it in evidence }
                if (!unlocked) {
                    blocked += "盲点策略未解锁：规则要求旁证 ${rule.evidence}，当前一个都没出现" +
                        "（实际命中：${evidence.ifEmpty { setOf("无") }.joinToString("+")}）"
                    return null
                }
                if (snapshot.screenWidth <= 0 || snapshot.screenHeight <= 0) return null
                val x = (snapshot.screenWidth * strategy.x).toInt()
                val y = (snapshot.screenHeight * strategy.y).toInt()
                if (x <= 0 || y <= 0 || x >= snapshot.screenWidth || y >= snapshot.screenHeight) {
                    return null
                }
                Decision.TapPoint(x, y, "盲点安全区(${strategy.x}, ${strategy.y})，旁证=${evidence.joinToString("+")}")
            }

            Strategy.GLOBAL_BACK -> {

                val unlocked = rule.evidence.any { it in evidence }
                if (!unlocked) {
                    blocked += "返回键兜底未解锁：规则要求旁证 ${rule.evidence}，当前一个都没出现"
                    return null
                }
                Decision.GlobalBack("返回键兜底，旁证=${evidence.joinToString("+")}")
            }

            else -> null
        }
    }

    companion object {
        /** 每次启动允许注入的最大点击次数 */
        const val MAX_TAPS_PER_LAUNCH = 3


        /**
         * "屏幕上有没有占屏 ≥60% 的图片/网页/视频节点" —— 也就是**广告还在不在屏幕上**。
         *
         * 点击后的校验必须问这个问题，而不是问"我点的那一个节点还在不在"：
         * 真机上 志愿汇 的跳过按钮坐标会移动（(0,0,210,88) → (0,70,210,158)），
         * 点完之后它只是换了位置，按坐标比对的校验就会误判成"消失了＝跳掉了"。
         * 这个误判让我一度以为修好了，实际上没有。
         */
        fun hasFullscreenMedia(nodes: List<UiNode>, screenWidth: Int, screenHeight: Int): Boolean {
            val screenArea = screenWidth.toLong() * screenHeight.toLong()
            if (screenArea <= 0L) return false
            return nodes.any { node ->
                node.area * 10L >= screenArea * 6L &&
                    MEDIA_CLASS_HINTS.any { node.className.contains(it, ignoreCase = true) }
            }
        }

        /**
         * "什么都没找到"的通用理由。
         * 单独抽成常量是因为调用方需要区分它和"盲点策略被旁证拦下"这类诊断信息 ——
         * 两者以前在日志里长得一样，导致配了盲点规则却查不出为什么没生效。
         */
        const val REASON_NOTHING_FOUND = "在时间窗内但未找到跳过目标"

        private val MEDIA_CLASS_HINTS = listOf(
            "ImageView", "WebView", "SurfaceView", "VideoView", "TextureView", "GLSurfaceView"
        )
    }
}
