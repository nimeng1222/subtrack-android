package com.subtrack.ui

import androidx.compose.runtime.Composable
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.ui.detail.DetailScreen
import com.subtrack.ui.home.HomeScreen
import com.subtrack.ui.reminders.ReminderCenterScreen
import com.subtrack.ui.stats.StatsScreen
import com.subtrack.ui.state.ListFilter
import com.subtrack.ui.state.LoadState
import java.time.LocalDate

/**
 * 三个 Tab + 详情 + 提醒中心的路由。纯展示，所有动作都是回调，不含业务逻辑。
 *
 * 优先级：详情 > 提醒中心 > Tab。这样从提醒中心点进详情后，返回能回到提醒中心。
 */
@Composable
fun ScreenRouter(
    tab: Int,
    detail: Subscription?,
    subscriptions: List<Subscription>,
    stats: StatsEngine.Snapshot,
    settings: AppSettings,
    today: LocalDate,
    paidTotal: Long,
    paidCount: Int = 0,
    loadState: LoadState,
    notificationsEnabled: Boolean,
    healthRefreshKey: Int = 0,
    criteria: com.subtrack.domain.stats.SubscriptionQuery.Criteria,
    insight: StatsEngine.Insight?,
    monthlyTrend: List<com.subtrack.domain.stats.BillingCalendar.MonthTotal>,
    budget: com.subtrack.domain.stats.BudgetWatch.Status?,
    priceChanges: List<com.subtrack.domain.stats.PriceWatch.Change>,
    healthFindings: List<com.subtrack.domain.stats.SubscriptionHealth.Finding>,
    missingRates: List<String>,
    onOpenSubscription: (String) -> Unit,
    onOpenCurrencySettings: () -> Unit,
    clipboardDraft: com.subtrack.domain.parse.PaymentTextParser.Parsed?,
    onUseClipboardDraft: (com.subtrack.domain.parse.PaymentTextParser.Parsed) -> Unit,
    onDismissClipboardDraft: () -> Unit,
    showReminders: Boolean,
    showCalendar: Boolean,
    onSelectTab: (Int) -> Unit,
    onCriteriaChange: (com.subtrack.domain.stats.SubscriptionQuery.Criteria) -> Unit,
    onOpen: (Subscription) -> Unit,
    onOpenReminders: () -> Unit,
    onCloseDetail: () -> Unit,
    onCloseReminders: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Subscription) -> Unit,
    onRenew: (Subscription) -> Unit,
    onPause: (Subscription) -> Unit,
    onResume: (Subscription) -> Unit,
    onCancel: (Subscription) -> Unit,
    onDelete: (Subscription) -> Unit,
    onConvertTrial: (Subscription) -> Unit,
    onExpire: (Subscription) -> Unit,
    onSnooze: (Subscription) -> Unit,
    onMute: (Subscription) -> Unit,
    onConfirmPending: (Subscription) -> Unit,
    onCancelPending: (Subscription) -> Unit,
    onSnoozePending: (Subscription) -> Unit,
    onOpenBreakdown: (StatsEngine.Breakdown) -> Unit,
    onOpenCalendar: () -> Unit,
    onCloseCalendar: () -> Unit,
    onPullRefresh: () -> Unit,
    onRetry: () -> Unit,
    onEnableNotifications: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
    onSendTestNotification: () -> Unit,
    settingsRoute: com.subtrack.ui.settings.SettingsRoute = com.subtrack.ui.settings.SettingsRoute.HOME,
    onNavigateSettings: (com.subtrack.ui.settings.SettingsRoute) -> Unit = {},
    onSelectAiModel: (String) -> Unit = {},
) {
    when {
        detail != null -> DetailScreen(
            sub = detail,
            today = today,
            paidTotalMinor = paidTotal,
            paidCount = paidCount,
            onBack = onCloseDetail,
            onRenew = { onRenew(detail) },
            onEdit = { onEdit(detail) },
            onPause = { onPause(detail) },
            onResume = { onResume(detail) },
            onCancel = { onCancel(detail) },
            onDelete = { onDelete(detail) },
            onConvertTrial = { onConvertTrial(detail) },
            onExpire = { onExpire(detail) },
        )

        showReminders -> ReminderCenterScreen(
            subscriptions = subscriptions,
            today = today,
            onBack = onCloseReminders,
            onOpen = onOpen,
            onSelectTab = onSelectTab,
            onSnooze = onSnooze,
            onMute = onMute,
        )

        showCalendar -> com.subtrack.ui.calendar.CalendarScreen(
            subscriptions = subscriptions,
            today = today,
            onOpen = onOpen,
            onSelectTab = onSelectTab,
        )

        tab == 0 -> HomeScreen(
            subscriptions = subscriptions,
            stats = stats,
            today = today,
            criteria = criteria,
            onCriteriaChange = onCriteriaChange,
            onAdd = onAdd,
            onOpen = onOpen,
            onOpenReminders = onOpenReminders,
            onSelectTab = onSelectTab,
            notificationsEnabled = notificationsEnabled,
            onEnableNotifications = onEnableNotifications,
            onPullRefresh = onPullRefresh,
            loadState = loadState,
            onRetry = onRetry,
            clipboardDraft = clipboardDraft,
            onUseClipboardDraft = onUseClipboardDraft,
            onDismissClipboardDraft = onDismissClipboardDraft,
            onConfirmPending = onConfirmPending,
            onCancelPending = onCancelPending,
            onSnoozePending = onSnoozePending,
        )

        tab == 1 -> StatsScreen(
            stats = stats,
            subscriptions = subscriptions,
            today = today,
            insight = insight,
            monthlyTrend = monthlyTrend,
            budget = budget,
            priceChanges = priceChanges,
            healthFindings = healthFindings,
            missingRates = missingRates,
            fxRates = if (settings.multiCurrencyEnabled) settings.effectiveFxRates else null,
            onOpenSubscription = onOpenSubscription,
            onOpenCurrencySettings = onOpenCurrencySettings,
            onOpenBreakdown = onOpenBreakdown,
            onOpenCalendar = onOpenCalendar,
            onSelectTab = onSelectTab,
        )

        tab == 2 -> com.subtrack.ui.ai.AiScreen(
            settings = settings,
            subscriptions = subscriptions,
            today = today,
            onOpenSettings = { onNavigateSettings(com.subtrack.ui.settings.SettingsRoute.NETWORK) },
            onSelectTab = onSelectTab,
            onSelectModel = onSelectAiModel,
        )

        else -> com.subtrack.ui.settings.SettingsHost(
            route = settingsRoute,
            onNavigate = onNavigateSettings,
            settings = settings,
            notificationsEnabled = notificationsEnabled,
            healthRefreshKey = healthRefreshKey,
            onUpdateSettings = onUpdateSettings,
            onExportJson = onExportJson,
            onExportCsv = onExportCsv,
            onImportJson = onImportJson,
            onImportCsv = onImportCsv,
            onSendTestNotification = onSendTestNotification,
            onSelectTab = onSelectTab,
        )
    }
}
