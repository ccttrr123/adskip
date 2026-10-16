package io.github.adskip.engine

/**
 * 心跳的内存状态机（纯逻辑，不依赖 Android，可单元测试）。
 *
 * 为什么从 `util/ServiceHeartbeat` 里抽出来：那边直接依赖 `android.os.SystemClock`，
 * 于是"存活核验**瞬时失败**之后还能不能自愈"这类状态迁移**根本没法测** ——
 * 而 2026-10-17 的真机 bug 恰好就出在这类迁移上（标志被清掉后再也回不来）。
 */
class HeartbeatState {

    @Volatile
    private var connected = false

    @Volatile
    private var lastAliveAt = 0L

    /**
     * 报一次"我还在"。
     *
     * **同时把"连着"置真** —— 这一点是刻意的：调用方只有在**通过了存活核验**之后才会报心跳
     * （服务里那一跳先问系统"我还在已启用列表里吗"，见 `AdSkipService.isStillEnabled()`），
     * 所以"刚报过心跳"就等价于"刚证明了自己连着"。
     *
     * 为什么必须这么做：那次核验可能**瞬时失败**（抛异常，或系统正在重绑的空档），
     * 此时会调 [disconnect] 把标志清掉。如果 [beat] 不把标志置回来，
     * 界面就会一直停在「⚠ 服务已停止」 ✗ —— 只能等系统下一次重绑
     * （`onServiceConnected`）才自愈，**而重绑频率是系统行为，不该成为正确性的前提**。
     */
    fun beat(now: Long) {
        lastAliveAt = now
        connected = true
    }

    /** 服务连上时调用（同时立刻报一次心跳，不等 30 秒） */
    fun connect(now: Long) {
        beat(now)
    }

    /**
     * 服务断开/被停用时调用。
     * **只清"连着"，保留 [lastAliveAt]** —— 界面因此能同时显示"已停止"和"最后活跃 N 秒前"，
     * 用户看得见它是什么时候走的（这是写手方在真机上特意保留的行为）。
     */
    fun disconnect() {
        connected = false
    }

    fun isConnected(): Boolean = connected

    /** 从没上报过返回 -1 */
    fun ageMs(now: Long): Long = if (lastAliveAt <= 0L) -1L else now - lastAliveAt

    fun health(now: Long): ServiceHealth = HeartbeatPolicy.classify(lastAliveAt, now)

    fun lastAliveAt(): Long = lastAliveAt
}
