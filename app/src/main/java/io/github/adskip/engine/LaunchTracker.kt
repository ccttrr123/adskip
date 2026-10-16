package io.github.adskip.engine

/**
 * 追踪前台包名的切换，为"进入前台后多少毫秒"提供依据。
 *
 * 纯逻辑、时间由外部注入，因此可以完全脱离设备做单元测试。
 *
 * 设计要点：只在包名**发生变化**时重置起点。
 * 不能用"前台停留过久就重置"这种写法 —— 那会让时间窗永远开着，
 * 而时间窗恰恰是这个项目最重要的安全阀。
 */
class LaunchTracker {

    private var currentPkg: String? = null
    private var firstSeenAt: Long = 0L
    private var taps: Int = 0

    /**
     * 记录一次前台观察。
     * 注意：调用方必须对**所有**包名调用本方法（包括无规则的包），
     * 否则 A → 桌面 → A 这种"离开又回来"无法被识别成一次新启动。
     *
     * @return true 表示这是一次新的启动（包名发生了变化）。
     *         调用方需要据此清空"本次启动"范围内的状态，例如已成功处理的包、已打过的日志。
     */
    fun observe(foregroundPkg: String, now: Long): Boolean {
        if (currentPkg != foregroundPkg) {
            currentPkg = foregroundPkg
            firstSeenAt = now
            taps = 0
            return true
        }
        return false
    }

    fun currentPkg(): String? = currentPkg

    /** 当前包名进入前台至今的毫秒数 */
    fun ageMs(now: Long): Long = if (currentPkg == null) 0L else now - firstSeenAt

    fun tapsThisLaunch(): Int = taps

    /** 记录一次已注入的点击，返回本次启动累计次数 */
    fun recordTap(): Int {
        taps++
        return taps
    }

    fun reset() {
        currentPkg = null
        firstSeenAt = 0L
        taps = 0
    }
}
