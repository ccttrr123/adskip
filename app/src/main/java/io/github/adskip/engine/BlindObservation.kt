package io.github.adskip.engine

/**
 * 盲点（`node == null`）校验用的"某一时刻能观察到什么"。
 *
 * 为什么需要它：自绘广告根本不在无障碍节点树里，点击后想判断"广告到底关没关"，
 * 只能看**窗口层面的变化**（点击时还在的目标应用窗口是否消失），而不是"还有没有跳过字样"。
 * 那棵树里读到的是广告底下的页面，跳过的按钮/字样与它无关。
 *
 * **刻意不包含 Activity 名**：真机上它不可靠 —— 真机复验里抓到过一次
 * `MainActivity → LaunchActivity` 的假成功（新一次启动的 WINDOW_STATE_CHANGED 事件比
 * 0ms 的盲点晚到，`currentActivity` 还留着上一次启动的 Activity 名）。
 *
 * **activePackage 只用于判断"读没读到"与调用方的跨应用守卫**：判定入口要求根节点包名是
 * 目标应用才允许出手，所以点击前的包名必然是目标包（或读不到）；能观察到的包名变化只剩
 * "读不到 → 读到"这种可读性变化，不能当"广告关掉"的证据（见 [BlindVerifier]）。
 *
 * 纯数据、无 Android 依赖，因此判定逻辑可以完全脱离设备做单元测试。
 *
 * @param activePackage `rootInActiveWindow` 的包名；读不到（root 为 null 或没有包名）时为 null
 * @param windows 当前无障碍能看到的窗口指纹；**读不到时为 null**（与"读到了但是空列表"不同）
 */
data class BlindObservation(
    val activePackage: String?,
    val windows: List<WindowFingerprint>?
) {
    /** 两个信号都读不到：这时任何成败结论都是猜的，调用方应"不记结论" */
    val unreadable: Boolean get() = activePackage == null && windows == null

    /** 单行诊断描述，真机上"为什么判不出来"只能靠它 */
    fun describe(): String {
        val pkg = activePackage ?: "读不到"
        val wins = windows?.joinToString("；") { it.describe() } ?: "读不到"
        return "activePkg=$pkg｜windows=[$wins]"
    }
}

/**
 * 一个无障碍窗口的指纹。
 *
 * 只保留与"广告窗口还在不在"相关的字段：**不看标题**（自绘窗口标题往往是空/无意义的），
 * 用 id + 类型 + 层 + 是否激活/聚焦 + 该窗口根节点的包名来识别。
 * 判定实际只比较 id/类型（见 [BlindVerifier.relevantWindowKeys]），
 * 包名只用于过滤窗口，其余字段留作日志诊断。
 */
data class WindowFingerprint(
    val id: Int,
    val type: Int,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    /** 该窗口根节点的包名；窗口存在但根读不到时为 null */
    val rootPackage: String?,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    fun describe(): String {
        val marks = buildString {
            if (active) append('A')
            if (focused) append('F')
            if (isEmpty()) append('-')
        }
        return "id=$id t=$type l=$layer $marks pkg=${rootPackage ?: "?"}" +
            " [$left,$top,$right,$bottom]"
    }
}
