package io.github.adskip.engine

/**
 * **跨启动**记住"屡次点不动的节点"。
 *
 * 动机（真机数据）：志愿汇 屏上有个 `tvSkip` 节点，今天失败了十几次，
 * 但**每次启动它都还会被点第一下**（因为"点过没用"的记忆原本只保留在单次启动内）。
 * 结果是每次启动都白送一次点击预算，真按钮出现晚一点就没机会了 ——
 * 三次失败日志的共同模式都是"1 个死节点 + 2 次盲点，2.5 秒内预算打光"。
 *
 * 设计上的克制：
 *  - 必须**连续失败到阈值**才拉黑（默认 3 次），偶发一次失败不算
 *  - 拉黑有**有效期**（默认 30 分钟），不是永久判决
 *  - 一旦该节点成功过，立刻恢复
 */
class FailedNodeTracker(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {

    data class Entry(val count: Int, val lastFailAt: Long)

    private val failures = mutableMapOf<String, Entry>()

    fun recordFailure(key: String, now: Long) {
        if (key.isBlank()) return
        val previous = failures[key]
        failures[key] = Entry((previous?.count ?: 0) + 1, now)
    }

    fun recordSuccess(key: String) {
        failures.remove(key)
    }

    /** 当前应当被拉黑的节点键；顺带清理过期记录 */
    fun blacklist(now: Long): Set<String> {
        failures.entries.removeAll { now - it.value.lastFailAt > ttlMs }
        return failures.filterValues { it.count >= threshold }.keys.toSet()
    }

    fun snapshot(): Map<String, Entry> = failures.toMap()

    fun restore(data: Map<String, Entry>) {
        failures.clear()
        failures.putAll(data)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 3
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L
    }
}