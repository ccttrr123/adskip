package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同包落地页识别（阶段一：分级）。
 *
 * 重点不是"判得准"，而是**宁可漏判也不误判成成功**：
 * 只有 ≥2 类特征才允许影响统计 ✓；单类特征一律只记日志 ✓。
 */
class LandingPageDetectorTest {

    @Test
    fun `普通首页没有任何特征`() {
        assertEquals(
            LandingPageDetector.Confidence.NONE,
            LandingPageDetector.detect(listOf("首页", "推荐", "我的", "搜索")),
        )
    }

    @Test
    fun `空界面不判`() {
        assertEquals(LandingPageDetector.Confidence.NONE, LandingPageDetector.detect(emptyList()))
    }

    @Test
    fun `只有下载类特征属于弱证据`() {
        // 应用商店首页到处是"下载/安装"，不能据此改判定
        assertEquals(
            LandingPageDetector.Confidence.WEAK,
            LandingPageDetector.detect(listOf("立即下载", "安装", "打开应用", "首页")),
        )
    }

    @Test
    fun `下载类加详情类属于强证据`() {
        assertEquals(
            LandingPageDetector.Confidence.STRONG,
            LandingPageDetector.detect(listOf("立即下载", "查看详情", "某 App")),
        )
    }

    @Test
    fun `详情类加信任话术也属于强证据`() {
        assertEquals(
            LandingPageDetector.Confidence.STRONG,
            LandingPageDetector.detect(listOf("立即购买", "官方正版", "限时免费")),
        )
    }

    @Test
    fun `同一类里命中再多也只算一类`() {
        // 五个下载词仍只是 1 类 → 弱证据，不许影响判定
        assertEquals(
            LandingPageDetector.Confidence.WEAK,
            LandingPageDetector.detect(listOf("下载", "安装", "打开应用", "打开App", "立即体验")),
        )
    }

    @Test
    fun `hits 能列出命中的词用于日志`() {
        val hits = LandingPageDetector.hits(listOf("立即下载", "查看详情", "首页"))
        assertEquals(listOf("下载", "查看详情"), hits)
    }
}
