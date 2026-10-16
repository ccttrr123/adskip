package io.github.adskip.util

import android.content.Context
import io.github.adskip.engine.AppStat
import io.github.adskip.engine.LaunchRecorder
import io.github.adskip.engine.StatCodec
import java.util.concurrent.ConcurrentHashMap

/**
 * 统计存储：内存里聚合，落到 SharedPreferences，**绝不上传**。
 *
 * 不上传是硬约束而不是选项：这个项目走 F-Droid 分发，
 * 而且它本身需要"读取屏幕内容"这种高敏感权限 —— 一旦有上报能力，
 * 用户就没有理由相信它。所以这里只做本地计数，导出靠用户手动复制。
 */
object StatsStore {

    private const val PREF = "adskip_stats"
    private const val KEY = "stats_v1"

    private val stats = ConcurrentHashMap<String, AppStat>()
    private val launchRecorder = LaunchRecorder()

    private var appContext: Context? = null
    private var sessionStartMs: Long = System.currentTimeMillis()

    /**
     * 记录一次启动（仅针对规则库里的应用）。
     * 经过 LaunchRecorder 去抖，避免系统弹窗导致的包名抖动把启动数刷高。
     *
     * @return true 表示这次真的计入了启动数。不被计入时（10 秒去抖窗口内）
     *         本次遇到广告也**不许**计入 adsSeen —— 否则成功率的分母会虚高，见 [recordAdSeen]
     */
    fun recordLaunch(pkg: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!launchRecorder.shouldRecord(pkg, nowMs)) return false
        update(pkg) { it.copy(launches = it.launches + 1) }
        return true
    }

    /** 记录一次点击注入 */
    fun recordClick(pkg: String) = update(pkg) { it.copy(clicks = it.clicks + 1) }

    /**
     * 本次启动第一次看到可疑跳过目标 —— 成功率的分母（每次启动最多记一次）。
     *
     * 只在**这次启动被计数**时才允许 +1。理由：真机上量到过 `微博 启动=1 有广告=2`，
     * 就是 A →（系统弹窗抢前台）→ A 造成的：探测层认为"新启动"、统计层却被去抖丢掉，
     * 于是广告计数比启动计数还多。分母虚高会把成功率压低，这是**会误导 M1→M2 取舍**的错误。
     */
    fun recordAdSeen(pkg: String, nowMs: Long = System.currentTimeMillis()) {
        if (!launchRecorder.acceptsAd(pkg, nowMs)) return
        update(pkg) { it.copy(adsSeen = it.adsSeen + 1) }
    }

    /** 记录一次校验结论 */
    fun recordVerified(pkg: String, ok: Boolean) = update(pkg) {
        if (ok) it.copy(verifiedOk = it.verifiedOk + 1) else it.copy(verifiedFail = it.verifiedFail + 1)
    }

    /** 记录一次疑似误点（前台跳到了别的包） */
    fun recordMisclick(pkg: String) = update(pkg) {
        it.copy(suspectedMisclick = it.suspectedMisclick + 1)
    }

    fun snapshot(): Map<String, AppStat> = HashMap(stats)

    fun sessionMinutes(): Long =
        ((System.currentTimeMillis() - sessionStartMs) / 60_000L).coerceAtLeast(0L)

    fun reset() {
        stats.clear()
        launchRecorder.reset()
        appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        sessionStartMs = System.currentTimeMillis()
    }

    /** 从磁盘恢复。服务启动时调一次即可 */
    fun load(context: Context) {
        appContext = context.applicationContext
        if (stats.isNotEmpty()) return
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        StatCodec.decode(raw).forEach { (pkg, stat) -> stats[pkg] = stat }
        sessionStartMs = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------- 内部

    private fun update(pkg: String, transform: (AppStat) -> AppStat) {
        stats[pkg] = transform(stats[pkg] ?: AppStat())
        persistDebounced()
    }

    private var lastPersistAt = 0L

    /**
     * 防抖写入：内容变化事件密集时不可能每次都落盘。
     * 2 秒一次足够（统计丢几秒无所谓），但能避免在主线程上频繁写文件。
     */
    private fun persistDebounced() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < 2_000L) return
        lastPersistAt = now
        persistNow()
    }

    /** 服务断开、界面暂停时调一次，确保最后几个计数不丢 */
    fun persistNow() {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, StatCodec.encode(stats))
                .apply()
        }
    }
}
