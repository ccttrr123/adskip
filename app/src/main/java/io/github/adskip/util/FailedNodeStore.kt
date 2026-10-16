package io.github.adskip.util

import android.content.Context
import android.os.SystemClock
import io.github.adskip.engine.FailedNodeTracker

/**
 * 把"屡次失败的节点"落到本地。
 *
 * 刻意做了持久化：这台手机上服务进程经常被系统回收，
 * 只存内存等于每次进程重启就全部忘掉，那这个机制就白做了。
 * 写入只发生在**点击失败**时（很少），不构成耗电负担。
 */
object FailedNodeStore {

    private const val PREF = "adskip_failed_nodes"
    private const val KEY = "v1"

    @Volatile
    private var cached: FailedNodeTracker? = null

    private fun tracker(context: Context): FailedNodeTracker {
        cached?.let { return it }
        val tracker = FailedNodeTracker()
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
        if (!raw.isNullOrBlank()) tracker.restore(decode(raw))
        return tracker.also { cached = it }
    }

    fun recordFailure(context: Context, key: String) {
        val tracker = tracker(context)
        tracker.recordFailure(key, SystemClock.elapsedRealtime())
        persist(context, tracker)
    }

    fun recordSuccess(context: Context, key: String) {
        val tracker = tracker(context)
        tracker.recordSuccess(key)
        persist(context, tracker)
    }

    fun blacklist(context: Context): Set<String> =
        tracker(context).blacklist(SystemClock.elapsedRealtime())

    private fun persist(context: Context, tracker: FailedNodeTracker) {
        val raw = tracker.snapshot().entries.joinToString(";") {
            "${it.key}=${it.value.count},${it.value.lastFailAt}"
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }

    /** 节点键里可能含逗号和 @，所以按**最后一个** = 切分，左边整体是键 */
    private fun decode(raw: String): Map<String, FailedNodeTracker.Entry> = buildMap {
        raw.split(';').forEach { part ->
            val eq = part.lastIndexOf('=')
            if (eq <= 0) return@forEach
            val values = part.substring(eq + 1).split(',')
            if (values.size != 2) return@forEach
            val count = values[0].toIntOrNull() ?: return@forEach
            val at = values[1].toLongOrNull() ?: return@forEach
            put(part.substring(0, eq), FailedNodeTracker.Entry(count, at))
        }
    }
}