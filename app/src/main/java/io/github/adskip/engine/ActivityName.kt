package io.github.adskip.engine

/**
 * 判断 WINDOW_STATE_CHANGED 事件里的 className 是否真的**是 Activity 名**。
 *
 * 为什么需要它（真机发现）：我们原来无条件把事件的 className 当成 Activity 名，
 * 但在 爱奇艺 上抓到的却是 `android.widget.FrameLayout` —— 弹窗/浮层类窗口的
 * className 是**控件类名**，不是 Activity。
 *
 * 后果很实际：`activity_hint` 这条旁证要求 Activity 名里含 Splash/Ad/Welcome 之类的关键字，
 * 而我们喂给它的却是 `FrameLayout`，于是这条旁证**永远是废的**，
 * 日志里也只能看到 `当前 Activity=android.widget.FrameLayout` 这种没用的信息。
 *
 * 判断刻意用**前缀**而不是包含：`org.qiyi.android.video.MainActivity` 里虽然含
 * "android."，但它是应用自己的 Activity，不能被误杀。
 */
object ActivityName {

    /** 这些前缀下的类都是框架/库内部的，不可能是应用的 Activity */
    private val FRAMEWORK_PREFIXES = listOf(
        "android.",
        "androidx.",
        "com.android.internal.",
        "com.android.server.",
        "java.",
        "kotlin."
    )

    /** 以这些结尾的基本都是控件而不是 Activity */
    private val WIDGET_SUFFIXES = listOf(
        "Layout",
        "View",
        "ViewGroup",
        "PopupWindow",
        "DecorView",
        "SurfaceView",
        "TextureView"
    )

    fun isActivityLike(className: String?): Boolean {
        val name = className?.trim().orEmpty()
        if (name.isEmpty()) return false

        // 不带包名的类名不可能是 Activity 的全限定名
        if (!name.contains('.')) return false

        if (FRAMEWORK_PREFIXES.any { name.startsWith(it) }) return false

        // 内部类/匿名类（监听器、适配器之类）不是 Activity 本身
        if (name.contains('$')) return false

        if (WIDGET_SUFFIXES.any { name.endsWith(it) }) return false

        return true
    }

    /**
     * 从一次事件里提取可用于 activity_hint 匹配的 Activity 名。
     * @return 合法时返回类名，否则返回 null（调用方应保留上一次的合法值）
     */
    fun extractOrNull(className: String?): String? =
        className?.trim()?.takeIf { isActivityLike(it) }
}
