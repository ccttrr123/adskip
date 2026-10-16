package io.github.adskip.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.adskip.engine.UiNode
import java.util.ArrayDeque

/**
 * 把 AccessibilityNodeInfo 树读成扁平的 UiNode 列表。
 *
 * 宽度优先 + 节点数上限：广告 SDK 的界面层级可能很深（尤其是 WebView），
 * 不设上限会在低端机上造成明显的卡顿。
 */
object NodeReader {

    private const val DEFAULT_LIMIT = 400
    private const val MAX_VISIT_FACTOR = 4

    fun read(root: AccessibilityNodeInfo?, limit: Int = DEFAULT_LIMIT): List<UiNode> {
        if (root == null) return emptyList()

        val out = ArrayList<UiNode>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var visited = 0
        val maxVisit = limit * MAX_VISIT_FACTOR

        while (queue.isNotEmpty() && out.size < limit && visited < maxVisit) {
            val node = queue.poll() ?: break
            visited++
            out.add(node.toUiNode())
            val count = node.childCount
            for (i in 0 until count) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    /**
     * 按屏幕位置找回节点，并向上找到真正可点击的祖先。
     *
     * 用途是"注入手势失败时的退化方案"：跳过按钮本身往往不可点击，
     * 真正接收点击的是外层的容器。
     */
    fun findClickableByBounds(root: AccessibilityNodeInfo?, target: UiNode): AccessibilityNodeInfo? {
        if (root == null) return null

        val rect = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var visited = 0
        while (queue.isNotEmpty() && visited < 2000) {
            val node = queue.poll() ?: break
            visited++
            node.getBoundsInScreen(rect)
            if (rect.left == target.left && rect.top == target.top &&
                rect.right == target.right && rect.bottom == target.bottom
            ) {
                var candidate: AccessibilityNodeInfo? = node
                var depth = 0
                while (candidate != null && !candidate.isClickable && depth < 6) {
                    candidate = candidate.parent
                    depth++
                }
                if (candidate?.isClickable == true) return candidate
            }
            val count = node.childCount
            for (i in 0 until count) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun AccessibilityNodeInfo.toUiNode(): UiNode {
        val rect = Rect()
        getBoundsInScreen(rect)
        return UiNode(
            text = text?.toString().orEmpty(),
            contentDescription = contentDescription?.toString().orEmpty(),
            viewId = viewIdResourceName.orEmpty(),
            className = className?.toString().orEmpty(),
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            clickable = isClickable
        )
    }
}
