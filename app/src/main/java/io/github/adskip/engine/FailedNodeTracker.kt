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

    /**
     * 当前应当被拉黑的节点键。
     *
     * **2026-11-10 修（24 天真实数据驱动）**：原来这里用 `ttlMs` 同时做两件事 ——
     * 判"是否还拉黑"和"删掉计数"。于是**只要两次使用间隔超过 TTL，计数就被清空**，
     * 黑名单在这类低频应用上**永远从头开始**✗。
     * 真机证据：志愿汇 24 天里 11 次广告 → **50 次点击 / 41 次失败**（4.5 次/广告），
     * 而它约 1.5 次/天启动 —— 30 分钟的 TTL 每次都先过期 ✓。
     *
     * 现在的语义：
     *  - 计数**只由"成功"清除**（`recordSuccess`），不因过期而丢 ✓
     *  - 拉黑时长**按失败次数分级**：3~5 次 30 分钟 / 6~9 次 6 小时 / ≥10 次 24 小时 ✓
     *    → 死节点大约每天只被白点一次，而正常按钮最多 30 分钟就会重试（不会永久误伤 ✓）
     *  - 只有"30 天都没再失败"的记录才会被忘掉（防 map 无限增长 ✓）
     */
    fun blacklist(now: Long): Set<String> {
        failures.entries.removeAll { now - it.value.lastFailAt > FORGET_MS }
        return failures.filterValues {
            it.count >= threshold && now - it.lastFailAt <= ttlFor(it.count)
        }.keys.toSet()
    }

    /** 失败越多，拉黑越久（但都有上限，不会变成永久判决 ✓） */
    private fun ttlFor(count: Int): Long = when {
        count >= 10 -> MAX_TTL_MS
        count >= 6 -> MID_TTL_MS
        else -> ttlMs
    }

    fun snapshot(): Map<String, Entry> = failures.toMap()

    fun restore(data: Map<String, Entry>) {
        failures.clear()
        failures.putAll(data)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 3
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L
        /** 失败 6~9 次：拉黑 6 小时 */
        const val MID_TTL_MS = 6 * 60 * 60 * 1000L
        /** 失败 ≥10 次：拉黑 24 小时 */
        const val MAX_TTL_MS = 24 * 60 * 60 * 1000L
        /** 30 天没再失败就彻底忘掉（防 map 无限增长） */
        const val FORGET_MS = 30L * 24 * 60 * 60 * 1000L
    }
}