package io.github.adskip.util

import android.content.Context

/**
 * 规则开关的持久化（只存"被关掉的应用"）。
 *
 * revision 的作用：无障碍服务在内存里持有已加载的规则，
 * 用户改了开关之后必须让它知道该重新加载。服务在每个应用切换时读一次 revision
 * （一次 SharedPreferences 读，开销可忽略），变了才重新解析规则库。
 */
object RulePrefs {

    private const val PREF = "adskip_rules"
    private const val KEY_DISABLED = "disabled_v1"
    private const val KEY_REVISION = "revision"

    fun disabled(context: Context): Set<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_DISABLED, emptySet())
            ?.toSet()
            ?: emptySet()

    fun setEnabled(context: Context, pkg: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_DISABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) current.remove(pkg) else current.add(pkg)
        prefs.edit()
            .putStringSet(KEY_DISABLED, current)
            .putInt(KEY_REVISION, prefs.getInt(KEY_REVISION, 0) + 1)
            .apply()
    }

    fun revision(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_REVISION, 0)
}
