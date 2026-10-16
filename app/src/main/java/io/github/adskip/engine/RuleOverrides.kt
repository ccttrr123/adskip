package io.github.adskip.engine

/**
 * 用户对规则库的开关覆盖。
 *
 * 设计上刻意只做"减法"：内置规则库仍是唯一事实来源，用户的选择只记录成
 * 一个"被关掉的包名集合"，加载时把对应规则的 enabled 置 false。
 *
 * 反过来做（用户开关能打开内置默认禁用的规则）是不允许的 ——
 * 示例规则 `com.example.demo` 带着盲点坐标和返回键兜底，
 * 万一被误开就会在真实设备上乱点。
 */
object RuleOverrides {

    fun apply(rules: List<AppRule>, disabled: Set<String>): List<AppRule> {
        if (disabled.isEmpty()) return rules
        return rules.map { rule ->
            if (rule.pkg in disabled) rule.copy(enabled = false) else rule
        }
    }
}
