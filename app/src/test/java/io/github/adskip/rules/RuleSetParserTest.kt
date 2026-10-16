package io.github.adskip.rules

import com.google.gson.JsonSyntaxException
import io.github.adskip.engine.AbortCondition
import io.github.adskip.engine.AppRule
import io.github.adskip.engine.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 规则库解析测试。
 *
 * 重点全部在"手写规则写漏了会怎样"上 —— 因为规则库最终是由社区一 App 一条手写维护的，
 * 而 Gson 不会应用 Kotlin 的默认值，这类问题如果不在解析层兜住，
 * 表现就是"规则看起来没问题但就是不生效"，极难排查。
 */
class RuleSetParserTest {

    @Test
    fun `最简规则也能解析并补齐默认值`() {
        // 这是社区贡献者最可能写出的形态：只写包名和一条策略
        val json = """
            { "rules": [ { "pkg": "com.a", "strategies": [ { "type": "node_text" } ] } ] }
        """.trimIndent()

        val ruleSet = RuleSetParser.parse(json)
        assertEquals(1, ruleSet.rules.size)

        val rule = ruleSet.rules[0]
        assertEquals("com.a", rule.pkg)
        assertEquals(RuleSetParser.DEFAULT_WINDOW_MS, rule.windowMs)
        assertEquals(RuleSetParser.DEFAULT_EVIDENCE, rule.evidence)
        assertTrue(rule.enabled) // 省略 enabled 时默认启用
        assertTrue(rule.activityHints.isEmpty())
        assertTrue(rule.abortIf.isEmpty())
    }

    @Test
    fun `省略 strategies 的规则被丢弃而不是变成危险空规则`() {
        val json = """{ "rules": [ { "pkg": "com.a" } ] }"""
        assertTrue(RuleSetParser.parse(json).rules.isEmpty())
    }

    @Test
    fun `缺少包名的规则被丢弃`() {
        val json = """{ "rules": [ { "name": "没有包名", "strategies": [ { "type": "node_text" } ] } ] }"""
        assertTrue(RuleSetParser.parse(json).rules.isEmpty())
    }

    @Test
    fun `时间窗被夹到安全上限内`() {
        val json = """
            { "rules": [ { "pkg": "com.a", "windowMs": 999999, "strategies": [ { "type": "node_text" } ] } ] }
        """.trimIndent()
        assertEquals(RuleSetParser.MAX_WINDOW_MS, RuleSetParser.parse(json).rules[0].windowMs)
    }

    @Test
    fun `未知策略类型被丢弃_保证向前兼容`() {
        val json = """
            { "rules": [ { "pkg": "com.a", "strategies": [
                { "type": "ocr_vision_2077" },
                { "type": "node_text" }
            ] } ] }
        """.trimIndent()

        val rule = RuleSetParser.parse(json).rules[0]
        assertEquals(1, rule.strategies.size)
        assertEquals(Strategy.NODE_TEXT, rule.strategies[0].type)
    }

    @Test
    fun `策略全部非法时整条规则被丢弃`() {
        val json = """{ "rules": [ { "pkg": "com.a", "strategies": [ { "type": "bogus" } ] } ] }"""
        assertTrue(RuleSetParser.parse(json).rules.isEmpty())
    }

    @Test
    fun `未知熔断类型被丢弃_缺少条件的熔断被丢弃`() {
        val json = """
            { "rules": [ { "pkg": "com.a", "strategies": [ { "type": "node_text" } ], "abortIf": [
                { "type": "unknown_type" },
                { "type": "text_contains" },
                { "type": "has_edittext" }
            ] } ] }
        """.trimIndent()

        val abortIf = RuleSetParser.parse(json).rules[0].abortIf
        assertEquals(1, abortIf.size)
        assertEquals(AbortCondition.HAS_EDITTEXT, abortIf[0].type)
    }

    @Test
    fun `坐标比例被夹到 0 到 1`() {
        val json = """
            { "rules": [ { "pkg": "com.a", "strategies": [
                { "type": "tap_ratio", "x": 1.8, "y": -0.4 }
            ] } ] }
        """.trimIndent()

        val strategy = RuleSetParser.parse(json).rules[0].strategies[0]
        assertEquals(1.0f, strategy.x, 0.0001f)
        assertEquals(0.0f, strategy.y, 0.0001f)
    }

    @Test
    fun `空白旁证列表会回退到默认旁证集合`() {
        val json = """
            { "rules": [ { "pkg": "com.a", "evidence": [], "strategies": [ { "type": "node_text" } ] } ] }
        """.trimIndent()
        assertEquals(RuleSetParser.DEFAULT_EVIDENCE, RuleSetParser.parse(json).rules[0].evidence)
    }

    @Test
    fun `未知旁证名会被过滤掉`() {
        val json = """
            { "rules": [ { "pkg": "com.a", "evidence": ["countdown", "telepathy"],
                           "strategies": [ { "type": "node_text" } ] } ] }
        """.trimIndent()
        assertEquals(listOf(AppRule.EVIDENCE_COUNTDOWN), RuleSetParser.parse(json).rules[0].evidence)
    }

    @Test
    fun `版本号缺失时回退为 1`() {
        assertEquals(1, RuleSetParser.parse("""{ "rules": [] }""").version)
    }

    @Test
    fun `空对象不会崩溃`() {
        val ruleSet = RuleSetParser.parse("{}")
        assertEquals(1, ruleSet.version)
        assertTrue(ruleSet.rules.isEmpty())
    }

    @Test(expected = JsonSyntaxException::class)
    fun `非法 JSON 直接抛出_由调用方决定回退`() {
        RuleSetParser.parse("{ this is not json")
    }

    /**
     * 校验随应用一起发布的规则库资源本身是合法且可用的。
     * 这条测试的意义：规则库是数据文件，编译期不会检查它，
     * 只有真的解析一遍才能发现结构性错误。
     */
    @Test
    fun `内置规则库资源可被正确解析`() {
        val file = File("src/main/assets/rules.json")
        assertTrue("找不到内置规则库：${file.absolutePath}", file.exists())

        val ruleSet = RuleSetParser.parse(file.readText())
        assertTrue("内置规则库不应为空", ruleSet.rules.isNotEmpty())

        ruleSet.rules.forEach { rule ->
            assertTrue("规则包名不应为空", rule.pkg.isNotBlank())
            assertTrue("规则 ${rule.pkg} 必须有策略", rule.strategies.isNotEmpty())
            assertTrue("规则 ${rule.pkg} 的时间窗必须为正", rule.windowMs > 0)
            assertTrue("规则 ${rule.pkg} 的旁证不应为空", rule.evidence.isNotEmpty())
        }

        // 示例规则用来向贡献者演示 schema，应当存在但默认关闭
        val demo = ruleSet.rules.firstOrNull { it.pkg == "com.example.demo" }
        assertNotNull("内置规则库应保留一条示例规则用于演示 schema", demo)
        assertFalse("示例规则必须默认禁用", demo!!.enabled)
        assertTrue(demo.strategies.any { it.type == Strategy.TAP_RATIO })
    }
}
