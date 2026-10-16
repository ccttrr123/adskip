package io.github.adskip.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * 手势注入。
 *
 * 这是整个方案能覆盖"自绘跳过按钮"的关键：Canvas 画出来的按钮在节点树里
 * 没有文字，只有注入一次真实触摸才能点到它。
 */
class GestureTapper(private val service: AccessibilityService) {

    fun tap(x: Int, y: Int, durationMs: Long = DEFAULT_DURATION_MS): Boolean {
        if (x <= 0 || y <= 0) return false
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            // 部分定制 ROM 会在特定状态下禁用注入，失败就让调用方退化为 ACTION_CLICK
            false
        }
    }

    private companion object {
        /** 太短可能被识别成滑动，太长会阻塞后续事件 */
        const val DEFAULT_DURATION_MS = 40L
    }
}
