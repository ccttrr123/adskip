package io.github.adskip.engine

/**
 * 某一时刻界面的快照，是判定引擎的唯一输入。
 *
 * @param firstSeenAgeMs 该包名被观察到进入前台至今的毫秒数
 * @param tapsThisLaunch 本次启动已经注入过的点击次数，用于限制盲目重试
 */
data class UiSnapshot(
    val pkg: String,
    val activityName: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val firstSeenAgeMs: Long = 0,
    val tapsThisLaunch: Int = 0,
    /**
     * 本次启动里"点过但没生效"的节点键。
     * 引擎会把它们从候选里剔除 —— 志愿汇 就是被一个永远点不动的节点
     * 把每次启动的 3 次点击预算全部吃光，导致真按钮轮不到。
     */
    val failedNodeKeys: Set<String> = emptySet(),
    val nodes: List<UiNode> = emptyList()
) {
    fun textOfAllNodes(): String = nodes.joinToString(" ") { it.label() }
}
