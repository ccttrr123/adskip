package io.github.adskip.engine

/** 服务健康状态 */
enum class ServiceHealth { ALIVE, STALE, UNKNOWN }

/**
 * 界面最终该显示哪种状态。
 *
 * 从 `MainActivity.renderHeartbeat()` 里抽出来的：那里原来是个纯布尔判断，
 * 没法单元测试，于是"标志一旦置假就再也说不出'正在工作'"这个缺陷只能靠真机撞见。
 */
enum class ServiceVerdict { RUNNING, STOPPED, CHECKING }

/**
 * 心跳策略 —— 纯逻辑，不依赖 Android，可单元测试。
 *
 * 为什么需要心跳：无障碍服务被系统回收后，
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里那个字符串**仍然是"已启用"**，
 * 界面据此显示"正在工作"，而实际上它早就停了。
 *
 * 真机验证过这个谎言：`enabled=1`、服务名在列表里，但 `Bound services:{}`、进程不存在。
 * 今天一整天有三次"看起来坏了"，查下来都是服务根本没在运行 —— 而界面一直显示正常。
 */
object HeartbeatPolicy {

    /** 服务每隔多久报一次"我还在" */
    const val INTERVAL_MS = 30_000L

    /**
     * 多久没心跳就算掉线。
     * 取 2 分钟而不是 2 个心跳周期（60 秒）：手机在低电量/后台受限时
     * 定时器本来就会被系统延迟，阈值太紧会误报"已停止"，反而让人不再信任这个提示。
     */
    const val STALE_MS = 2 * 60 * 1000L

    fun classify(lastAliveAt: Long, now: Long, staleMs: Long = STALE_MS): ServiceHealth = when {
        lastAliveAt <= 0L -> ServiceHealth.UNKNOWN
        now - lastAliveAt >= staleMs -> ServiceHealth.STALE
        else -> ServiceHealth.ALIVE
    }

    /**
     * 界面该怎么显示。
     *
     * **两个条件都要满足**才算"正在工作"：
     *   - [connected]：服务实例报"我在"（精确、不受休眠影响）
     *   - 心跳还新鲜：兜住"标志为真但服务其实已经没了"
     *
     * 为什么不能只看 [connected]：它一旦被置假就可能**恢复不了**。2026-10-17 在 ColorOS 上撞到过 ——
     * 系统频繁触发 `onUnbind`（两分钟 12 次重新绑定），标志被置假，而服务一直在工作、心跳一直在报，
     * 界面却死咬"⚠ 服务已停止"，还每隔几秒弹一次 Toast。反过来也不能只看心跳新鲜度：
     * 服务实例已销毁但定时器仍在报时，光看时间会误报"正在工作"。
     *
     * @param ageMs 距上次心跳的毫秒数；从没上报过传负数
     * @param graceMs 界面刚打开时的宽限期：系统可能还在绑定服务，先不下结论
     */
    fun classifyService(
        connected: Boolean,
        ageMs: Long,
        uptimeMs: Long,
        graceMs: Long,
        staleMs: Long = STALE_MS
    ): ServiceVerdict {
        val fresh = ageMs >= 0L && ageMs < staleMs
        val alive = connected && fresh
        if (alive) return ServiceVerdict.RUNNING
        return if (uptimeMs < graceMs) ServiceVerdict.CHECKING else ServiceVerdict.STOPPED
    }

    /** 把"最后活跃多久前"变成人话 */
    fun describeAge(ageMs: Long): String = when {
        ageMs < 0 -> "从未上报"
        ageMs < 60_000L -> "${ageMs / 1000} 秒前"
        ageMs < 3_600_000L -> "${ageMs / 60_000} 分钟前"
        else -> "${ageMs / 3_600_000L} 小时前"
    }
}
