package io.github.adskip.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.adskip.engine.ActivityName
import io.github.adskip.engine.Decision
import io.github.adskip.engine.LaunchTracker
import io.github.adskip.engine.RuleEngine
import io.github.adskip.engine.SkipTextMatcher
import io.github.adskip.engine.UiNode
import io.github.adskip.engine.UiSnapshot
import io.github.adskip.engine.NeverTouch
import io.github.adskip.rules.RuleRepository
import io.github.adskip.util.DebugLog
import io.github.adskip.util.RulePrefs
import io.github.adskip.util.FailedNodeStore
import io.github.adskip.util.ServiceHeartbeat
import io.github.adskip.util.StatsStore

/**
 * 无障碍服务：整个应用的执行层。
 *
 * 它自己不做任何判定，只负责四件事：
 *   1. 把系统事件翻译成 UiSnapshot
 *   2. 把 Decision 执行成真实动作（注入手势 / ACTION_CLICK / 返回键）
 *   3. 执行后校验，识别误点并回退
 *   4. 记录日志，让真机上的行为可观察
 *
 * ## 两道来自真机的关键设计
 *
 * **执行期上锁**：点击之后到校验结果回来之前，禁止对该应用再次判定。
 * 没有这道锁，"异步校验 + 密集事件"就会变成连点：第一次点掉广告后，
 * 后续点击落在广告底下的界面上。真机实测（OPPO Reno 15）里 B站 的表现为跳到"我的"页面 ——
 * 因为底部导航的「我的」正好在广告跳过按钮的同一坐标上。
 *
 * **主动探测**：真机上发现"跳过按钮出现"和"无障碍事件到达"并不同步。
 * 开屏广告多是静态图，几乎不产生内容变化事件，被动等待最快也要等倒计时每秒跳一次 ——
 * 实测 B站 从"未发现"到"已点击"中间空了 342ms，微博给用户的感觉就是"慢"。
 * 所以目标应用一进入前台就启动一个短时探测循环（窗口期内每 100ms 读一次界面），
 * 让点击时机取决于"按钮什么时候真的出现"，而不是"系统什么时候发事件"。
 */
