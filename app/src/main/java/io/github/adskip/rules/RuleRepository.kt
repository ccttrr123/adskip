package io.github.adskip.rules

import android.content.Context
import io.github.adskip.engine.RuleOverrides
import io.github.adskip.engine.RuleSet
import io.github.adskip.util.DebugLog
import io.github.adskip.util.RulePrefs

/**
 * 规则库读取。
 *
 * M1 只从内置 assets 读取；M4 会在这里加上"远程热更新 + 签名校验"，
 * 因此这里已经是唯一的规则来源入口，替换实现不影响其它代码。
 */
class RuleRepository(private val context: Context) {

    fun load(): RuleSet = try {
        val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val parsed = RuleSetParser.parse(json)
        // 叠加用户开关：内置规则是默认值，用户的选择是覆盖层
        parsed.copy(rules = RuleOverrides.apply(parsed.rules, RulePrefs.disabled(context)))
    } catch (t: Throwable) {
        DebugLog.w("规则库加载失败，本次以空规则运行：${t.message}")
        RuleSet()
    }

    companion object {
        const val ASSET_NAME = "rules.json"
    }
}
