package io.github.adskip.engine

/**
 * 单个应用的跳过年统计。
 *
 * 存在的理由很直接：M1 只做日志，导致"成功率"这个数字**根本无法测量** ——
 * 所有结论都是人工跑 adb 一轮轮测出来的，而那种测法有三个偏差：
 * 广告有频次限制（冷启动 4 次可能只出 1 次广告）、只覆盖冷启动、
 * 而且只测了用户指定的那几个应用。
 * 没有这个数字，M1→M2 的取舍就只能靠感觉。
 */
data class AppStat(
    val launches: Int = 0,
    val clicks: Int = 0,
    val verifiedOk: Int = 0,
    val verifiedFail: Int = 0,
    val suspectedMisclick: Int = 0,
    /**
     * "遇到广告"的启动次数：本次启动里**第一次看到可疑跳过目标**时记一次。
     *
     * 它是成功率的分母。之前用"点击次数"当分母（每次成功常伴随一次失败试探 → 系统性偏低），
     * 但也不能用"启动次数"：真机上 B站 一天切到前台 100 次却只弹了十来次广告，
     * 用启动数当分母会算成 7/100，比原来更离谱。
     */
    val adsSeen: Int = 0
) {
    /** 有校验结论的尝试次数（校验中断的不算） */
    val decided: Int get() = verifiedOk + verifiedFail

    /** 成功率百分比；没有已决样本时返回 null（不能拿 0 次当 0%） */
    /**
     * 成功率 = 成功 ÷ **遇到广告的启动次数**。
     * 没有遇到过广告时返回 null（显示"-"，而不是拿 0 次当 0%）。
     */
    val successRate: Int? get() =
        if (adsSeen <= 0) null else (verifiedOk * 100 / adsSeen).coerceAtMost(100)
}

/**
 * 统计报表的纯逻辑部分。刻意不含 Android：这样成功率计算和报表格式都能单元测试。
 */
object StatsReport {

    /**
     * 生成可直接粘贴给人看的报表。
     *
     * 格式刻意做成"一行一个应用、列对齐"，这样粘到聊天里也不会散。
     */
    fun build(
        stats: Map<String, AppStat>,
        nameOf: (String) -> String,
        sessionMinutes: Long
    ): String {
        val rows = stats.entries
            .filter { it.value.launches > 0 || it.value.clicks > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<String, AppStat>> { it.value.clicks }
                    .thenBy { nameOf(it.key) }
            )

        if (rows.isEmpty()) return "（还没有统计数据：打开一个有开屏广告的应用试试）"

        // 统计是跨会话累计的，"运行 0 分钟"会让人误以为这些数字只有几分钟的样本，
        // 所以把"累计"和"本界面打开时长"分开写清楚
        val sb = StringBuilder()
        sb.append("广告跳过统计（本机累计；本界面已打开 ").append(sessionMinutes).append(" 分钟）\n")
        // 表头也用 pad() 生成，而不是写死空格 —— 写死的话改了列宽就会和行错位
        sb.append(pad("应用", 14)).append(pad("启动", 5)).append(pad("有广告", 7))
            .append(pad("点击", 6)).append(pad("成功", 6)).append(pad("失败", 6)).append(pad("误点", 6))
            .append("成功率\n")

        var totalLaunches = 0
        var totalClicks = 0
        var totalOk = 0
        var totalFail = 0
        var totalMis = 0

        for ((pkg, s) in rows) {
            totalLaunches += s.launches
            totalClicks += s.clicks
            totalOk += s.verifiedOk
            totalFail += s.verifiedFail
            totalMis += s.suspectedMisclick

            sb.append(pad(nameOf(pkg), 14))
                .append(pad(s.launches.toString(), 5))
                .append(pad(s.adsSeen.toString(), 7))
                .append(pad(s.clicks.toString(), 6))
                .append(pad(s.verifiedOk.toString(), 6))
                .append(pad(s.verifiedFail.toString(), 6))
                .append(pad(s.suspectedMisclick.toString(), 6))
                .append(s.successRate?.let { "$it%" } ?: "-")
                .append('\n')
        }

        val totalAds = stats.values.sumOf { it.adsSeen }
        val overall = if (totalAds == 0) "-" else "${(totalOk * 100 / totalAds).coerceAtMost(100)}%"
        sb.append(pad("合计", 14))
            .append(pad(totalLaunches.toString(), 5))
            .append(pad(totalAds.toString(), 7))
            .append(pad(totalClicks.toString(), 6))
            .append(pad(totalOk.toString(), 6))
            .append(pad(totalFail.toString(), 6))
            .append(pad(totalMis.toString(), 6))
            .append(overall)

        return sb.toString()
    }

