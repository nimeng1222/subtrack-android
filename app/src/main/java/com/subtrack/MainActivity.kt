package com.subtrack

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.MonthlyCashflow
import com.subtrack.domain.parse.PaymentTextParser
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.domain.state.saveEditorDraft
import com.subtrack.domain.state.EditorSaveResult
import com.subtrack.domain.state.EDITOR_CONFLICT_MESSAGE
import com.subtrack.domain.state.SubscriptionActions
import com.subtrack.reminder.NotificationHelper
import com.subtrack.ui.RootOverlays
import com.subtrack.ui.openNotificationSettings
import com.subtrack.ui.ScreenRouter
import com.subtrack.ui.rememberBackupLaunchers
import com.subtrack.ui.rememberLastPaid
import com.subtrack.ui.state.LoadState
import com.subtrack.ui.state.UiMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.subtrack.ui.theme.SubTrackTheme

class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SubTrackApp).container
        setContent {
            val settingsState by container.settingsState.collectAsState()
            val settings = (settingsState as? com.subtrack.data.settings.SettingsState.Loaded)?.settings
            val dark = when (settings?.themeMode) {
                com.subtrack.domain.model.ThemeMode.DARK -> true
                com.subtrack.domain.model.ThemeMode.LIGHT -> false
                else ->
                    androidx.compose.foundation.isSystemInDarkTheme()
            }
            SubTrackTheme(darkTheme = dark) {
                LockGate(settingsState) {
                    SubTrackRoot(container)
                }
            }
        }
    }
}

/**
 * 应用的根 Composable。这里只做三件事：持有导航状态、把领域动作接到屏幕上、渲染浮层。
 * 业务规则一律不写在这里——它们在 domain/state 与 domain/stats 里，可单测。
 */
