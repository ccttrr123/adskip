package io.github.adskip.util

import android.os.SystemClock
import io.github.adskip.engine.HeartbeatPolicy
import io.github.adskip.engine.HeartbeatState
import io.github.adskip.engine.ServiceHealth

/**
 * 服务心跳 —— **纯内存，零磁盘 IO**。
 *
 * 界面和无障碍服务跑在同一个进程里，所以一个内存变量就够了：
 * 服务每 30 秒更新一次时间戳，界面直接读。
 * 不写盘、不轮询、不新增唤醒、不持 wakelock —— 耗电可以忽略。
 *
 * 注意这是**故意**不做持久化的：进程被系统杀掉后这个值就归零，
 * 而"归零"正是我们想知道的信号（服务确实不在了）。写盘反而会让它变成一个旧值，
 * 平白多出一次 IO 还更容易骗人。
 *
 * 状态机本身在 `engine/HeartbeatState`（纯逻辑、可测试）；这里只是把它接到 Android 的时钟上。
 */
object ServiceHeartbeat {

    private val state = HeartbeatState()

    private fun now(): Long = SystemClock.elapsedRealtime()

    /**
     * 服务调用：报一次"我还在"。
     *
     * **同时会把"连着"置真** —— 因为调用方只有在通过存活核验之后才会报心跳，
     * 所以这一次上报本身就是"我连着"的证据。这样即使核验**瞬时失败**把标志清掉，
     * 下一次核验成功就能自愈，不必等系统重绑（详见 `HeartbeatState.beat` 的注释）。
     */
    fun beat() {
        state.beat(now())
    }

    /** 服务连上时调用（同时立刻报一次心跳，不等 30 秒） */
    fun onServiceConnected() {
        state.connect(now())
    }

    /** 服务断开/被停用时调用。进程被杀时这个值随进程一起消失，也是正确的结果 */
    fun onServiceDisconnected() {
        state.disconnect()
    }

    fun isConnected(): Boolean = state.isConnected()

    /** 服务从没上报过返回 -1 */
    fun ageMs(): Long = state.ageMs(now())

    fun health(): ServiceHealth = state.health(now())

    /** 供单元测试与诊断使用 */
    fun lastAliveAt(): Long = state.lastAliveAt()
}
