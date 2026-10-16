package io.github.adskip.engine

/**
 * 与 Android 无关的界面节点抽象。
 *
 * 把 AccessibilityNodeInfo 抽象成这个纯数据类，是为了让判定逻辑可以完全脱离
 * 设备与 Android 运行时做单元测试 —— 这个项目最难验证的部分恰恰是"判定准不准"，
 * 而它必须在没有真机的情况下也能被测试覆盖。
 */
data class UiNode(
    val text: String = "",
    val contentDescription: String = "",
    val viewId: String = "",
    val className: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()

    /** 节点上可用于匹配的全部可见文本 */
    fun label(): String = when {
        text.isNotBlank() -> text
        contentDescription.isNotBlank() -> contentDescription
        else -> ""
    }

    fun isEditText(): Boolean = className.contains("EditText", ignoreCase = true)

    /**
     * 本次启动内识别"同一个节点"的键。
     *
     * **必须把位置算进去。** 第一版我只用 view id，结果引入了回归：
     * 志愿汇 那个节点有"未落位 (0,0,210,88)"和"落位后 (0,70,210,158)"两种状态，
     * 只按 id 做键会把两者当成同一个 —— 于是"未落位时点失败"会把"落位后的重试"也排除掉，
     * 而落位后的那一下恰恰是以前能成功的位置。所以键 = 身份 + 量化到 50px 网格的中心：
     */
    fun nodeKey(): String =
        "${if (viewId.isNotBlank()) viewId else label()}@${centerX / 50},${centerY / 50}"

    /**
     * 单行描述，用于日志诊断。
     * 真机上判断"这个节点到底是什么"只能靠这些字段，因此刻意带上类名与 view id。
     */
    fun describe(): String {
        val simpleClass = className.substringAfterLast('.').ifEmpty { className }
        val id = viewId.substringAfterLast('/').ifEmpty { "-" }
        return "「${label().take(20)}」$simpleClass#${id}@(${left},${top},${right},${bottom})" +
            if (clickable) "[可点]" else ""
    }
}