@Composable
private fun SubTrackRoot(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── "今天"是可观察状态（AC：跨午夜 / 回前台 / 下拉刷新都要重算）──
    // 旧实现用无 key 的 remember 固定，应用开着过夜后待确认、相对日、统计月份全是昨天的。
    var today by remember { mutableStateOf(container.clock.today()) }

    fun refreshToday() { today = container.clock.today() }

    // 跨午夜自动翻页：睡到下一个零点（多留 1.5s 给系统时钟）后刷新
    LaunchedEffect(Unit) {
        while (true) {
            val now = java.time.LocalDateTime.now()
            val nextMidnight = java.time.LocalDateTime.of(java.time.LocalDate.now().plusDays(1), java.time.LocalTime.MIDNIGHT)
            delay(java.time.Duration.between(now, nextMidnight).toMillis() + 1500)
            refreshToday()
        }
    }

    // ── 数据源：可失败的订阅流（AC-46：失败不能伪装成空） ──
    var loadState by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    val subscriptions: List<Subscription> =
        (loadState as? LoadState.Ready)?.data ?: emptyList()

    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey, today) {
        container.repository.observeAll()
            .catch { e -> loadState = LoadState.Failed(e.message ?: "读取失败") }
            .collect { loadState = LoadState.Ready(it) }
    }

    // ── 导航与浮层 ──
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 设置模块的二级页面：由这里持有，系统返回键与统计页"去设置汇率"都落到同一处。
    var settingsRoute by rememberSaveable { mutableStateOf(com.subtrack.ui.settings.SettingsRoute.HOME.name) }
    var showEditor by remember { mutableStateOf(false) }
    // 剪贴板识别出的草稿（L3 零权限方案）；null 表示没有可用的识别结果
    var clipboardDraft by remember { mutableStateOf<PaymentTextParser.Parsed?>(null) }
    // 记住用户"不用过"的那段文本，避免同一段内容每次开 App 都来打扰。
    // 用文本本身（而非布尔）作为去重键：用户复制了新内容应该重新提示。
    var dismissedClip by remember { mutableStateOf<String?>(null) }
    // 传给编辑页的预填草稿
    var prefill by remember { mutableStateOf<PaymentTextParser.Parsed?>(null) }
    var editing by remember { mutableStateOf<Subscription?>(null) }
    var editorSaveError by remember { mutableStateOf<String?>(null) }
    var editorSaving by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var showReminders by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<UiMessage?>(null) }
    var criteria by remember { mutableStateOf(com.subtrack.domain.stats.SubscriptionQuery.Criteria()) }
    var breakdown by remember { mutableStateOf<StatsEngine.Breakdown?>(null) }
    var confirmDelete by remember { mutableStateOf<Subscription?>(null) }
    var resumeFor by remember { mutableStateOf<Subscription?>(null) }
    var undo by remember { mutableStateOf<Subscription?>(null) }
    var paidTotal by remember { mutableLongStateOf(0L) }
    // 已付笔数：为 0 时详情页要写"还没有付款记录"，而不是显示 ¥0.00 让人以为算错了。
    var paidCount by remember { mutableIntStateOf(0) }

    val loadedState by container.settingsState.collectAsState()
    val settings = (loadedState as? com.subtrack.data.settings.SettingsState.Loaded)?.settings ?: return
    // 健康卡三项（通知/精确闹钟/电池优化）都可能被用户在系统设置里改：
    // resumeTick 随回前台递增（递增在下方已有的 ON_RESUME 观察者里），以此为键重读，
    // 不显示过时状态（2026-10-28 设备实测）。
    var resumeTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var notificationsEnabled by remember(resumeTick) { mutableStateOf(NotificationHelper.canPost(context)) }

    // ── 统计：流水相关的量要 suspend 查，单独拉 ──
    // 多币种开启时全部走统一折算口径（缺汇率的条目排除、由 missingRates 提示），
    // 禁止把外币数值直接当人民币相加。
    val fx: Map<String, Long>? =
        if (settings.multiCurrencyEnabled) settings.effectiveFxRates else null

    var payments by remember { mutableStateOf(Triple(0L, 0, null as LocalDate?)) }
    var monthPaidMinor by remember { mutableLongStateOf(0L) }
    LaunchedEffect(subscriptions, refreshKey, today, settings.multiCurrencyEnabled, settings.effectiveFxRates) {
        val (sum, count) = run {
            val records = try { container.repository.paymentsBetween(today.minusDays(90), today) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = UiMessage.Toast("流水读取失败，保留上次统计：${e.message}")
                return@LaunchedEffect
            }
            com.subtrack.domain.stats.CurrencyConverter.paymentsCnySummary(records, fx)
        }
        payments = Triple(sum, count, container.repository.firstPaymentDate())
        // 预算的"本月已付"：本月 1 号起的实付流水（确认续费后不会从预算占用里消失）
        monthPaidMinor = run {
            val monthRecords = try { container.repository.paymentsBetween(today.withDayOfMonth(1), today) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = UiMessage.Toast("预算读取失败，保留上次统计：${e.message}")
                return@LaunchedEffect
            }
            com.subtrack.domain.stats.CurrencyConverter.paymentsCnySummary(monthRecords, fx).first
        }
    }
    val (paymentsTotal, paymentsCount, firstPayment) = payments

    val stats = remember(subscriptions, today, paymentsTotal, paymentsCount, firstPayment, settings.multiCurrencyEnabled, settings.effectiveFxRates) {
        StatsEngine.snapshot(
            subs = subscriptions,
            today = today,
            dueThisMonthMinor = MonthlyCashflow.dueThisMonthMinor(subscriptions, today, fx),
            dueThisMonthCount = MonthlyCashflow.dueThisMonthCount(subscriptions, today),
            paymentsTotalMinor = paymentsTotal,
            paymentsCount = paymentsCount,
            firstPaymentDate = firstPayment,
            // 多币种关闭时传 null = 完全按原样算（默认）
            fxRates = fx)
    }

    // L2 增强：四项状态集中在这里派生，开关判断只做一次
    val lastPaid = rememberLastPaid(container, settings.priceWatchEnabled, subscriptions, refreshKey)
    val enhancements = com.subtrack.ui.rememberEnhancementState(
        subscriptions = subscriptions,
        settings = settings,
        today = today,
        stats = stats,
        monthSpentMinor = monthPaidMinor,
        lastPaidAmounts = lastPaid,
    )

    val detail = subscriptions.firstOrNull { it.id == detailId }

    /** 所有状态变更都走这里：写流水 → 写订阅 → 重排提醒 → 提示。 */
    val applyMutation: (SubscriptionActions.Outcome) -> Unit = { outcome ->
        scope.launch {
            runCatching { container.applyOutcome(outcome) }
                .onFailure { message = UiMessage.Toast("操作失败：${it.message}") }
            outcome.toast?.let { message = UiMessage.Toast(it) }
        }
    }

    val openDetail: (Subscription) -> Unit = { sub ->
        detailId = sub.id
        showReminders = false
        scope.launch {
            // 一次取回流水，同时算出合计与笔数（口径与 totalPaid 相同，不重复查询）。
            val (total, count) = com.subtrack.domain.stats.CurrencyConverter
                .paymentsCnySummary(container.repository.paymentsOf(sub.id), null)
            paidTotal = total
            paidCount = count
        }
    }

    // ── 备份：SAF 选择器 + 编排交给 BackupController ──
    val backupLaunchers = rememberBackupLaunchers(
        container = container,
        context = context,
        scope = scope,
        subscriptions = subscriptions,
        settings = settings,
        today = today,
        onMessage = { message = it },
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsEnabled = granted
        // 用户拒绝时只提示一次，不再替用户跳到系统设置：保存订阅不该把人踢出应用。
        if (!granted) message = UiMessage.Toast("未开启通知：到期提醒不会出现，可在首页顶部开启")
    }

    LaunchedEffect(Unit) {
        // 打开 App 即补一遍扫描：把到点未发的提醒（含逾期补发）发出去。
        // 排程（reconcile + 每日扫描时刻）由 SubTrackApp 在设置首次加载后统一负责，
        // 这里不再用可能还是默认值的设置提前规划。
        container.reminderService.sweep()
        // 临时验证入口：adb 传入 cliptext 时先写入剪贴板，走真实读取路径
        (context as? android.app.Activity)?.intent?.getStringExtra("cliptext")?.let { text ->
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("t", text))
            clipboardDraft = com.subtrack.ui.clipboard.ClipboardReader.readPayment(context)
        }
    }

    // 回到前台时刷新日期 + 读一次剪贴板（只在前台读：这是 Android 10+ 的硬限制，也符合隐私预期）。
    // 读不到就静默跳过——用户可能只是复制了别的东西，不该被弹窗打扰。
    // 注意读取时机：ON_RESUME 早于窗口获得焦点，Android 10+ 的剪贴板读取会被
    // ClipboardService 以 "application is not in focus" 拒绝（2026-10-28 设备实测，
    // 冷启动与点击图标启动均命中）。因此剪贴板读取挂到 onWindowFocusChanged(true)。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                resumeTick++
                refreshToday()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val activityContext = context as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(activityContext) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                val parsed = com.subtrack.ui.clipboard.ClipboardReader.readPayment(context)
                clipboardDraft = parsed?.takeIf { it.raw != dismissedClip }
            }
        }
        val tree = activityContext?.window?.decorView?.viewTreeObserver
        tree?.addOnWindowFocusChangeListener(listener)
        onDispose { tree?.removeOnWindowFocusChangeListener(listener) }
    }
    // 两个自动消失的浮层：toast 2.6s，撤销条 5s
    LaunchedEffect(message) { if (message is UiMessage.Toast) { delay(2600); message = null } }
    LaunchedEffect(undo) { if (undo != null) { delay(5000); undo = null } }

    val requestNotificationPermission: (com.subtrack.ui.NotificationPromptMode) -> Unit = { mode ->
        val rationaleVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (context as? android.app.Activity)
                ?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
        when (
            com.subtrack.ui.notificationPromptAction(
                mode = mode,
                sdkInt = Build.VERSION.SDK_INT,
                canPost = NotificationHelper.canPost(context),
                rationaleVisible = rationaleVisible,
            )
        ) {
            com.subtrack.ui.NotificationPromptAction.REQUEST_PERMISSION ->
                // 常量本身是 API 33 才有的：显式再判一次版本，既让 lint 满意，也不依赖上游分支的隐含前提。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            com.subtrack.ui.NotificationPromptAction.OPEN_SYSTEM_SETTINGS -> openNotificationSettings(context)
            com.subtrack.ui.NotificationPromptAction.NONE -> Unit
        }
    }

    val onSave: (Subscription) -> Unit = save@{ draft ->
        if (editorSaving) return@save
        val original = editing // Freeze the opening snapshot before launching asynchronous work.
        editorSaving = true
        editorSaveError = null
        scope.launch {
            try {
                when (container.repository.saveEditorDraft(original, draft)) {
                    is EditorSaveResult.Conflict -> editorSaveError = EDITOR_CONFLICT_MESSAGE
                    EditorSaveResult.Saved -> {
                        showEditor = false
                        editing = null
                        prefill = null
                        runCatching { container.reminderService.replan(draft) }
                            .onFailure { message = UiMessage.Toast("已保存，但提醒重排失败：${it.message}") }
                        // 保存后只做"请求权限"这一件事，绝不跳系统设置（AFTER_SAVE 模式）。
                        requestNotificationPermission(com.subtrack.ui.NotificationPromptMode.AFTER_SAVE)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                editorSaveError = "保存失败，草稿已保留：${e.message}"
            } finally { editorSaving = false }
        }
    }

    val onUndoDelete: (Subscription) -> Unit = { removed ->
        scope.launch {
            container.repository.upsert(SubscriptionActions.restore(removed).subscription)
            container.reminderService.replan(removed)
        }
        undo = null
    }

    val onConfirmDelete: (Subscription) -> Unit = { target ->
        scope.launch {
            container.repository.archive(target.id)
            container.reminderService.cancelFor(target.id)
        }
        undo = target
        confirmDelete = null
        detailId = null
    }

    val inSettingsSubPage = tab == 3 && settingsRoute != com.subtrack.ui.settings.SettingsRoute.HOME.name
    BackHandler(enabled = detail != null || showReminders || showEditor || breakdown != null || showCalendar || inSettingsSubPage) {
        when {
            // 功能页的返回优先：回到上一层（同步管理 → 数据与同步 → 设置首页），不直接退出应用。
            inSettingsSubPage && detail == null && !showReminders && !showEditor && breakdown == null && !showCalendar ->
                settingsRoute = com.subtrack.ui.settings.SettingsRoute.fromName(settingsRoute).parent.name
            breakdown != null -> breakdown = null
            showEditor -> { showEditor = false; editing = null }
            detail != null -> detailId = null
            showCalendar -> showCalendar = false
            showReminders -> showReminders = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        ScreenRouter(
            tab = tab,
            detail = detail,
            subscriptions = subscriptions,
            stats = stats,
            settings = settings,
            today = today,
            paidTotal = paidTotal,
            paidCount = paidCount,
            loadState = loadState,
            notificationsEnabled = notificationsEnabled,
            healthRefreshKey = resumeTick,
            criteria = criteria,
            insight = StatsEngine.insight(stats.forecastMonthlyMinor, stats.actualMonthlyMinor, paymentsCount),
            monthlyTrend = com.subtrack.domain.stats.BillingCalendar.recentMonths(subscriptions, today, 6),
            budget = enhancements.budget, priceChanges = enhancements.priceChanges,
            healthFindings = enhancements.healthFindings, missingRates = enhancements.missingRates,
            onOpenSubscription = { id -> detailId = id },
            onOpenCurrencySettings = { tab = 3; settingsRoute = com.subtrack.ui.settings.SettingsRoute.CURRENCY.name },
            showCalendar = showCalendar, onOpenCalendar = { showCalendar = true },
            onCloseCalendar = { showCalendar = false },
            showReminders = showReminders,
            onSelectTab = { tab = it; if (it == 3) settingsRoute = com.subtrack.ui.settings.SettingsRoute.HOME.name },
            onCriteriaChange = { criteria = it },
            onOpen = openDetail,
            onOpenReminders = { showReminders = true },
            onCloseDetail = { detailId = null },
            onCloseReminders = { showReminders = false },
            onAdd = { editorSaveError = null; editing = null; prefill = null; showEditor = true },
            clipboardDraft = clipboardDraft,
            onUseClipboardDraft = { draft ->
                editorSaveError = null
                // 用识别结果预填到新建页，用户确认后才入库
                editing = null
                prefill = draft
                showEditor = true
                clipboardDraft = null
            },
            onDismissClipboardDraft = {
                dismissedClip = clipboardDraft?.raw
                clipboardDraft = null
            },
            onEdit = { sub -> editorSaveError = null; editing = sub; prefill = null; showEditor = true; detailId = null },
            onRenew = { sub -> applyMutation(SubscriptionActions.confirmRenewal(sub, today)); detailId = null },
            onPause = { sub -> applyMutation(SubscriptionActions.pause(sub)); detailId = null },
            onResume = { sub -> resumeFor = sub },
            onCancel = { sub -> applyMutation(SubscriptionActions.cancel(sub, today)); detailId = null },
            onDelete = { sub -> confirmDelete = sub },
            onConvertTrial = { sub -> applyMutation(SubscriptionActions.convertTrial(sub, today)); detailId = null },
            onExpire = { sub -> applyMutation(SubscriptionActions.expire(sub, today)); detailId = null },
            onSnooze = { sub -> applyMutation(SubscriptionActions.snooze(sub, today)) },
            onMute = { sub -> applyMutation(SubscriptionActions.muteThisCycle(sub, sub.nextChargeDate ?: today)) },
            onConfirmPending = { sub -> applyMutation(SubscriptionActions.confirmRenewal(sub, today)) },
            onCancelPending = { sub -> applyMutation(SubscriptionActions.cancel(sub, today)) },
            onSnoozePending = { sub -> applyMutation(SubscriptionActions.snooze(sub, today)) },
            onOpenBreakdown = { breakdown = it },
            onPullRefresh = { refreshToday(); refreshKey++ },
            onRetry = { loadState = LoadState.Loading; refreshKey++ },
            onEnableNotifications = { requestNotificationPermission(com.subtrack.ui.NotificationPromptMode.USER_INITIATED) },
            onUpdateSettings = { s2 -> scope.launch { container.settingsStore.update(s2) } },
            onExportJson = { backupLaunchers.exportJson() },
            onExportCsv = { backupLaunchers.exportCsv() },
            onImportJson = { backupLaunchers.importJson() },
            onImportCsv = { backupLaunchers.importCsv() },
            onSendTestNotification = { scope.launch { container.reminderService.sendTestNotification() } },
            settingsRoute = com.subtrack.ui.settings.SettingsRoute.fromName(settingsRoute),
            // 从别的 Tab（比如 AI 页的"去设置里配置"）跳设置：必须同时切到设置 Tab，
            // 否则路由变了但界面还停在原 Tab，表现为"点了没反应"。
            onNavigateSettings = { route -> settingsRoute = route.name; tab = 3 },
            onSelectAiModel = { model -> scope.launch { container.settingsStore.updateNetwork { it.copy(aiModel = model) } } },

        )

        RootOverlays(
            showEditor = showEditor, editing = editing, prefill = prefill,
            editorSaveError = editorSaveError, editorSaving = editorSaving,
            settings = settings, today = today,
            breakdown = breakdown, message = message, undo = undo,
            confirmDelete = confirmDelete, resumeFor = resumeFor,
            onDismissEditor = { if (!editorSaving) { showEditor = false; editing = null; prefill = null; editorSaveError = null } },
            onSave = onSave,
            onCloseBreakdown = { breakdown = null },
            onDismissMessage = { message = null },
            onUndoDelete = { undo?.let(onUndoDelete) },
            onConfirmDelete = { confirmDelete?.let(onConfirmDelete) },
            onDismissDelete = { confirmDelete = null },
            onResume = { target, newDate ->
                applyMutation(SubscriptionActions.resume(target, newDate)); resumeFor = null
            },
            onDismissResume = { resumeFor = null },
        )
    }
}
