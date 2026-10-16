package io.github.adskip.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NeverTouchTest {

    @Test
    fun `支付宝和微信被拦下`() {
        assertTrue(NeverTouch.isBlocked("com.eg.android.AlipayGphone"))
        assertTrue(NeverTouch.isBlocked("com.tencent.mm"))
    }

    @Test
    fun `银行与钱包类被关键字兜住`() {
        assertTrue(NeverTouch.isBlocked("com.unionpay"))
        assertTrue(NeverTouch.isBlocked("com.miui.wallet"))
        assertTrue(NeverTouch.isBlocked("com.some.bank.app"))
        assertTrue(NeverTouch.isBlocked("air.com.xxx.tenpay"))
    }

    @Test
    fun `规则库里的应用一个都不能被误伤`() {
        // 这条最重要：关键字兜底一旦误伤某个规则应用，那个应用就**静默失效**了，
        // 而且不会报错 —— 只有这条测试能拦住它。
        val json = File("src/main/assets/rules.json").readText()
        val pkgs = Regex("\"pkg\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }
        val hurt = pkgs.filter { NeverTouch.isBlocked(it) }.toList()
        assertTrue("这些规则应用被永不接管名单误伤了：$hurt", hurt.isEmpty())
    }

    @Test
    fun `普通应用不受影响`() {
        assertFalse(NeverTouch.isBlocked("com.baidu.tieba"))
        assertFalse(NeverTouch.isBlocked("tv.danmaku.bili"))
        assertFalse(NeverTouch.isBlocked("com.jingdong.app.mall"))
        assertFalse(NeverTouch.isBlocked("com.chaoxing.mobile"))
    }

    @Test
    fun `空包名不拦`() {
        assertFalse(NeverTouch.isBlocked(""))
    }
}