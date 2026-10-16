package io.github.adskip.rules

import com.google.gson.Gson
import io.github.adskip.engine.AbortCondition
import io.github.adskip.engine.AppRule
import io.github.adskip.engine.RuleSet
import io.github.adskip.engine.SkipTargetRanker
import io.github.adskip.engine.Strategy

/**
 * 规则库 JSON 解析。
 *
 * 设计目标只有一条：**一份手写的规则库，即使字段写漏、写错、写了未来版本的新策略，
 * 也绝不能产生"看似生效实则危险"的行为。**
 *
 * 因此这里的策略是"补默认值 + 丢弃不可用项"，而不是抛异常或放任 null 流进引擎：
 *   - 缺 pkg / 缺策略        → 丢弃这一条规则
 *   - 缺 windowMs            → 补默认值，并夹到安全上限内
 *   - 缺 evidence            → 补默认旁证集合
 *   - 未知的策略类型          → 丢弃该策略（向前兼容：新规则库不会让旧客户端崩溃）
 *   - x/y 越界               → 夹到 [0,1]
 */
object RuleSetParser {

    private val gson = Gson()

    /**
     * 默认时间窗。
     *
     * 从 3000 提到 5000 是真机实测的结果：贴吧 在启动后 **2.4 秒**时屏幕上还是它自己的
     * 纯色 logo（截图 49KB，而广告是 1.3MB），广告要到 3 秒多才上来。
     * 窗口只有 3 秒时，会出现"广告还没出现窗口就关了"的情况 ——
     * 表现为某个应用有时候跳过、有时候完全不动作。
     */
    const val DEFAULT_WINDOW_MS = 5000L

    /**
     * 时间窗上限。没有这个上限，一条写错的 windowMs 就能让服务在该 App 里
     * 持续几分钟盯着屏幕找"跳过"，这已经偏离了本项目的全部安全前提。
     */
    const val MAX_WINDOW_MS = 10000L

    val DEFAULT_EVIDENCE = listOf(
        AppRule.EVIDENCE_COUNTDOWN,
        AppRule.EVIDENCE_ACTIVITY_HINT,
        AppRule.EVIDENCE_FULLSCREEN_MEDIA
    )

    private val KNOWN_STRATEGIES = setOf(
        Strategy.NODE_TEXT,
        Strategy.TAP_RATIO,
        Strategy.GLOBAL_BACK
    )

    private val KNOWN_ABORT_TYPES = setOf(
        AbortCondition.HAS_EDITTEXT,
        AbortCondition.TEXT_CONTAINS
    )

    /** @throws com.google.gson.JsonSyntaxException JSON 非法时抛出，由调用方决定回退策略 */
    fun parse(json: String): RuleSet {
        val dto = gson.fromJson(json, RuleSetDto::class.java) ?: return RuleSet()

        val rules = dto.rules.orEmpty()
            .mapNotNull { toRule(it) }

        return RuleSet(
            version = dto.version?.takeIf { it > 0 } ?: 1,
            updatedAt = dto.updatedAt,
            rules = rules
        )
    }

    private fun toRule(dto: AppRuleDto): AppRule? {
        val pkg = dto.pkg?.trim().orEmpty()
        if (pkg.isEmpty()) return null

        val strategies = dto.strategies.orEmpty()
            .mapNotNull { toStrategy(it) }
        if (strategies.isEmpty()) return null

        val abortIf = dto.abortIf.orEmpty()
            .mapNotNull { toAbortCondition(it) }

        return AppRule(
            pkg = pkg,
            name = dto.name?.trim()?.takeIf { it.isNotEmpty() },
            enabled = dto.enabled ?: true,
            windowMs = (dto.windowMs ?: DEFAULT_WINDOW_MS).coerceIn(1L, MAX_WINDOW_MS),
            activityHints = dto.activityHints.orEmpty().filter { it.isNotBlank() },
            evidence = dto.evidence.orEmpty()
                .filter { it in DEFAULT_EVIDENCE }
                .ifEmpty { DEFAULT_EVIDENCE },
            // 默认 full（不限位置）：真机数据证明不同 App 的跳过按钮位置不同，
            // B站 的在屏幕 92% 高度处。位置限制改由 SkipTargetRanker 的
            // "倒计时证据 + 边缘判定" 处理，收紧了但不会误杀。
            skipRegion = dto.skipRegion?.trim()?.takeIf { it in SkipTargetRanker.KNOWN_REGIONS }
                ?: SkipTargetRanker.REGION_FULL,
            // 必须是恰好 4 个整数，否则当作没写 —— 写坏的坐标比不限制更危险
            nodeBounds = dto.nodeBounds?.takeIf { it.size == 4 },
            strategies = strategies,
            abortIf = abortIf
        )
    }

    private fun toStrategy(dto: StrategyDto): Strategy? {
        val type = dto.type?.trim().orEmpty()
        if (type !in KNOWN_STRATEGIES) return null

        return Strategy(
            type = type,
            patterns = dto.patterns.orEmpty().filter { it.isNotBlank() },
            x = (dto.x ?: 0f).coerceIn(0f, 1f),
            y = (dto.y ?: 0f).coerceIn(0f, 1f),
            requireActivityHint = dto.requireActivityHint ?: false
        )
    }

    private fun toAbortCondition(dto: AbortConditionDto): AbortCondition? {
        val type = dto.type?.trim().orEmpty()
        if (type !in KNOWN_ABORT_TYPES) return null

        val value = dto.value?.trim()
        if (type == AbortCondition.TEXT_CONTAINS && value.isNullOrEmpty()) return null

        return AbortCondition(type = type, value = value)
    }
}
