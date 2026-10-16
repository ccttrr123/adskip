package io.github.adskip.engine

/**
 * 跳过按钮的文本识别。
 *
 * 刻意不把裸的"关闭"放进默认特征集：正常界面里"关闭"按钮太多，
 * 只有规则明确写了才匹配。默认集只收录本身语义就足够明确的词。
 */
object SkipTextMatcher {

    /** 默认文本特征，规则里 patterns 留空时使用 */
    val DEFAULT_PATTERNS: List<String> = listOf(
        "跳过",
        "跳过广告",
        "点击跳过",
        "关闭广告",
        "skip",
        "skip ad"
    )

    /**
     * 形如以下都能识别出剩余秒数：
     *   "跳过 3"、"跳过3s"、"3 跳过"、"跳过 3 秒"、"Skip 5"、"5s Skip"
     * 返回 null 表示不是倒计时文案。
     *
     * 两个数字位置都必须可选，否则"3 跳过"（数字在前）匹配不上：
     * 词前那个组是可选的，所以"跳过 3"能过，而词后那个组如果写成必填，
     * 就会让"3 跳过"整条匹配失败。这个 bug 是被单元测试抓出来的。
     */
    private val COUNTDOWN = Regex(
        """^\s*(?:(\d{1,2})\s*[sS]?\s*秒?\s*)?(跳过|跳过广告|关闭广告|skip(?:\s*ad)?)(?:\s*[（(]?\s*(\d{1,2})\s*[sS]?\s*秒?\s*[)）]?)?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** 纯倒计时数字 + 单位，例如 "3s"、"3 秒"，部分 SDK 把倒计时和"跳过"拆成两个节点 */
    private val COUNTDOWN_ONLY = Regex(
        """^\s*(\d{1,2})\s*[sS秒]\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** 规范化：去掉空白、统一全角括号 */
    private fun normalize(raw: String): String = raw
        .trim()
        .replace('\u00A0', ' ')
        .replace('\u3000', ' ')
        .trim()

    /**
     * 该节点文本是否像"跳过"按钮。
     *
     * 命中条件：包含任一特征词（忽略大小写），或者是倒计时文案
     * （倒计时文案本身就说明这里是开屏广告的跳过位）。
     */
    fun isSkipLabel(raw: String?, patterns: List<String> = DEFAULT_PATTERNS): Boolean {
        if (raw.isNullOrBlank()) return false
        val label = normalize(raw)
        if (label.isEmpty()) return false

        if (countdownSeconds(label) != null) return true

        return patterns.any { p ->
            p.isNotBlank() && label.contains(p.trim(), ignoreCase = true)
        }
    }

    /**
     * 从文案里解析剩余秒数。
     * 返回 null 表示不是倒计时文案；返回 0 表示文案形如"跳过"而无秒数。
     */
    fun countdownSeconds(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val label = normalize(raw)

        COUNTDOWN.matchEntire(label)?.let { m ->
            val before = m.groupValues[1]
            val after = m.groupValues[3]
            val n = when {
                before.isNotEmpty() -> before
                after.isNotEmpty() -> after
                else -> return 0
            }
            return n.toIntOrNull()
        }

        COUNTDOWN_ONLY.matchEntire(label)?.let { m ->
            return m.groupValues[1].toIntOrNull()
        }

        return null
    }
}