class AdSkipService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val tracker = LaunchTracker()
    private val gestureTapper by lazy { GestureTapper(this) }

    private lateinit var engine: RuleEngine

    /** 已加载的规则版本号，用于感知用户在界面上改过的规则开关 */
    private var loadedRuleRevision = -1

    /**
     * 最近一次采集到的 Activity 名，以及它属于哪个包。
     *
     * 必须把两者绑在一起：只看名字的话，上一个应用残留的名字可能去匹配当前应用的
     * activityHints，从而错误地解锁盲点策略（那等于在错误的界面上盲点）。
     * 但也不能像第一版那样"包名一变就清空" —— 弹窗类窗口会把它冲掉，
     * 之后又没有 WINDOW_STATE_CHANGED 事件来补，结果日志里只剩"未知"。
     */
    private var currentActivity: String = ""
    private var currentActivityPkg: String = ""

    private var lastEvaluateAt = 0L

    /** 上一次真实动作的时间，用于全局冷却 */
    private var lastActionAt = 0L

    /** 已点击但校验还没回来的包：期间一律不再判定。这是"防连点"的核心状态 */
    private var awaitingVerifyFor: String? = null
    private var pendingVerify: Runnable? = null

    /** 探测循环 */
    private var probeLoop: Runnable? = null

    /**
     * 本次启动已经尝试过的点击次数，用来决定"这次用哪种点击机制"。
     *
     * 真机数据：123云盘、志愿汇、KOOK 三个应用匹配到的跳过节点都不是 clickable，
     * 注入手势对它们无效（日志表现为"点击后「跳过」仍存在"）。
     * 而注入手势是自绘按钮的唯一出路，不能放弃。所以改成**换着来**：
     * 奇数次用注入手势，偶数次用真实 view 点击（ACTION_CLICK 到可点击祖先）。
     */
    private var tapAttempts = 0

    /** 本次启动里点过但没生效的节点键；新的一次启动会清空 */
    private val failedNodes = mutableSetOf<String>()

    /** 跨启动拉黑的节点键（连续失败到阈值的），每次启动重新计算 */
    private var blacklistedNodes: Set<String> = emptySet()

    /**
     * 已经记过"遇到广告"的包（每次被计数的启动最多记一次，作为成功率分母）。
     *
     * 用**集合**而不是单个布尔：`dispatch` 是拿 `snapshot.pkg` 去记账的，
     * 而包名可以在一次启动判定之内切换；按包名各记各的，既不会漏记也不会重复记。
     */
    private val adSeenThisLaunch = mutableSetOf<String>()

    /** 心跳定时器：每 30 秒报一次"我还在"（见下面的 heartbeatTick） */
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 心跳：让界面能看出服务是否**真的**在运行（纯内存，零磁盘 IO）。
     *
     * **上报前先向系统核一次"我还挂在那份已启用列表里吗"**（2026-10-17 改）。
     *
     * 原来这里是"定时器一到就报" —— 那**只证明进程和 Handler 还活着**，
     * 证明不了无障碍服务还连着（实例被销毁后如果 `onDestroy` 没走到，
     * 定时器可能还在跑，就会撒谎说"我在"）。核一次之后，谎报的那条路被堵住。
     *
     * 为什么**不**用"能读到 `rootInActiveWindow`"当存活依据（虽然那是更直接的"我还干得了活"）：
     * 读不到窗口是**已知的正常失败模式**（见 evaluateOnce 里 "读不到界面内容" 那条日志，
     * 部分机型的开屏窗口就是读不到），拿它当存活依据会在那种时候把心跳判死 →
     * 界面报"服务已停止" —— 正好把刚修好的假警报又装回去。
     *
     * 核验失败时只清"我在"标志、**保留** `lastAliveAt`：
     * 界面就能同时显示"已停止"和"最后活跃 N 秒前"，用户看得见它是什么时候走的。
     */
    private val heartbeatTick = object : Runnable {
        override fun run() {
            if (isStillEnabled()) {
                ServiceHeartbeat.beat()
            } else {
                ServiceHeartbeat.onServiceDisconnected()
            }
            heartbeatHandler.postDelayed(this, io.github.adskip.engine.HeartbeatPolicy.INTERVAL_MS)
        }
    }

    /**
     * 系统此刻是否仍把我们列为已启用的无障碍服务。
     *
     * 这是**系统**的说法，比 `Settings.Secure` 里那个可能过期的字符串可靠
     * （那个字符串在服务被回收后仍然是"已启用"，界面据此撒谎过一次 —— 见 ServiceHealth 的注释）。
     */
    private fun isStillEnabled(): Boolean = runCatching {
        val manager = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?: return@runCatching false
        manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }.getOrDefault(false)

    /**
     * 服务被系统解绑。
     *
     * **刻意不在这里清"我在"标志**（2026-10-17 修）。
     *
     * 原来这里调 `ServiceHeartbeat.onServiceDisconnected()`，逻辑看着对："解绑了就是停了"。
     * 但这台 ColorOS 上 `onUnbind` 会**脱离服务是否还在工作**地触发 —— 真机证据：
     *   - 两分钟内打了 12 次"服务已连接"（每次都是一次重新绑定）
     *   - 同一时刻 `dumpsys accessibility` 里 `Bound services` 一直在，心跳也一直在报
     *   - 界面却显示「⚠ 服务已停止」，并且每 5 秒弹一次 Toast（每次刷心跳都判一次）
     * 也就是说：标志一旦被 `onUnbind` 置假，在服务其实活得好好的情况下**再也恢复不了**，
     * 于是"精确信号"反过来骗人 —— 比原来"看心跳过时"的判据更糟。
     *
     * 现在的分工：
     *   - `onServiceConnected()` 置真（每次重连都会走到，所以状态能自愈）
     *   - `onDestroy()` 置假（服务真正被销毁时一定会走到）
     *   - 进程被系统杀掉：标志随进程消失，界面靠心跳过时兜底（`HeartbeatPolicy`）
     *
     * 心跳定时器也**不再**在这里停：服务若仍有实例在跑，让它继续报"我在"，
     * 界面就能凭心跳新鲜度判断，而不是被一个置假的布尔量锁死。
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean =
        super.onUnbind(intent)

    /**
     * 各应用上次成功跳过的时间，用于跳过后的冷却。
     *
     * 为什么用**时间**而不是"本次启动"的集合：
     * 一开始我用"前台包名变化 = 新的一次启动"来清空状态，但系统弹窗、输入法、
     * 侧边栏这类临时窗口也会让前台包名来回切换，于是状态被反复清空 ——
     * 日志里同一句"本次启动未发现跳过按钮"每 4 秒重复一次就是证据。
     * 更要命的是：如果这类窗口恰好在点击后插入，清空状态会让引擎再次点击。
     * 改成时间冷却后，行为与包名切换语义完全解耦。
     */
    private val lastSuccessAt = mutableMapOf<String, Long>()

    /** 日志去重：同一结论在冷却期内只记一次，避免内容变化事件刷屏 */
    private val lastLogAt = mutableMapOf<String, Long>()

    /** 误点隔离：同一 App 连续误点达到阈值后本次运行不再介入 */
    private val strikes = mutableMapOf<String, Int>()
    private val quarantined = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo?.apply {
            // 刻意**不**监听 TYPE_WINDOW_CONTENT_CHANGED：它是三类事件里最吵的一类
            // （滚动、动画、文字刷新都会触发），而自从有了主动探测，我们不再靠它拿响应速度。
            // 去掉它之后每个界面变化都不再唤醒本服务，是这项功能里最实在的省电改动。
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            notificationTimeout = NOTIFICATION_TIMEOUT_MS
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        val ruleSet = RuleRepository(this).load()
        engine = RuleEngine(ruleSet.rules)
        loadedRuleRevision = RulePrefs.revision(this)

        // 告诉界面"我在"：这是精确信号，界面据此显示"正在工作"。
        // 立刻报一次心跳（不等 30 秒），之后每 30 秒一次
        ServiceHeartbeat.onServiceConnected()
        heartbeatHandler.removeCallbacks(heartbeatTick)
        heartbeatTick.run()
        StatsStore.load(this)
        DebugLog.i("服务已连接｜规则库 v${ruleSet.version}｜规则数 ${ruleSet.rules.size}")
        if (ruleSet.rules.isEmpty()) {
            DebugLog.w("规则库为空，服务不会做任何事")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return

        val now = SystemClock.uptimeMillis()

        // 必须对所有包名记录（含无规则的包），否则 A→桌面→A 无法被识别为新的一次启动
        if (tracker.observe(pkg, now)) {
            // 上一个包的校验已经失去意义，取消这个锁，避免它一直挂着一个不在前台的包
            awaitingVerifyFor = null
            // 新的一次启动：点击机制的轮换从头开始
            tapAttempts = 0
            failedNodes.clear()
            // 跨启动拉黑屡次失败的节点（否则每次启动都白送一次点击）
            blacklistedNodes = FailedNodeStore.blacklist(this)

            // 注意：**不在这里**清 adSeenThisLaunch。
            //
            // 这里判定为"新启动"的依据只是**包名变化**，而统计层还多一道 10 秒去抖：
            // A →（系统弹窗/输入法抢前台）→ A 会让这里成立、`recordLaunch` 却把它丢掉。
            // 早期版本就是在这里复位标志，结果广告计数比启动计数还多（真机 `微博 启动=1 有广告=2`）。
            // 现在由 `recordLaunch` 的返回值决定（见下面 isTarget 分支）：
            // 只有**真的被计数**的那次启动才开始允许记广告。

            // 目标应用重新进入前台 = 新的一次启动，把上一次的成功冷却清掉。
            //
            // 这一步是被真机数据逼出来的：冷却原本是 10 秒的挂钟计时，
            // 结果下一次启动（相隔约 12 秒）时冷却还没过期，我们被挡住只能干等它结束，
            // 表现为"每次启动都比上次晚约 1 秒才跳过"，点击时倒计时 05→04→03→02→01 一路退。
            // 冷却的本意只是"同一次启动内别重复点"，所以必须跟着启动重置，而不是跟着时间。
            lastSuccessAt.remove(pkg)

            // 用户可能刚改过规则开关：每个应用切换时读一次 revision，变了才重新解析
            reloadRulesIfChanged()

            // 目标应用刚进入前台：重启探测，时间窗从这里算起
            if (::engine.isInitialized && engine.isTarget(pkg)) {
                val counted = StatsStore.recordLaunch(pkg)
                // 被计数 → 这次启动可以记一次广告；被去抖丢掉 → 收回名额，
                // 免得它后面拿着上一次启动留下的资格又记一次广告（正是计数不一致的成因）
                if (counted) adSeenThisLaunch.remove(pkg) else adSeenThisLaunch.add(pkg)
                stopProbeLoop()
                startProbeLoop()
            }
            // 注意：非目标包**不**停止探测循环。
            // 这里原来是 stopProbeLoop()，真机上被证明是错的：
            // 系统弹窗/输入法会短暂抢走"最后一次事件的包名"，循环一停就再也起不来
            // （静态广告期间目标应用长时间不发事件），于是要等好几秒才跳过。
            // 现在循环自己看 rootInActiveWindow 的真实包名，并有绝对时长上限兜底。
        }

        // 只接受"看起来是 Activity"的类名。
        // 弹窗/浮层类窗口上报的是控件类名（真机上抓到过 android.widget.FrameLayout），
        // 拿它去匹配 activity_hint 永远不会命中，日志里也只能看到没用的信息。
        // 遇到非法值时保留上一次的合法值。
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ActivityName.extractOrNull(e.className?.toString())?.let {
                currentActivity = it
                currentActivityPkg = pkg
            }
        }

        if (pkg == packageName) return
        if (!::engine.isInitialized) return
        if (!engine.isTarget(pkg)) return

        // 事件路径同样保留：它能让判定在事件到达时立刻发生，作为探测的补充
        if (now - lastEvaluateAt < EVALUATE_THROTTLE_MS) return
        lastEvaluateAt = now
        evaluateOnce(pkg, now, tracker.ageMs(now))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopProbeLoop()
        pendingVerify?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        tracker.reset()
        StatsStore.persistNow()
        // 服务真正被销毁时才算"我不在了" —— 这里是唯一可靠的停止信号（见 onUnbind 的注释）。
        // 顺序放在最后：先把手上的事收干净，再让界面改口
        ServiceHeartbeat.onServiceDisconnected()
        super.onDestroy()
    }

    /**
     * 记一次"这个节点点了没用"。
     * 同时记进本次启动的黑名单（立刻不再点）和跨启动的统计（屡次失败就长期拉黑）。
     */
    private fun markNodeFailed(node: UiNode?) {
        node ?: return
        val key = node.nodeKey()
        failedNodes += key
        FailedNodeStore.recordFailure(this, key)
    }

    // ------------------------------------------------------------ 规则热切换

    /**
     * 用户改了规则开关就让服务重新加载规则。
     *
     * 只读一次 SharedPreferences 的 int，开销可忽略，但省掉了"改完开关要重启服务"这种体验。
     */
    private fun reloadRulesIfChanged() {
        val revision = RulePrefs.revision(this)
        if (revision == loadedRuleRevision) return
        loadedRuleRevision = revision
        val ruleSet = RuleRepository(this).load()
        engine = RuleEngine(ruleSet.rules)
        DebugLog.i("规则开关有变更，已重新加载｜启用 ${ruleSet.rules.count { it.enabled }} 条")
    }

    // ------------------------------------------------------------ 探测循环

    /**
     * 在目标应用的启动时间窗内周期性判定。
     *
     * 两个关键点，都是被真机数据逼出来的：
     *
     * 1. **看 rootInActiveWindow 的真实包名，而不是"最后一次事件的包名"。**
     *    广告是静态图时目标应用可能长时间不发事件，而系统弹窗会短暂抢走包名；
     *    用事件包名判断会让探测被误停。实测表现为点击时倒计时已经走到「跳过 02」——
     *    也就是广告已经显示了约 3 秒才被点到。
     *
     * 2. **必须有绝对时长上限。** 时间窗是按"包名最后一次变化"算的，
     *    如果包名反复来回切换，这个窗口可能一直不关闭。这里用 MAX_PROBE_DURATION_MS 兜底。
     */
    private fun startProbeLoop() {
        if (probeLoop != null) return

        val startedAt = SystemClock.uptimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                // 关键：如果当前注册的循环不是自己，说明本实例已经被换代了，立刻退出。
                //
                // 没有这一条会形成"孤儿循环"：stopProbeLoop() 清掉字段后新建循环，
                // 而旧循环如果此刻正在执行，它的 removeCallbacks 已经清不掉它，
                // 它会把自己重新排进队列继续跑。真机上的表现是每启动一次就多一个
                // 探测循环，各自每 50~150ms 读一次 400 节点的界面树 ——
                // 主线程越来越堵，点击延迟单调恶化（实测倒计时从「05」一路退到「02」）。
                if (probeLoop !== this) return

                val now = SystemClock.uptimeMillis()
                val age = now - startedAt

                if (age > MAX_PROBE_DURATION_MS) {
                    stopProbeLoop()
                    return
                }

                val root = rootInActiveWindow
                val activePkg = root?.packageName?.toString()

                if (root != null && activePkg != null && ::engine.isInitialized && engine.isTarget(activePkg)) {
                    val rule = engine.ruleFor(activePkg)
                    if (rule != null && age <= rule.windowMs) {
                        evaluateOnce(activePkg, now, age)
                    }
                }

                handler.postDelayed(this, probeIntervalFor(age))
            }
        }

        probeLoop = runnable
        // 先立刻探一次，再按间隔继续。
        // 如果这里用 postDelayed 起步，按钮恰好在重置后 10ms 出现时也要白等一个间隔。
        handler.post(runnable)
    }

    /**
     * 自适应探测间隔。
     *
     * 真机实测（贴吧）：跳过按钮基本上在广告上屏后的第一个 100ms 内就可读，
     * 所以"早期密集、后期稀疏"就够了 —— 广告要在启动后 2~3 秒才出现是常态，
     * 但一旦出现就会立刻被抓住。这样既保持响应速度，又不至于在 5 秒窗口里
     * 一直以 50ms 的频率读界面树（那会产生上百次读树，白白耗电和占用主线程）。
     */
    private fun probeIntervalFor(ageMs: Long): Long =
        if (ageMs < EARLY_PROBE_UNTIL_MS) PROBE_INTERVAL_MS else LATE_PROBE_INTERVAL_MS

    private fun stopProbeLoop() {
        probeLoop?.let { handler.removeCallbacks(it) }
        probeLoop = null
    }

    // ------------------------------------------------------------ 判定入口

    private fun evaluateOnce(pkg: String, now: Long, age: Long) {        // ---------- 四道闸门 ----------
        // 1) 已点击、校验未回来：绝不再点。这是连点问题的根因修复
        if (pkg == awaitingVerifyFor) return
        // 2) 刚刚已经成功跳过这个应用：冷却期内不再介入（时间判定，不受包名来回切换影响）
        if (now - (lastSuccessAt[pkg] ?: 0L) < POST_SUCCESS_COOLDOWN_MS) return
        // 3) 全局动作冷却：防止不同事件路径绕过节流导致高频点击
        if (now - lastActionAt < MIN_ACTION_INTERVAL_MS) return
        if (pkg in quarantined) return

        val rule = engine.ruleFor(pkg) ?: return
        if (age > rule.windowMs) return

        val root = rootInActiveWindow
        if (root == null) {
            // 这条分支原来是静默的，导致"打开应用完全没有日志"无从排查：
            // 目标应用的开屏窗口在部分机型上读不到内容，等能读到时间窗已经过了
            logOnce(pkg, "root-null") {
                "[${tagFor(pkg)}] 读不到界面内容（rootInActiveWindow 为空），本次无法判定"
            }
            return
        }

        // 支付/金融类永不接管：**连界面内容都不读**，不只是不点击。
        // 放在 NodeReader.read 之前，所以这些界面从未进入过我们的进程。
        if (NeverTouch.isBlocked(pkg)) return

        val (w, h) = screenSize()
        val snapshot = UiSnapshot(
            pkg = pkg,
            // 只在名字确实属于这个包时才采用，避免上一个应用的残留名字解锁错误的盲点
            activityName = if (currentActivityPkg == pkg) currentActivity else "",
            screenWidth = w,
            screenHeight = h,
            firstSeenAgeMs = age,
            tapsThisLaunch = tracker.tapsThisLaunch(),
            failedNodeKeys = failedNodes + blacklistedNodes,
            nodes = NodeReader.read(root)
        )

        dispatch(engine.evaluate(snapshot), snapshot)
    }

    // ------------------------------------------------------------ 执行

    private fun dispatch(decision: Decision, snapshot: UiSnapshot) {
        // "遇到广告"：本次启动第一次看到可疑跳过目标时记一次（成功率的分母）。
        // adSeenThisLaunch 的语义是"这个包**这次被计数的启动**已经记过广告了"，
        // 所以集合里没有它才轮到 StatsStore 再核一遍去抖窗口（见 recordAdSeen 的注释）
        val sawAd = decision is Decision.TapNode || decision is Decision.TapPoint ||
            (decision is Decision.Wait && decision.candidates.isNotEmpty())
        if (sawAd && snapshot.pkg !in adSeenThisLaunch) {
            adSeenThisLaunch.add(snapshot.pkg)
            StatsStore.recordAdSeen(snapshot.pkg)
        }
        when (decision) {
            is Decision.Ignore -> Unit

            is Decision.Expired -> logOnce(snapshot.pkg, "expired") {
                "[${tag(snapshot)}] 已超出时间窗（${decision.ageMs}ms > ${decision.windowMs}ms），本次启动不介入"
            }

            is Decision.Wait -> logOnce(snapshot.pkg, "wait:${decision.reason}") {
                when {
                    // 有候选但被安全规则否决：把候选的类名/id/坐标打出来，这是排查的主力
                    decision.candidates.isNotEmpty() ->
                        "[${tag(snapshot)}] ${decision.reason}：" +
                            decision.candidates.take(3).joinToString("；") { it.describe() }

                    // 通用情况：真的什么都没找到
                    decision.reason == RuleEngine.REASON_NOTHING_FOUND ->
                        "[${tag(snapshot)}] 本次启动未发现跳过按钮"

                    // 其余都是诊断信息（例如盲点策略因缺少旁证未解锁）。
                    // 之前这里直接把理由丢掉了，导致"配了盲点规则却不生效"无法排查。
                    else -> "[${tag(snapshot)}] ${decision.reason}"
                }
            }

            is Decision.Abort -> logOnce(snapshot.pkg, "abort:${decision.reason}") {
                "[${tag(snapshot)}] 放弃介入：${decision.reason}"
            }

            is Decision.TapNode -> {
                val node = decision.node
                val ok = tapNode(node)
                if (ok) {
                    if (decision.rejected.isNotEmpty()) {
                        DebugLog.i(
                            "[${tag(snapshot)}] 另外否决了 ${decision.rejected.size} 个候选（防误点）：" +
                                decision.rejected.take(3).joinToString("；") { it.describe() }
                        )
                    }
                    onTapIssued(snapshot, "${decision.reason} ${node.describe()}", node)
                } else {
                    DebugLog.w("[${tag(snapshot)}] 点击注入失败：${decision.reason}")
                }
            }

            is Decision.TapPoint -> {
                val ok = gestureTapper.tap(decision.x, decision.y)
                if (ok) {
                    // 盲点没有节点可比对，校验退回"界面上还有没有跳过字样"
                    onTapIssued(snapshot, "${decision.reason} @(${decision.x},${decision.y})", null)
                } else {
                    DebugLog.w("[${tag(snapshot)}] 坐标点击注入失败：${decision.reason}")
                }
            }

            is Decision.GlobalBack -> {
                val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                lastActionAt = SystemClock.uptimeMillis()
                // 冷却：连按返回键有可能把用户一路退出应用
                lastSuccessAt[snapshot.pkg] = lastActionAt
                DebugLog.i(
                    "[${tag(snapshot)}] 返回键兜底 ${if (ok) "已发送" else "发送失败"}：${decision.reason}"
                )
            }
        }
    }

    /**
     * 点击跳过节点。两种机制交替使用，因为真机上失效模式不同：
     *
     *   - **注入手势**：对 Canvas 自绘的按钮是唯一有效的办法，但有的广告触摸逻辑不接受它
     *   - **ACTION_CLICK**：对真实 View 最可靠（点到可点击的祖先），但自绘按钮完全没有节点可点
     *
     * 真机实测：123云盘、志愿汇、KOOK 的跳过节点都不是 clickable，纯手势点不动；
     * 而 B站 的按钮是自绘的，只有手势能点。所以不能二选一，只能换着来 ——
     * 重试本身由校验结果驱动，并有 MAX_TAPS_PER_LAUNCH 兜住上限。
     */
    private fun tapNode(node: UiNode): Boolean {
        tapAttempts++
        val preferGesture = tapAttempts % 2 == 1

        return if (preferGesture) {
            gestureTapper.tap(node.centerX, node.centerY) || clickByBounds(node)
        } else {
            clickByBounds(node) || gestureTapper.tap(node.centerX, node.centerY)
        }
    }

    /** 按屏幕位置找回节点，向其可点击的祖先发一次真实 view 点击 */
    private fun clickByBounds(node: UiNode): Boolean {
        val target = NodeReader.findClickableByBounds(rootInActiveWindow, node) ?: return false
        return target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun onTapIssued(snapshot: UiSnapshot, reason: String, node: UiNode?) {
        val now = SystemClock.uptimeMillis()
        val n = tracker.recordTap()
        StatsStore.recordClick(snapshot.pkg)
        lastActionAt = now
        // 上锁：校验结果回来之前，不再对该包做任何判定
        awaitingVerifyFor = snapshot.pkg
        // 日志里带上"启动后多久才点到"，让"慢"可以被量化而不是凭感觉
        DebugLog.i("[${tag(snapshot)}] 已点击(第${n}次, 启动后${snapshot.firstSeenAgeMs}ms)：$reason")
        scheduleVerify(snapshot.pkg, reason, node)
    }

    // ------------------------------------------------------------ 校验

    private fun scheduleVerify(targetPkg: String, reason: String, node: UiNode?) {
        pendingVerify?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { verifyAfterTap(targetPkg, reason, node) }
        pendingVerify = runnable
        handler.postDelayed(runnable, VERIFY_DELAY_MS)
    }

    /**
     * 点击后校验。点到广告本体比不跳过更糟，用户会直接卸载，所以这一步不能省。
     *
     * 校验必须**精确到我们点的那一个节点**（按屏幕边界比对），不能只看"界面上还有没有跳过字样"：
     * 真机上京东的广告关掉之后，它自己的首页上还有别的「跳过」（活动动画之类），
     * 用粗粒度判断会把成功误判成失败 —— 既不会进入成功冷却，还会触发重试去点那个无关节点。
     * 志愿汇 曾经连续点 4 次就是这个原因。
     *
     * M1 的已知局限（不掩饰）：广告落地页如果打开在**同一个包**内的新 Activity，
     * 这里无法与"成功跳过"区分。识别同包落地页需要引入落地页特征
     * （下载按钮、"查看详情"、关闭箭头等），留到 M2。
     */
    private fun verifyAfterTap(targetPkg: String, reason: String, node: UiNode?) {
        if (awaitingVerifyFor == targetPkg) awaitingVerifyFor = null

        val foreground = tracker.currentPkg()

        if (foreground != null && foreground != targetPkg && foreground != packageName &&
            looksLikeLandingPage(foreground)
        ) {
            DebugLog.w("[$targetPkg] 疑似误点：前台已跳转到 $foreground，执行返回键回退")
            performGlobalAction(GLOBAL_ACTION_BACK)
            StatsStore.recordMisclick(targetPkg)
            registerStrike(targetPkg)
            return
        }

        val nodes = NodeReader.read(rootInActiveWindow)
        val stillThere = if (node != null) {
            // 精确比对：我们点的那一个节点还在不在（含位置，避免同名节点误判）
            nodes.any {
                it.left == node.left && it.top == node.top &&
                    it.right == node.right && it.bottom == node.bottom
            }
        } else {
            nodes.any { SkipTextMatcher.isSkipLabel(it.label()) }
        }

        // 判成功前先问一句：界面上是不是还有跳过字样？
        // 如果"按坐标比对，我点的那个节点消失了"、但跳过字样仍在，那极可能只是**坐标移动了** ——
        // 志愿汇 的跳过按钮会从 (0,0,210,88) 移到 (0,70,210,158)，
        // 点完它只是换了位置，按坐标比对的校验却判成"消失了＝跳掉了"，记了一次假成功。
        // 这种情况下既不记成功也不重试，留给探测继续观察。
        val skipLabelStillThere = nodes.any { SkipTextMatcher.isSkipLabel(it.label()) }

        if (stillThere && skipLabelStillThere) {
            markNodeFailed(node)
            StatsStore.recordVerified(targetPkg, ok = false)
            DebugLog.w("[$targetPkg] 点击后跳过节点仍存在，判定未生效（$reason）")
        } else if (!stillThere && skipLabelStillThere) {
            markNodeFailed(node)
            DebugLog.w("[$targetPkg] 跳过字样仍在但坐标变了，无法确认是否跳掉 —— 不记成功（$reason）")
        } else if (stillThere) {
            markNodeFailed(node)
            StatsStore.recordVerified(targetPkg, ok = false)
            DebugLog.w("[$targetPkg] 点击后同一个跳过节点仍存在，判定未生效（$reason）")
        } else {
            // M2 阶段一：同包落地页识别（跨应用的情况已在上面用 looksLikeLandingPage 兜底）
            // 只有"强证据"才影响判定；弱证据只记日志，避免启发式的假阳性污染统计 ✗
            val labels = nodes.map { it.label() }
            val confidence = io.github.adskip.engine.LandingPageDetector.detect(labels)
            val hitWords = io.github.adskip.engine.LandingPageDetector.hits(labels)
            if (confidence == io.github.adskip.engine.LandingPageDetector.Confidence.STRONG) {
                // 不记成功，也**不记失败**：这份词表还是启发式，宁可少记一次成功，
                // 也不要把"误点落地页"算成成功 ✗（成功率虚高比少记一次更糟 ✓）
                node?.let { FailedNodeStore.recordSuccess(this, it.nodeKey()) }
                lastSuccessAt[targetPkg] = SystemClock.uptimeMillis()
                tapAttempts = 0
                stopProbeLoop()
                DebugLog.w(
                    "[$targetPkg] 疑似同包落地页（命中 ${hitWords.joinToString("/")}）" +
                        "—— 不计成功，也不计失败（$reason）"
                )
                return
            }
            if (confidence == io.github.adskip.engine.LandingPageDetector.Confidence.WEAK) {
                DebugLog.i(
                    "[$targetPkg] 疑似落地页特征较弱（命中 ${hitWords.joinToString("/")}）" +
                        "—— 仍按成功记，仅记录供事后校正（$reason）"
                )
            }
            // 判定成功：进入冷却期，不再碰这个应用
            // 成功了就把这个节点的失败记录清掉（不要因为历史失败永久拉黑一个正常按钮）
            node?.let { FailedNodeStore.recordSuccess(this, it.nodeKey()) }
            lastSuccessAt[targetPkg] = SystemClock.uptimeMillis()
            tapAttempts = 0
            StatsStore.recordVerified(targetPkg, ok = true)
            // 已经成功了就没必要继续探测：原来循环会一直空转到时间窗结束，
            // 每次 tick 仍要取一次 rootInActiveWindow。这对省电是实打实的
            stopProbeLoop()
            DebugLog.i("[$targetPkg] 跳过按钮已消失，判定成功（$reason）")
        }
    }

    /**
     * 连续误点隔离。M1 只存在内存里，服务重启即恢复；
     * 持久化与规则库修正放到 M3。
     */
    private fun registerStrike(pkg: String) {
        val n = (strikes[pkg] ?: 0) + 1
        strikes[pkg] = n
        if (n >= MAX_STRIKES) {
            quarantined += pkg
            DebugLog.e("[$pkg] 已连续误点 $n 次，本次运行不再介入该应用（重启服务可恢复）")
        }
    }

    private fun looksLikeLandingPage(pkg: String): Boolean =
        LANDING_PKG_HINTS.any { pkg.contains(it, ignoreCase = true) }

    // ------------------------------------------------------------ 杂项

    /** 同一结论在冷却期内只记一次，避免内容变化事件把日志刷爆 */
    private fun logOnce(pkg: String, key: String, message: () -> String) {
        val dedupKey = "$pkg|$key"
        val now = SystemClock.uptimeMillis()
        if (now - (lastLogAt[dedupKey] ?: 0L) < LOG_DEDUP_MS) return
        lastLogAt[dedupKey] = now
        DebugLog.i(message())
    }

    private fun tag(snapshot: UiSnapshot): String =
        engine.ruleFor(snapshot.pkg)?.name ?: snapshot.pkg

    private fun tagFor(pkg: String): String = engine.ruleFor(pkg)?.name ?: pkg

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            } else {
                val dm = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(dm)
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    return dm.widthPixels to dm.heightPixels
                }
            }
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private companion object {
        /**
         * AccessibilityServiceInfo.setNotificationTimeout 在 API 34 起接收 Long
         * （在那之前是 Int），这里跟随 compileSdk 35 的签名。
         *
         * 从 50 提到 120：不再依赖事件密度之后（探测负责抓时机），
         * 没必要让系统以 20 次/秒的频率给我们送事件。
         */
        const val NOTIFICATION_TIMEOUT_MS = 120L

        const val EVALUATE_THROTTLE_MS = 80L

        /**
         * 早期探测间隔（启动后 EARLY_PROBE_UNTIL_MS 以内）。
         * 跳过按钮一出现就要立刻抓住，这一段是密集探测区。
         */
        const val PROBE_INTERVAL_MS = 50L

        /** 后期探测间隔。广告上屏后不会等太久，这里放稀一些省电 */
        const val LATE_PROBE_INTERVAL_MS = 150L

        /** 密集探测持续多久 */
        const val EARLY_PROBE_UNTIL_MS = 1500L

        /**
         * 探测循环的绝对时长上限。
         *
         * 时间窗是按"包名最后一次变化"算的，包名反复来回切换时窗口可能一直不关闭，
         * 所以必须有这个与包名语义无关的硬上限，避免无限期读界面树。
         */
        const val MAX_PROBE_DURATION_MS = 8000L

        /** 两次真实动作之间的最小间隔。连点问题在真机上非常刺眼，这里再兜一层 */
        const val MIN_ACTION_INTERVAL_MS = 900L

        /**
         * 成功跳过后的冷却时间，用于"同一次启动内不要重复点"。
         *
         * 从 10 秒降到 3 秒是实测结论：10 秒的挂钟计时会误伤**下一次启动**
         * （实测启动间隔约 12 秒时冷却仍未过期，导致每次晚约 1 秒才跳过，
         * 点击时倒计时从「05」一路退到「01」）。
         * 现在冷却还会在目标应用重新进入前台时被清掉，所以这里只是一个兜底，
         * 3 秒足够覆盖广告关闭后界面切换的那一小段时间。
         */
        const val POST_SUCCESS_COOLDOWN_MS = 3_000L

        /** 同一结论的日志去重窗口 */
        const val LOG_DEDUP_MS = 10_000L

        const val VERIFY_DELAY_MS = 700L
        const val MAX_STRIKES = 3

        /**
         * 只会被"跳到别的包"这一种情况触发。
         * 用包名关键字而不是硬编码完整包名，是为了在国产 ROM 的定制浏览器/商店上也能命中。
         */
        val LANDING_PKG_HINTS = listOf(
            "browser", "chrome", "firefox", "webkit", "ucmobile", "quark",
            "market", "appstore", "gplay", "vending", "appmarket",
            "taobao", "tmall", "jd.", "pinduoduo", "alipay"
        )
    }
}
