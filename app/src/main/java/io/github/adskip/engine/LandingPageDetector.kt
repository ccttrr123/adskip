package io.github.adskip.engine

/**
 * 同包落地页识别（M2 第一件；**阶段一：分级，先只让强证据影响判定**）。
 *
 * ## 为什么需要
 * [io.github.adskip.service.AdSkipService.verifyAfterTap] 判"成功"的判据是
 * "我们点的那一个跳过节点消失了"。但用户点到广告后，**落地页如果开在同一个 App 内部**的
 * 新 Activity 里，跳过节点同样会消失 —— 于是"误点"被记成"成功"，**成功率虚高** ✗。
 * （跨应用的情况已有 [io.github.adskip.service.AdSkipService] 里的 `looksLikeLandingPage` 兜底 ✓）
 *
 * ## 为什么分级、为什么先不改判定
 * 纯文本启发式一定有假阳性（应用商店首页也会出现"下载"，正常 App 首页也可能有"官方""免费"）。
 * 所以这里**不返回是/否**，而返回置信度：
 *
 * - [Confidence.STRONG]：命中 **≥2 个不同类别**的落地页特征 → 允许调用方据此**不记成功** ✓
 * - [Confidence.WEAK]：只命中 1 个类别 → 调用方**只记日志、不改判定** ✓
 *
 * 这样既能把明显的情况拦下来，又能低成本地收集真机样本、事后校正词表与阈值 ✓
 * （项目的一贯做法：先观察、有证据再动手）
 */
object LandingPageDetector {

    enum class Confidence {
        /** 没有任何落地页特征 */
        NONE,
        /** 只有一类特征 —— 可能是落地页，也可能只是普通首页，不足以改判定 */
        WEAK,
        /** ≥2 类特征 —— 足够像落地页，可以据此不记成功 */
        STRONG,
    }

    /** 类别一：下载/安装类动作 */
    private val DOWNLOAD_WORDS = listOf(
        "下载", "安装", "打开应用", "打开App", "立即体验",
    )

    /** 类别二：详情/购买类动作 */
    private val ACTION_WORDS = listOf(
        "查看详情", "了解详情", "立即购买", "立即领取", "领券",
        "加入购物车", "立即抢", "去看看", "立即查看",
    )

    /** 类别三：信任背书类话术（落地页极常见，正常 App 首页较少同时出现） */
    private val TRUST_WORDS = listOf(
        "已安装", "官方正版", "无广告", "限时免费", "秒开",
    )

    /**
     * 按"命中类别数"给置信度。
     *
     * 用**类别**而不是"命中词数"：同类别里命中五个也只算一类 ——
     * 否则一个应用商店页面（到处是"下载/安装"）会被轻易判成强证据 ✗
     */
    fun detect(labels: List<String>): Confidence {
        if (labels.isEmpty()) return Confidence.NONE
        val joined = labels.joinToString("\u0001")
        var categories = 0
        if (DOWNLOAD_WORDS.any { joined.contains(it) }) categories++
        if (ACTION_WORDS.any { joined.contains(it) }) categories++
        if (TRUST_WORDS.any { joined.contains(it) }) categories++
        return when {
            categories >= 2 -> Confidence.STRONG
            categories == 1 -> Confidence.WEAK
            else -> Confidence.NONE
        }
    }

    /** 命中的具体词 —— 只用于日志，方便事后按真机样本校正词表 ✓ */
    fun hits(labels: List<String>): List<String> {
        if (labels.isEmpty()) return emptyList()
        val joined = labels.joinToString("\u0001")
        return (DOWNLOAD_WORDS + ACTION_WORDS + TRUST_WORDS).filter { joined.contains(it) }
    }
}
