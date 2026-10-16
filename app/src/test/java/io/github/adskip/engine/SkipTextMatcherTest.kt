package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipTextMatcherTest {

    @Test
    fun `识别纯文字跳过按钮`() {
        assertTrue(SkipTextMatcher.isSkipLabel("跳过"))
        assertTrue(SkipTextMatcher.isSkipLabel("跳过广告"))
        assertTrue(SkipTextMatcher.isSkipLabel("点击跳过"))
        assertTrue(SkipTextMatcher.isSkipLabel("Skip"))
        assertTrue(SkipTextMatcher.isSkipLabel("SKIP AD"))
    }

    @Test
    fun `识别各种倒计时写法`() {
        assertTrue(SkipTextMatcher.isSkipLabel("跳过 3"))
        assertTrue(SkipTextMatcher.isSkipLabel("跳过3s"))
        assertTrue(SkipTextMatcher.isSkipLabel("3 跳过"))
        assertTrue(SkipTextMatcher.isSkipLabel("跳过 3 秒"))
        assertTrue(SkipTextMatcher.isSkipLabel("Skip 5"))
        assertTrue(SkipTextMatcher.isSkipLabel("跳过(4)"))
        assertTrue(SkipTextMatcher.isSkipLabel("3s"))
    }

    @Test
    fun `解析倒计时秒数`() {
        assertEquals(3, SkipTextMatcher.countdownSeconds("跳过 3"))
        assertEquals(3, SkipTextMatcher.countdownSeconds("跳过3s"))
        assertEquals(3, SkipTextMatcher.countdownSeconds("3 跳过"))
        assertEquals(3, SkipTextMatcher.countdownSeconds("3s 跳过"))
        assertEquals(3, SkipTextMatcher.countdownSeconds("3秒 跳过"))
        assertEquals(5, SkipTextMatcher.countdownSeconds("Skip 5"))
        assertEquals(4, SkipTextMatcher.countdownSeconds("跳过(4)"))
        assertEquals(0, SkipTextMatcher.countdownSeconds("跳过"))
        assertEquals(0, SkipTextMatcher.countdownSeconds("跳过广告"))
        assertNull(SkipTextMatcher.countdownSeconds("立即下载"))
        assertNull(SkipTextMatcher.countdownSeconds(null))
        assertNull(SkipTextMatcher.countdownSeconds(""))
    }

    @Test
    fun `不会把正常界面的文案误判成跳过`() {
        // 「关闭」刻意不在默认特征集里：正常界面里关闭按钮太多
        assertFalse(SkipTextMatcher.isSkipLabel("关闭"))
        assertFalse(SkipTextMatcher.isSkipLabel("确定"))
        assertFalse(SkipTextMatcher.isSkipLabel("立即下载"))
        assertFalse(SkipTextMatcher.isSkipLabel("查看详情"))
        assertFalse(SkipTextMatcher.isSkipLabel("打开"))
        assertFalse(SkipTextMatcher.isSkipLabel("登录"))
        assertFalse(SkipTextMatcher.isSkipLabel(null))
        assertFalse(SkipTextMatcher.isSkipLabel("   "))
    }

    @Test
    fun `规则可以自行扩展特征词`() {
        val custom = listOf("关闭")
        assertTrue(SkipTextMatcher.isSkipLabel("关闭", custom))
        assertFalse(SkipTextMatcher.isSkipLabel("确定", custom))
    }
}
