package io.github.adskip.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import io.github.adskip.engine.BlindObservation
import io.github.adskip.engine.WindowFingerprint

/**
 * 把 Android 的窗口状态读成 [BlindObservation]（纯数据）。
 *
 * 读失败不抛异常、也不编造：读不到的字段保持 null，由判定层把它当作"不知道"。
 * 这正是这次改动要守住的边界 —— 盲点路径最危险的 bug 不是判错方向，
 * 而是**拿底层的页面冒充广告的状态**，把一个明确的"成功"记成失败。
 */
object BlindObservationReader {

    fun read(service: AccessibilityService): BlindObservation {
        // 1) 前台窗口的包名。root 为 null 是已知的正常失败模式（部分机型的开屏窗口读不到）
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        val activePackage = root?.packageName?.toString()

        // 2) 无障碍能看到的窗口指纹。整次读取失败或读回空列表 -> null（读不到），不是空列表：
        //    空列表意味着"一个窗口都没读到"，把它当"读到了、只是没有窗口"会得出错误结论。
        val windows: List<WindowFingerprint>? = runCatching {
            service.windows?.map { window ->
                val rect = Rect()
                runCatching { window.getBoundsInScreen(rect) }
                val windowRoot = runCatching { window.root }.getOrNull()
                WindowFingerprint(
                    id = window.id,
                    type = window.type,
                    layer = window.layer,
                    active = window.isActive,
                    focused = window.isFocused,
                    rootPackage = windowRoot?.packageName?.toString(),
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom
                )
            }?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        return BlindObservation(
            activePackage = activePackage,
            windows = windows
        )
    }
}
