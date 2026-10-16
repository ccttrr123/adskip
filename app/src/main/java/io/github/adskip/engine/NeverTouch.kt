package io.github.adskip.engine

/**
 * **永不接管名单**：支付 / 金融 / 银行类应用。
 *
 * 对名单里的包，服务**连界面内容都不读**（不只是"不点击"）——
 * 这是能在代码层面证明的承诺："我从不看支付界面"。
 *
 * 背景：支付宝会对"存在非白名单无障碍服务"弹出「支付环境有风险 / 正在通过无障碍服务控制」。
 * 那个提示**无法通过正当手段消除**（它检测的是能力，不是某个应用），
 * 唯一的欺骗性手段（伪造 isAccessibilityTool、隐藏服务、模拟人手点击）都属于对抗风控，
 * 本项目明确不做。能做的就是：把支付类应用彻底排除在接管范围之外，并如实告知用户。
 */
object NeverTouch {

    /** 明确已知的支付/金融应用 */
    private val PACKAGES = setOf(
        "com.eg.android.AlipayGphone",   // 支付宝
        "com.tencent.mm",                // 微信（含微信支付）
        "com.unionpay",                  // 云闪付
        "com.jd.jrapp",                  // 京东金融
        "com.icbc",                      // 工商银行
        "com.chinamworld.main",          // 建设银行
        "cmb.pb",                        // 招商银行
        "com.android.bankabc",           // 农业银行
        "com.bankcomm.Bankcomm",         // 交通银行
        "com.psbc.mobilebank",           // 邮储银行
        "com.pingan.paces.ccms",         // 平安口袋银行
        "com.paypal.android.p2pmobile"   // PayPal
    )

    /**
     * 关键字兜底：银行和钱包类应用包名太杂，列不全。
     * 这些词都很"专有"，正常应用包名里极少出现 ——
     * 而且有单元测试盯着，**任何规则库里的应用被误伤都会让测试失败**。
     */
    private val KEYWORDS = listOf(
        "alipay", "unionpay", "wallet", "bank", "paypal", "tenpay", "wxpay"
    )

    fun isBlocked(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg in PACKAGES) return true
        val lower = pkg.lowercase()
        return KEYWORDS.any { lower.contains(it) }
    }
}