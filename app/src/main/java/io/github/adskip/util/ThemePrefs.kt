package io.github.adskip.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 夜间模式偏好的持久化与应用。
 *
 * 三种标准选项：跟随系统 / 浅色 / 深色。
 * 应用方式用 AppCompatDelegate.setDefaultNightMode —— 它会自己重建界面，
 * 所以改完不需要手动重启应用。
 */
object ThemePrefs {

    private const val PREF = "adskip_theme"
    private const val KEY = "mode"

    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    fun mode(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, FOLLOW_SYSTEM)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply()
        apply(mode)
    }

    fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}