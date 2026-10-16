package io.github.adskip.rules

/**
 * 规则库的 JSON 传输对象。
 *
 * 为什么必须和 engine.Rule 分开，而不是直接用 Gson 反序列化成数据类：
 *
 * Gson 不走 Kotlin 的构造函数，而是用 Unsafe 分配实例再反射填字段。
 * 于是**规则里省略的字段会变成 0 / null / false，而不是 Kotlin 里写的默认值**。
 * 对一个"要由社区手写维护"的规则库来说这是致命的：
 * 漏写 windowMs 会让规则永远不生效，漏写 evidence 会直接 NPE，而且都没有任何报错。
 *
 * 所以这一层老老实实全部声明成可空，把"缺省怎么补、非法怎么丢"集中放在
 * RuleSetParser 里显式处理，并由单元测试覆盖。
 */
data class RuleSetDto(
    val version: Int? = null,
    val updatedAt: String? = null,
    val rules: List<AppRuleDto>? = null
)

data class AppRuleDto(
    val pkg: String? = null,
    val name: String? = null,
    val enabled: Boolean? = null,
    val windowMs: Long? = null,
    val activityHints: List<String>? = null,
    val evidence: List<String>? = null,
    val skipRegion: String? = null,
    val nodeBounds: List<Int>? = null,
    val strategies: List<StrategyDto>? = null,
    val abortIf: List<AbortConditionDto>? = null
)

data class StrategyDto(
    val type: String? = null,
    val patterns: List<String>? = null,
    val x: Float? = null,
    val y: Float? = null,
    val requireActivityHint: Boolean? = null
)

data class AbortConditionDto(
    val type: String? = null,
    val value: String? = null
)
