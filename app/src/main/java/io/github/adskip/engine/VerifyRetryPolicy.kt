package io.github.adskip.engine

/**
 * 点击后校验时，如果**读不到界面**（`rootInActiveWindow == null`）是否要再试一次。
 *
 * 真机依据（2026-11-18 M2 覆盖矩阵，云运动 2/2 次）：点击「跳过」后正好赶上窗口切换，
 * 1000ms 时 `rootInActiveWindow` 为空，旧逻辑直接"不记成功也不记失败"——
 * 统计里表现为点了却没有任何结论。这种空读通常是瞬时的，400ms 后重读一次就能拿到界面。
 *
 * 只对**文本节点路径**使用（盲点路径有自己的 Unknown 处理，不再重试）。
 * 纯逻辑、无 Android 依赖，方便单测。
 */
object VerifyRetryPolicy {

    /** 最多重试次数：一次就够；再多会把校验拖到用户已经看到下一步界面 */
    const val MAX_RETRIES = 1

    /**
     * @param rootReadable 校验时是否读到了界面
     * @param retryCount 已经重试过几次
     */
    fun shouldRetry(rootReadable: Boolean, retryCount: Int): Boolean =
        !rootReadable && retryCount < MAX_RETRIES
}
