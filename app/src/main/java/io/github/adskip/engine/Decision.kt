package io.github.adskip.engine

/**
 * 判定结果。引擎只负责"决定做什么"，不负责执行 —— 执行需要 Android API，
 * 这个边界让 decision 逻辑可测、执行逻辑可替换。
 *
 * Wait 和 TapNode 都带上了候选节点列表，目的很实际：
 * 真机上"为什么没跳过""为什么点到别的地方了"只能靠这些信息回答。
 */
sealed interface Decision {

    /** 该包没有规则，或规则被禁用，完全不介入 */
    object Ignore : Decision

    /**
     * 已超出时间窗，本次启动不再介入。
     *
     * 单独拆出来是为了能在日志里说清"为什么没动"——
     * 之前它和 Ignore 共用一条静默路径，导致"打开应用完全没有日志"无法排查。
     */
    data class Expired(val ageMs: Long, val windowMs: Long) : Decision

    /** 命中熔断条件，本次启动不再介入 */
    data class Abort(val reason: String) : Decision

    /**
     * 找到带文本特征的跳过节点，点击它的中心。
     *
     * @param rejected 同样命中文本、但被安全规则否决的候选，仅用于日志诊断
     */
    data class TapNode(
        val node: UiNode,
        val reason: String,
        val rejected: List<UiNode> = emptyList()
    ) : Decision

    /** 盲点坐标（依赖旁证解锁） */
    data class TapPoint(val x: Int, val y: Int, val reason: String) : Decision

    /** 返回键兜底 */
    data class GlobalBack(val reason: String) : Decision

    /**
     * 命中规则、在时间窗内，但暂时没有可执行的目标（例如界面还在加载）。
     *
     * @param candidates 命中了文本、但被安全规则否决的候选；为空表示压根没有候选
     */
    data class Wait(
        val reason: String,
        val candidates: List<UiNode> = emptyList()
    ) : Decision
}
