package io.github.adskip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.res.Configuration
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import io.github.adskip.databinding.ActivityMainBinding
import io.github.adskip.databinding.ItemRuleBinding
import io.github.adskip.engine.AppStat
import io.github.adskip.engine.RuleSet
import io.github.adskip.engine.StatsReport
import io.github.adskip.rules.RuleRepository
import io.github.adskip.service.AdSkipService
import io.github.adskip.util.DebugLog
import io.github.adskip.util.RulePrefs
import io.github.adskip.engine.HeartbeatPolicy
import io.github.adskip.engine.ServiceHealth
import io.github.adskip.engine.ServiceVerdict
import io.github.adskip.util.ServiceHeartbeat
import io.github.adskip.util.StatsStore
import io.github.adskip.util.ThemePrefs
import kotlin.math.roundToInt

/**
 * 状态 / 统计 / 规则 / 日志四个分区。
 *
 * 版面上做的两处改动是有原因的：
 *   - **规则库默认折叠**：现在有 18 个应用，展开会把页面撑到要翻好几屏，
 *     而它是最不常用的信息（只有排查某个应用时才需要看）
 *   - **日志限高 + 内部滚动**：以前日志跟着内容无限变长，一旦刷起来
 *     上面的状态和统计就得翻很久才看得到
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var ruleSet: RuleSet = RuleSet()
    private var rulesExpanded = false

    private val logListener: (String) -> Unit = {
        renderLog()
        renderStats()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35 起系统强制"边到边"，内容会画到状态栏底下 —— 时间和图标压住标题，
        // 看起来就是"上方看不清"。这里用 insets 给根布局留出上下安全区。
        // 比在 XML 里写 fitsSystemWindows 可靠（NestedScrollView 上它常被忽略）。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 底部留安全区（手势条）
            view.setPadding(0, 0, 0, bars.bottom)

            // 顶部留白加在**滚动内容**上，而不是根布局上 —— 关键区别：
            //   · 加在根布局上：内容滚到该边界就被硬切，上面露白带（上一版的毛病）
            //   · 加在内容上：留白随内容一起滚走，往下滚时内容自然会淡入遮罩 ✓
            // 留白高度取"遮罩高度"而不是状态栏高度：否则静止时第一行标题正好落在遮罩里被淡掉。
            // 直接读遮罩的实际高度，避免这里和布局里各写一个数字、将来改一处忘一处
            val fadePx = binding.topFade.layoutParams.height
            (binding.mainScroll.getChildAt(0) as? View)?.let { content ->
                content.setPadding(content.paddingLeft, fadePx, content.paddingRight, content.paddingBottom)
            }
            insets
        }

        // 状态栏/导航栏的图标颜色必须跟随主题。
        // 浅色主题下系统默认画**白色**图标，而我们的背景是浅色 ——
        // 结果就是时间、信号、电量跟背景糊在一起看不清。
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        // 夜间模式：先把持久化的偏好应用上去，再根据当前值点亮对应按钮。
        // setDefaultNightMode 会自己重建界面，所以切换后无需手动重启
        val themeMode = ThemePrefs.mode(this)
        ThemePrefs.apply(themeMode)
        val initialThemeId = when (themeMode) {
            ThemePrefs.LIGHT -> R.id.themeLight
            ThemePrefs.DARK -> R.id.themeDark
            else -> R.id.themeFollow
        }
        // 三个独立按钮，互斥由这里控制（不用 MaterialButtonToggleGroup，
        // 因为它会连成一条带外框的分段控件，和别的卡片按钮长得不一样）
        styleThemeButtons(initialThemeId)
        listOf(binding.themeFollow, binding.themeLight, binding.themeDark).forEach { button ->
            button.setOnClickListener { view ->
                val id = view.id
                styleThemeButtons(id)
                ThemePrefs.setMode(
                    this,
                    when (id) {
                        R.id.themeLight -> ThemePrefs.LIGHT
                        R.id.themeDark -> ThemePrefs.DARK
                        else -> ThemePrefs.FOLLOW_SYSTEM
                    }
                )
            }
        }

        // 一键复制日志：真机排查全靠它（手机 logcat 缓冲区一两分钟就被系统日志冲光）
        binding.copyLogButton.setOnClickListener {
            val log = binding.logText.text?.toString().orEmpty()
            if (log.isBlank()) {
                toast(getString(R.string.nothing_to_copy))
            } else {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("adskip-log", log))
                toast(getString(R.string.log_copied))
            }
        }

        StatsStore.load(this)
        ruleSet = RuleRepository(this).load()

        binding.openSettingsButton.setOnClickListener { openAccessibilitySettings() }
        binding.copyAdbButton.setOnClickListener { copyAdbCommand() }
        binding.clearLogButton.setOnClickListener {
            DebugLog.clear()
            renderLog()
        }
        binding.copyStatsButton.setOnClickListener { copyStats() }
        binding.resetStatsButton.setOnClickListener {
            StatsStore.reset()
            renderStats()
            toast(getString(R.string.stats_reset))
        }
        binding.toggleRulesButton.setOnClickListener {
            rulesExpanded = !rulesExpanded
            renderRules()
        }

        renderRules()
        DebugLog.addListener(logListener)
        renderStatus()
        renderStats()
        renderLog()
        startHeartbeatTicker()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderStats()
        renderLog()
        startHeartbeatTicker()
    }

    override fun onPause() {
        // 后台前把统计落盘，避免最后几个计数丢掉
        StatsStore.persistNow()
        stopHeartbeatTicker()
        super.onPause()
    }

    override fun onDestroy() {
        DebugLog.removeListener(logListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------ 渲染

    private fun renderStatus() {
        val enabled = isServiceEnabled()
        binding.statusTitle.text = if (enabled) {
            getString(R.string.app_name) + " 正在工作"
        } else {
            "服务未开启"
        }
        binding.statusDetail.text = if (enabled) {
            "已接管 ${ruleSet.rules.count { it.enabled }} 个应用的开屏广告"
        } else {
            "开启后才会开始工作"
        }
        binding.hintText.visibility = if (enabled) View.GONE else View.VISIBLE
        renderHeartbeat()
    }

    /**
     * 让夜间模式按钮的**选中态**和「打开无障碍设置」一致（实心主色），
     * **未选中态**和「复制统计」一致（描边）。
     *
     * MaterialButtonToggleGroup 默认不会自动在两种外观间切换，所以要自己按
     * 选中 id 刷一遍；颜色从主题解析，因此深色模式下也会自动跟着变。
     */
    private fun styleThemeButtons(checkedId: Int) {
        val primary = themeColor(com.google.android.material.R.attr.colorPrimary, 0xFF6750A4.toInt())
        val onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF.toInt())
        val strokePx = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        listOf(
            binding.themeFollow to R.id.themeFollow,
            binding.themeLight to R.id.themeLight,
            binding.themeDark to R.id.themeDark
        ).forEach { (button, id) ->
            if (id == checkedId) {
                button.backgroundTintList = ColorStateList.valueOf(primary)
                button.setTextColor(onPrimary)
                button.strokeWidth = 0
            } else {
                button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                button.setTextColor(primary)
                button.strokeWidth = strokePx
            }
        }
    }

    /** 从当前主题解析颜色；解析不到就用兜底色 */
    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    /**
     * 心跳提示。
     *
     * renderStatus 只看设置里的"已启用"字符串 —— 服务被系统回收之后那个字符串**仍然是"已启用"**，
     * 于是界面显示"正在工作"，而那是在骗人（真机验证过：enabled=1、服务名在列表里，
     * 但 Bound services:{} 且进程不存在）。
     * 这里用心跳把真相盖上去。
     *
     * 判据是**两个条件都要满足**（2026-10-17 改）：
     *   - `isConnected()`：服务实例报"我在"（精确、不受休眠影响）
     *   - 心跳还新鲜（`ageMs` 在阈值内）：兜住"标志为真但服务其实已经没了"
     *
     * 为什么不能只看 `isConnected()`：这个布尔量一旦被置假就可能**恢复不了**。
     * 真机上撞到过 —— ColorOS 频繁触发 `onUnbind`，标志被置假，而服务其实一直在工作、
     * 心跳一直在报，界面却死咬「⚠ 服务已停止」并每隔几秒弹一次 Toast。
     * 反过来也不能只看心跳新鲜度：服务进程还活着但实例已销毁时，定时器可能还在报，
     * 单看时间会误报"正在工作"。两个都看，任一条不成立才敢说停了。
     */
    private fun renderHeartbeat() {
        if (headlineColor == 0) headlineColor = binding.statusTitle.currentTextColor

        if (!isServiceEnabled()) {
            binding.statusTitle.setTextColor(headlineColor)
            binding.statusDetail.setTextColor(headlineColor)
            return
        }

        val verdict = HeartbeatPolicy.classifyService(
            connected = ServiceHeartbeat.isConnected(),
            ageMs = ServiceHeartbeat.ageMs(),
            uptimeMs = uptimeMs(),
            graceMs = HEARTBEAT_GRACE_MS
        )
        when (verdict) {
            // 刚打开界面时系统可能还在绑定服务，先不下结论
            ServiceVerdict.CHECKING -> binding.statusDetail.text = "正在检查服务状态…"

            ServiceVerdict.STOPPED -> showServiceStopped()

            ServiceVerdict.RUNNING -> {
                // 时间戳只在新鲜时展示：休眠期间定时器会被系统推迟，
                // 拿一个不新鲜的秒数去吓唬人没有意义（连着就是连着）
                binding.statusTitle.setTextColor(headlineColor)
                binding.statusDetail.setTextColor(headlineColor)
                val age = ServiceHeartbeat.ageMs()
                val suffix = "（最后活跃 ${HeartbeatPolicy.describeAge(age)}）"
                binding.statusDetail.text =
                    "已接管 ${ruleSet.rules.count { it.enabled }} 个应用的开屏广告$suffix"
            }
        }
    }

    private fun showServiceStopped() {
        val age = ServiceHeartbeat.ageMs()
        binding.statusTitle.setTextColor(headlineWarning)
        binding.statusTitle.text = "⚠ 服务已停止"
        binding.statusDetail.setTextColor(headlineWarning)
        binding.statusDetail.text = (if (age >= 0) "最后活跃 ${HeartbeatPolicy.describeAge(age)}。" else "") +
            "请到「无障碍」里把「广告跳过」关掉再打开"
    }

    private fun uptimeMs(): Long = android.os.SystemClock.elapsedRealtime() - createdAt

    /**
     * 界面可见时每 5 秒刷一次心跳显示：
     * 既能保持"最后活跃 X 秒前"是新的，也能在服务中途被杀时当场变红。
     * 纯内存读取，不产生 IO。
     */
    private fun startHeartbeatTicker() {
        heartbeatHandler.removeCallbacks(heartbeatTicker)
        heartbeatHandler.post(heartbeatTicker)
    }

    private fun stopHeartbeatTicker() {
        heartbeatHandler.removeCallbacks(heartbeatTicker)
    }

    /**
     * 把统计画成**真表格**：表头行 + 每个应用一行 + 合计行。
     *
     * 为什么不再用一个等宽 TextView（原先的做法）：monospace **不含中文字形**，
     * 中文会回退到比例字体，于是"中文占 2 个拉丁字符宽"这个前提不成立 ——
     * `StatsReport.pad()` 已经按显示宽度补齐了，画面照样是歪的。算法修不了，只能换渲染方式。
     * 这里每列一格 View + layout_weight，列边界由布局算，与字体无关。
     *
     * 「复制统计」走的仍然是 [StatsReport.build]（纯文本，粘到聊天里也不会散），两者互不影响。
     */
    private fun renderStats() {
        val stats = StatsStore.snapshot()
        val rows = stats.entries
            .filter { it.value.launches > 0 || it.value.clicks > 0 }
            // 有广告的排前面 → 再有广告数、成功数、点击数
            .sortedWith(
                compareByDescending<Map.Entry<String, AppStat>> { it.value.adsSeen > 0 }
                    .thenByDescending { it.value.adsSeen }
                    .thenByDescending { it.value.verifiedOk }
                    .thenByDescending { it.value.clicks }
                    .thenBy { displayName(it.key) }
            )

        binding.statsEmptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.statsTable.removeAllViews()
        if (rows.isEmpty()) return

        val table = binding.statsTable
        addStatsRow(
            table,
            cells = listOf("应用", "启动", "有广告", "点击", "成功", "失败", "误点", "成功率"),
            header = true
        )
        addStatsDivider(table)

        rows.forEach { (pkg, stat) ->
            addStatsRow(
                table,
                cells = listOf(
                    displayName(pkg),
                    stat.launches.toString(),
                    stat.adsSeen.toString(),
                    stat.clicks.toString(),
                    stat.verifiedOk.toString(),
                    stat.verifiedFail.toString(),
                    stat.suspectedMisclick.toString(),
                    stat.successRate?.let { "$it%" } ?: "-"
                )
            )
        }

        // 合计口径与 StatsReport 保持一致：有广告数把**所有**应用加起来，
        // 而不只是表里这几行 —— 否则两处的合计会对不上，说不清哪个是真的
        val totalAds = stats.values.sumOf { it.adsSeen }
        val totalOk = stats.values.sumOf { it.verifiedOk }
        val overall = if (totalAds == 0) "-" else "${(totalOk * 100 / totalAds).coerceAtMost(100)}%"
        addStatsDivider(table)
        addStatsRow(
            table,
            cells = listOf(
                "合计",
                stats.values.sumOf { it.launches }.toString(),
                totalAds.toString(),
                stats.values.sumOf { it.clicks }.toString(),
                totalOk.toString(),
                stats.values.sumOf { it.verifiedFail }.toString(),
                stats.values.sumOf { it.suspectedMisclick }.toString(),
                overall
            ),
            header = true
        )
    }

    /**
     * 画一行统计。
     *
     * 列宽按 [STATS_WEIGHTS] 分配，数字一律**右对齐**（表格里数字的惯例，
     * 也让"位数不同的数字"之间的比较不费眼），应用名左对齐。
     *
     * 每格左右各留 [STATS_CELL_PAD_DP]：不留的话表头会顶满整列 ——
     * 真机上"启动有广告"会糊成一个词（表头 3 个汉字比数字还宽，列宽其实是被表头定死的）。
     *
     * 应用名限一行 + 末尾省略：名字长的应用（比如"掌上无畏契约"）宁可截断，
     * 也不能换行 —— 一换行整行的行高就不一样了，表格立刻又变得难读。
     */
    private fun addStatsRow(
        table: LinearLayout,
        cells: List<String>,
        header: Boolean = false
    ) {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            // 表头与合计行上下各留多一点：让它们和中间的数据行分开
            // （真正把它们切开的是 [addStatsDivider] 那条线，这里只是配合）
            setPadding(0, dp(if (header) 6f else 4f), 0, dp(if (header) 6f else 4f))
        }

        cells.forEachIndexed { index, text ->
            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { weight = STATS_WEIGHTS[index] }
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (header) STATS_HEADER_SP else STATS_TEXT_SP)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = if (index == 0) Gravity.START else Gravity.END
                setPadding(dp(STATS_CELL_PAD_DP), 0, dp(STATS_CELL_PAD_DP), 0)
                setLineSpacing(0f, STATS_LINE_SPACING)
                if (header) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        table.addView(row)
    }

    /**
     * 表头下、合计上各一条细分隔线。
     *
     * 不直接用 `?attr/colorOutlineVariant` —— 实测它在这个主题里解析成 0（全透明），
     * 等于画了个看不见的 View。所以显式给色：浅色用中性灰，深色由 `values-night` 覆盖。
     */
    private fun addStatsDivider(table: LinearLayout) {
        table.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1f)
            )
            setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.stats_divider)
            )
        })
    }

    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    /**
     * 规则库渲染成"一行一个开关"。
     *
     * 没有用 RecyclerView：这里最多几十行，而且整个页面本身在外层 NestedScrollView 里，
     * 再嵌一个 RecyclerView 又要处理嵌套滚动和 wrap_content 测量，
     * 得不偿失。直接往 LinearLayout 里塞行反而更简单、更稳。
     */
    private fun renderRules() {
        binding.toggleRulesButton.setText(if (rulesExpanded) R.string.collapse else R.string.expand)
        val visible = if (rulesExpanded) View.VISIBLE else View.GONE
        binding.rulesContainer.visibility = visible
        binding.rulesHint.visibility = visible
        binding.rulesTitle.text = getString(R.string.section_rules) + "（${ruleSet.rules.size} 条）"

        if (!rulesExpanded) return
        if (binding.rulesContainer.childCount > 0) return

        ruleSet.rules.forEach { rule ->
            val row = ItemRuleBinding.inflate(layoutInflater, binding.rulesContainer, false)
            row.ruleName.text = rule.name ?: rule.pkg
            row.rulePkg.text = rule.pkg
            row.ruleSwitch.isChecked = rule.enabled

            // 内置的示例规则不参与用户开关：它带着盲点坐标和返回键兜底，
            // 万一被误开就会在真实设备上乱点
            if (rule.pkg == DEMO_PKG) {
                row.ruleSwitch.isEnabled = false
                row.ruleName.text = (rule.name ?: rule.pkg) + "（示例，不可开启）"
            } else {
                row.ruleSwitch.setOnCheckedChangeListener { _, checked ->
                    RulePrefs.setEnabled(this, rule.pkg, checked)
                    reloadRules()
                }
            }
            binding.rulesContainer.addView(row.root)
        }
    }

    /** 开关变更后重新加载规则，并按新的启用状态刷新界面 */
    private fun reloadRules() {
        ruleSet = RuleRepository(this).load()
        binding.rulesContainer.removeAllViews()
        renderRules()
        renderStatus()
        renderStats()
        toast("改动会在你下次打开该应用时生效")
    }

    private fun renderLog() {
        val lines = DebugLog.snapshot()
        binding.logText.text = if (lines.isEmpty()) {
            "(暂无日志。开启服务后打开一个有开屏广告的应用，这里会显示判定过程)"
        } else {
            lines.takeLast(MAX_LOG_LINES).joinToString("\n")
        }
        // 新日志追加后滚到底部，否则看到的永远是最早那几行
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun displayName(pkg: String): String =
        ruleSet.rules.firstOrNull { it.pkg == pkg }?.name ?: pkg

    // ------------------------------------------------------------ 授权

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast("无法打开系统设置，请手动进入：设置 → 无障碍") }
    }

    /**
     * 通过 Settings.Secure 判断服务是否启用。
     * 不用 AccessibilityManager.getEnabledAccessibilityServiceList：那个方法在新版本上被标记废弃，
     * 而读 secure 设置没有版本差异，也不需要任何权限。
     */
    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/", ignoreCase = true) }
    }

    /**
     * Android 13+ 对侧载应用的无障碍开关有"受限设置"限制。
     * 这里给懂的用户一条命令行通路，同时必须如实警告它会覆盖已有列表。
     */
    private fun copyAdbCommand() {
        val component = "$packageName/${AdSkipService::class.java.name}"
        val text = buildString {
            appendLine("# 广告跳过：一次性授权（需要电脑 + USB 调试）")
            appendLine("# 作用：绕过 Android 13+ 对侧载应用的「受限设置」限制")
            appendLine()
            appendLine("adb shell settings put secure enabled_accessibility_services $component")
            appendLine("adb shell settings put secure accessibility_enabled 1")
            appendLine()
            appendLine("# ⚠️ 第一条命令会【覆盖】系统里已有的无障碍服务列表。")
            appendLine("# 如果你已经在使用其它无障碍类应用（如 TalkBack、李跳跳），")
            appendLine("# 请把它的服务名一并写进去，用英文冒号分隔，例如：")
            appendLine("#   enabled_accessibility_services 已有服务包名/服务类名:$component")
        }
        clipboard("adskip-adb", text)
        toast("已复制。注意：该命令会覆盖已有无障碍服务列表")
    }

    // ------------------------------------------------------------ 统计导出

    private fun copyStats() {
        val stats = StatsStore.snapshot()
        if (stats.isEmpty()) {
            toast(getString(R.string.nothing_to_copy))
            return
        }
        clipboard(
            "adskip-stats",
            StatsReport.build(stats, ::displayName, StatsStore.sessionMinutes())
        )
        toast(getString(R.string.stats_copied))
    }

    private fun clipboard(label: String, text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        if (cm == null) {
            toast("剪贴板不可用")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private val createdAt = android.os.SystemClock.elapsedRealtime()
    private var headlineColor = 0
    private val headlineWarning = 0xFFD32F2F.toInt()
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatTicker = object : Runnable {
        override fun run() {
            renderHeartbeat()
            heartbeatHandler.postDelayed(this, HEARTBEAT_REFRESH_MS)
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 150
        const val DEMO_PKG = "com.example.demo"
        const val HEARTBEAT_REFRESH_MS = 5_000L
        const val HEARTBEAT_GRACE_MS = 4_000L

        /**
         * 统计表 8 列的宽度比例，顺序与表头一致：
         * 应用 / 启动 / 有广告 / 点击 / 成功 / 失败 / 误点 / 成功率。
         *
         * 数字列给 1.4（不是更窄）：表头最宽的"有广告/成功率"是 3 个汉字，
         * **表头比数字宽**，所以列宽其实是被表头定死的（[STATS_HEADER_SP] 下约 32dp）。
         * 名字列 3.2 够放最长应用名"掌上无畏契约"（约 78dp，实际 107dp）。
         */
        val STATS_WEIGHTS = floatArrayOf(3.2f, 1.4f, 1.4f, 1.4f, 1.4f, 1.4f, 1.4f, 1.4f)

        /** 数字 10sp；表头 9sp —— 表头密度高，小一档才留得出列间空隙（真机上看不出发虚） */
        const val STATS_TEXT_SP = 10f
        const val STATS_HEADER_SP = 9f

        /** 每格左右留白：表头汉字之间就靠它分开，不留会糊成"启动有广告" */
        const val STATS_CELL_PAD_DP = 2f

        /** 行高倍率：单行文本只影响行高，1.0 太挤、1.2 又松得和字号不搭 */
        const val STATS_LINE_SPACING = 1.15f
    }
}