    /**
     * 按显示宽度补齐。中文按两格算，否则中英混排的表格会歪。
     */
    private fun pad(text: String, width: Int): String {
        var display = 0
        for (ch in text) display += if (ch.code > 0x2E7F) 2 else 1
        if (display >= width) return "$text "
        return text + " ".repeat(width - display)
    }
}

/**
 * 统计的本地持久化编码。
 *
 * 刻意不用 Gson：格式就这么简单，自己写反而更稳 ——
 * 不怕以后字段重命名导致旧数据整段读不出来，也省一次反射。
 * 放在 engine 层是为了能脱离 Android 做单元测试（真机上的统计丢过一次就很难发现）。
 */
/**
 * 判断"这一回算不算一次新的启动"。
 *
 * 为什么需要去抖：服务的启动埋点挂在"前台包名发生变化"上，而包名变化会被
 * 系统弹窗、输入法、悬浮窗打断。真机上就出现了"百度贴吧 7 次启动但只点过 1 次" ——
 * 一批测试里每个应用明明只启动了一次。
 * 虚高的启动数会直接污染 M1.5 用来决定要不要进 M2 的判断依据，所以必须按包名去抖。
 */
class LaunchRecorder(private val debounceMs: Long = DEFAULT_DEBOUNCE_MS) {

    private val lastAt = mutableMapOf<String, Long>()

    /**
     * 每个包"最近这次观察有没有被计成一次新启动"。
     *
     * 这是给 [acceptsAd] 用的。为什么不能直接比较 `lastAt` 里的时间戳：
     * [shouldRecord] 在**接受**时会顺手把 `lastAt[pkg]` 更新成 now，
     * 于是同一次观察里 `now - lastAt[pkg]` 恰好等于 0 —— 永远落在去抖窗口内。
     * （这不是推理，是实测：用时间戳比较的版本会把 5 次观察全判成"可以记广告"。）
     */
    private val countedLaunch = mutableMapOf<String, Boolean>()

    /** @return true 表示应当记为一次新启动（同一个包在去抖窗口内重复出现不算） */
    fun shouldRecord(pkg: String, nowMs: Long): Boolean {
        val last = lastAt[pkg]
        if (last != null && nowMs - last < debounceMs) {
            countedLaunch[pkg] = false
            return false
        }
        lastAt[pkg] = nowMs
        countedLaunch[pkg] = true
        return true
    }

    /**
     * @return true 表示"这次看到的广告"可以计入 adsSeen。
     *
     * 为什么需要它（为什么调用方不能自己判断）：
     * 服务里的"是新启动"信号来自 `LaunchTracker.observe()`（按**包名变化**判定），
     * 而统计层还有一道**时间**去抖。A →（系统弹窗/输入法抢过前台）→ A 会同时产生
     * "包名变了（=新启动）"和"距上次不足 10 秒（=不算新启动）"两个结论，
     * 启动数被丢掉、广告数却记了上去 —— 真机上就量到 `微博 启动=1 有广告=2`。
     * 分母（adsSeen）虚高会把成功率压低，是会误导 M1→M2 取舍的错误。
     *
     * 判据是 [shouldRecord] 刚刚留下的结论：**只有被计数的那次启动才有资格记广告**。
     * 不能在这里比较时间戳（见 [countedLaunch] 的注释）。
     */
    fun acceptsAd(pkg: String, nowMs: Long): Boolean {
        // nowMs 不参与判断，但保留在签名里：语义上它表示"广告是在哪个时刻看到的"
        @Suppress("UNUSED_PARAMETER")
        val unused = nowMs
        return countedLaunch[pkg] == true
    }

    fun reset() {
        lastAt.clear()
        countedLaunch.clear()
    }

    companion object {
        /** 窗口比探测时间窗（5 秒）大一倍，足够跨过弹窗造成的抖动 */
        const val DEFAULT_DEBOUNCE_MS = 10_000L
    }
}

object StatCodec {

    fun encode(map: Map<String, AppStat>): String =
        map.entries.joinToString("\n") { (pkg, s) ->
            "$pkg|${s.launches}|${s.clicks}|${s.verifiedOk}|${s.verifiedFail}|${s.suspectedMisclick}|${s.adsSeen}"
        }

    /** 坏行直接丢弃，不让一条脏数据毁掉整份统计 */
    fun decode(raw: String): Map<String, AppStat> {
        val out = mutableMapOf<String, AppStat>()
        raw.lineSequence().forEach { line ->
            val f = line.split('|')
            if (f.size < 6 || f[0].isBlank()) return@forEach
            val nums = f.drop(1).mapNotNull { it.toIntOrNull() }
            if (nums.size < 5) return@forEach
            // 旧数据只有 5 个数字（没有 adsSeen），缺失时按 0 处理，不丢历史统计
            out[f[0]] = AppStat(nums[0], nums[1], nums[2], nums[3], nums[4], nums.getOrElse(5) { 0 })
        }
        return out
    }
}
