package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户规则开关的覆盖逻辑。
 *
 * 关键约束：开关只能"关"，不能"开" —— 内置规则里默认禁用的示例规则
 * 带着盲点坐标和返回键兜底，被误开会在真实设备上乱点。
 */
class RuleOverridesTest {

    private fun rule(pkg: String, enabled: Boolean = true, name: String? = null) = AppRule(
        pkg = pkg,
        name = name,
        enabled = enabled,
        strategies = listOf(Strategy(Strategy.NODE_TEXT))
    )

    @Test
    fun `没有覆盖时规则原样返回`() {
        val rules = listOf(rule("com.a"), rule("com.b"))
        assertEquals(rules, RuleOverrides.apply(rules, emptySet()))
    }

    @Test
    fun `被用户关掉的规则 enabled 变成 false`() {
        val rules = listOf(rule("com.a"), rule("com.b"))
        val result = RuleOverrides.apply(rules, setOf("com.a"))

        assertFalse(result.first { it.pkg == "com.a" }.enabled)
        assertTrue(result.first { it.pkg == "com.b" }.enabled)
    }

    @Test
    fun `开关不能把内置默认禁用的规则打开`() {
        // 用户"打开"一条默认禁用的规则，在存储层表现为它不在 disabled 集合里，
        // 而 apply 只做减法，所以它仍然是禁用的
        val demo = rule("com.example.demo", enabled = false, name = "示例")
        val result = RuleOverrides.apply(listOf(demo), emptySet())

        assertFalse("示例规则必须保持禁用", result[0].enabled)
    }

    @Test
    fun `覆盖不影响其它字段`() {
        val original = rule("com.a", name = "某应用")
        val result = RuleOverrides.apply(listOf(original), setOf("com.a"))

        assertEquals("某应用", result[0].name)
        assertEquals(original.strategies, result[0].strategies)
        assertEquals(original.windowMs, result[0].windowMs)
    }

    @Test
    fun `未被接管的开关不会凭空减少规则数量`() {
        val rules = listOf(rule("com.a"), rule("com.b"), rule("com.c"))
        assertEquals(3, RuleOverrides.apply(rules, setOf("com.b")).size)
    }
}
