package io.github.adskip.engine

/**
 * 盲点（`node == null`）点击后的校验判定 —— **纯函数**，不依赖 Android。
 *
 * ## 为什么不能用节点树判"成功/失败"（真机根因，2026-11-10 实测）
 *
 * 志愿汇 的自绘开屏广告**不在无障碍节点树里**。广告铺满屏幕时 `uiautomator dump`
 * 拿到的是底下的主页面（105 个节点、「找活动/找组织/领证书…」），**0 个含"跳过"**。
 * 所以"跳过字样还在不在"对这类广告天然无效：读到的是主页面，跳过字样与广告无关。
 * 旧逻辑因此把"广告已经关掉"误判成"跳过节点仍存在" → 记失败 → 继续点，白耗点击预算。
 *
 * ## 能用的判据：点击前后"目标应用的窗口"是否消失
 *
 * 真机 14 轮日志里，盲点点击生效时**点击时还在的那个窗口（启动 Activity 的窗口）在 1 秒后
 * 从无障碍窗口列表里消失了**（例：`7797/1` → `7798/1`，LaunchActivity 被 MainActivity 换掉）。
 * 对照组（关掉无障碍服务、没有任何点击）里，同一个窗口要到约 **7.5 秒**才消失（广告倒计时结束）——
 * 也就是说"1 秒内消失"是点击造成的，不是应用自己推进的。
 *
 * 判据刻意收得很窄：
 *   1. 只比**点击时存在、校验时消失**的窗口（不看"新出现的窗口"，那可能是任何弹窗）；
 *   2. 只比窗口 `id/type`，不比较包名可读性/焦点/层级/坐标 —— 那些都会抖；
 *   3. 只取目标应用的窗口，以及"根包名读不到、但类型是普通应用窗口"的窗口
 *      （自绘广告若作为独立窗口出现，就在这一类里）；系统窗口（状态栏/输入法，type≥2）全部排除；
 *   4. 空窗口列表/读不到 → 不判。
 *
 * ## 为什么不用包名变化当成功判据
 *
 * 判定入口已经要求"根节点包名 == 目标包"才允许出手（跨应用守卫），所以点击前那一刻的包名
 * 必然是目标包或读不到；能观察到的"包名变化"只剩"读不到 → 读到"这种可读性变化，
 * 与广告是否关掉无关（真机复验抓到过一次 `MainActivity → LaunchActivity` 的假成功，
 * 就是事件滞后造成的）。包名这里只用来判断"读没读到"，不参与成功判定。
 *
 * 都读不到时**不记任何结论**（不记成功也不记失败），并让调用方停止探测循环：
 * 没有观察就没有结论，宁可少记一次，也不要把猜测写进统计。
 */
object BlindVerifier {

    /** `AccessibilityWindowInfo.TYPE_APPLICATION`；类型为它的窗口才可能是应用内容/自绘广告 */
    private const val TYPE_APPLICATION = 1

    sealed interface Verdict {
        /** 点击时存在的目标应用窗口在校验时消失了 → 广告已经不在前台 */
        data class Success(val evidence: String) : Verdict

        /** 窗口列表读到了、点击时那几个窗口都还在 → 广告还在（判定未生效） */
        data class Unchanged(val evidence: String) : Verdict

        /** 信号读不到 / 没有比较基线 → 不记结论 */
        data class Unknown(val reason: String) : Verdict
    }

    /**
     * @param targetPkg 正在处理的应用包名
     * @param before 点击前一刻的观测（由调用方在注入手势**之前**读好）
     * @param after 校验时刻的观测
     */
    fun judge(targetPkg: String, before: BlindObservation, after: BlindObservation): Verdict {
        // 1) 两个信号都读不到：任何结论都是猜的
        if (after.unreadable) {
            return Verdict.Unknown("校验时读不到前台包名与窗口列表")
        }
        if (before.unreadable) {
            return Verdict.Unknown("点击时读不到前台包名与窗口列表，没有比较基线")
        }
        val beforeWindows = before.windows
            ?: return Verdict.Unknown("点击时窗口列表读不到，没有比较基线")
        val afterWindows = after.windows
            ?: return Verdict.Unknown("校验时窗口列表读不到，无法确认广告窗口是否消失")

        // 2) 点击时没有可比较的目标应用窗口 → 没有基线
        val beforeKeys = relevantWindowKeys(beforeWindows, targetPkg)
        if (beforeKeys.isEmpty()) {
            return Verdict.Unknown("点击时窗口列表里没有目标应用窗口，没有比较基线")
        }

        // 3) 只认"点击时存在、校验时消失"的窗口。新出现的窗口不算证据：
        //    任何弹窗/新 Activity 都会让它出现，不能说明广告关掉了。
        val afterKeys = relevantWindowKeys(afterWindows, targetPkg)
        val disappeared = beforeKeys.toSet() - afterKeys.toSet()
        if (disappeared.isNotEmpty()) {
            return Verdict.Success("目标应用窗口消失 ${disappeared.sorted()}")
        }

        // 4) 点击时的目标应用窗口都还在
        return Verdict.Unchanged("目标应用窗口未消失（点击时 $beforeKeys，校验时 $afterKeys）")
    }

    /**
     * 单个窗口的"身份"：id + 类型。
     *
     * **刻意不含 rootPackage**：它是"读没读到"的反映，点击瞬间根节点可能读不到、1 秒后又能读到，
     * 把包名算进 key 会把这种可读性变化误判成"窗口被换掉了 → 成功"。
     * 包名只在过滤阶段用：[isRelevantWindow]。
     */
    internal fun relevantWindowKeys(
        windows: List<WindowFingerprint>,
        targetPkg: String
    ): List<String> =
        windows
            .filter { isRelevantWindow(it, targetPkg) }
            .map { "${it.id}/${it.type}" }
            .sorted()

    /**
     * 哪些窗口属于"广告可能待的地方"：
     *   - 根节点包名就是目标应用；
     *   - 根节点包名读不到、但类型是普通应用窗口（自绘广告窗口的常见形态）。
     *
     * 系统窗口（状态栏/输入法/系统弹窗，type≥2）一律排除：它们的进出与广告无关
     * （真机日志里成功那轮恰好同时出现过状态栏窗口，不排除就会把噪声当证据）。
     */
    private fun isRelevantWindow(window: WindowFingerprint, targetPkg: String): Boolean =
        when {
            window.rootPackage == targetPkg -> true
            window.rootPackage == null && window.type == TYPE_APPLICATION -> true
            else -> false
        }
}
