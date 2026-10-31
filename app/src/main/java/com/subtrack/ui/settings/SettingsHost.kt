package com.subtrack.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.subtrack.BuildConfig
import com.subtrack.SubTrackApp
import com.subtrack.domain.model.AppSettings
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.home.FloatingTabBar
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Success
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设置的页面层级。
 *
 * 改版前设置是「一个超长表单」：提醒、外观、增强、联网、同步、备份全堆在一列里，
 * 用户既看不出能设置什么，也不知道自己现在的状态。现在拆成「入口页 + 8 个功能页」，
 * 首页只回答两个问题：这里能设置什么、现在是什么状态。
 */
enum class SettingsRoute {
    HOME,
    REMINDER,
    BUDGET,
    CURRENCY,
    APPEARANCE,
    DATA_SYNC,
    SYNC,
    NETWORK,
    SECURITY,
    ABOUT;

    /** 上一层：系统返回与顶部返回走同一条链（同步管理 → 数据与同步 → 设置首页）。 */
    val parent: SettingsRoute
        get() = when (this) {
            SYNC -> DATA_SYNC
            HOME -> HOME
            else -> HOME
        }

    companion object {
        fun fromName(name: String?): SettingsRoute =
            entries.firstOrNull { it.name == name } ?: HOME
    }
}

/** 首页同步行需要的真实状态。 */
internal data class SyncSummary(
    val configured: Boolean = false,
    val active: Boolean = false,
    val pendingUploads: Int = 0,
    val conflicts: Int = 0,
    val initError: String? = null,
)

/**
 * 设置模块的宿主：负责路由与首页。所有设置页共用同一套组件与标题/返回规则。
 *
 * [route] 由上层持有（统计页"去设置汇率"要直接落到币种页），这里只负责派发。
 */
@Composable
fun SettingsHost(
    route: SettingsRoute,
    onNavigate: (SettingsRoute) -> Unit,
    settings: AppSettings,
    notificationsEnabled: Boolean,
    healthRefreshKey: Int,
    onUpdateSettings: (AppSettings) -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
    onSendTestNotification: () -> Unit,
    onSelectTab: (Int) -> Unit,
) {
    // 首页滚动位置：从功能页返回时必须回到原位置，不能跳回顶部。
    val homeListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val context = LocalContext.current
    var syncSummary by remember { mutableStateOf(SyncSummary()) }
    var overviewTick by remember { mutableIntStateOf(0) }

    // 每次回到首页都重读一次同步状态：用户在同步页里改过东西，首页的摘要不能是旧的。
    LaunchedEffect(route) {
        if (route == SettingsRoute.HOME) overviewTick++
    }
    LaunchedEffect(healthRefreshKey, overviewTick) {
        val service = runCatching { (context.applicationContext as SubTrackApp).container.syncService }.getOrNull()
            ?: return@LaunchedEffect
        withTimeoutOrNull(3_000) { service.ensureInitialized() }
        val status = runCatching { service.status() }.getOrNull()
        syncSummary = SyncSummary(
            configured = service.activeVault != null || service.endpoint != null,
            active = status?.active == true,
            pendingUploads = status?.pendingUploads ?: 0,
            conflicts = status?.unresolvedConflicts ?: 0,
            initError = service.initError,
        )
    }

    when (route) {
        SettingsRoute.HOME -> SettingsHomeScreen(
            settings = settings,
            notificationsEnabled = notificationsEnabled,
            sync = syncSummary,
            listState = homeListState,
            onOpen = onNavigate,
            onSelectTab = onSelectTab,
        )

        SettingsRoute.REMINDER -> ReminderSettingsScreen(
            settings = settings,
            notificationsEnabled = notificationsEnabled,
            healthRefreshKey = healthRefreshKey,
            onUpdate = onUpdateSettings,
            onSendTestNotification = onSendTestNotification,
            onBack = { onNavigate(SettingsRoute.HOME) },
        )

        SettingsRoute.BUDGET -> BudgetStatsScreen(
            settings = settings,
            onUpdate = onUpdateSettings,
            onBack = { onNavigate(SettingsRoute.HOME) },
        )

        SettingsRoute.CURRENCY -> CurrencySettingsScreen(
            settings = settings,
            onUpdate = onUpdateSettings,
            onBack = { onNavigate(SettingsRoute.HOME) },
        )

        SettingsRoute.APPEARANCE -> AppearanceSettingsScreen(
            settings = settings,
            onUpdate = onUpdateSettings,
            onBack = { onNavigate(SettingsRoute.HOME) },
        )

        SettingsRoute.DATA_SYNC -> DataAndSyncScreen(
            sync = syncSummary,
            onOpenSync = { onNavigate(SettingsRoute.SYNC) },
            onExportJson = onExportJson,
            onExportCsv = onExportCsv,
            onImportJson = onImportJson,
            onImportCsv = onImportCsv,
            onBack = { onNavigate(SettingsRoute.HOME) },
        )

        SettingsRoute.SYNC -> SyncManagementScreen(
            onBack = { onNavigate(SettingsRoute.DATA_SYNC) },
        )

        SettingsRoute.NETWORK -> NetworkAndAiScreen(
            settings = settings,
            onUpdate = onUpdateSettings,
            onBack = { onNavigate(SettingsRoute.HOME) },
        )

        SettingsRoute.SECURITY -> SecuritySettingsScreen(
            settings = settings,
            onUpdate = onUpdateSettings,
            onOpenNetwork = { onNavigate(SettingsRoute.NETWORK) },
            onBack = { onNavigate(SettingsRoute.HOME) },
        )

        SettingsRoute.ABOUT -> AboutScreen(
            settings = settings,
            onUpdate = onUpdateSettings,
            onBack = { onNavigate(SettingsRoute.HOME) },
        )
    }
}

/**
 * 设置首页：三个分区、八个入口。每行只有图标、标题、状态与箭头，
 * 任何表单/开关/URL 都不出现在这一页。
 */
@Composable
private fun SettingsHomeScreen(
    settings: AppSettings,
    notificationsEnabled: Boolean,
    sync: SyncSummary,
    listState: LazyListState,
    onOpen: (SettingsRoute) -> Unit,
    onSelectTab: (Int) -> Unit,
) {
    val nowMillis = remember(sync) { System.currentTimeMillis() }
    val manualRates = settings.fxRates.size
    val syncTone = when {
        sync.initError != null -> Tone.Danger
        sync.active && sync.conflicts > 0 -> Tone.Warning
        sync.active -> Tone.Success
        else -> Tone.Muted
    }
    val networkTone = when {
        settings.network.aiEnabled && settings.network.aiBaseUrl.isBlank() -> Tone.Warning
        settings.network.aiEnabled || settings.network.templatesEnabled -> Tone.Success
        else -> Tone.Muted
    }
    val reminderTone = if (notificationsEnabled) Tone.Success else Tone.Warning

    SettingsPage(
        title = "设置",
        onBack = null,
        listState = listState,
        listTag = "settings-list",
        bottomContent = {
            FloatingTabBar(
                selected = 3,
                onSelect = onSelectTab,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp, top = 4.dp),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "常用设置") {
                SettingsNavRow(
                    icon = com.subtrack.R.drawable.ic_bell,
                    title = "提醒与通知",
                    status = SettingsSummaries.reminderLine(settings.reminderTime, settings.defaultReminderOffsets, notificationsEnabled),
                    tone = reminderTone,
                    tag = "settings-nav-reminder",
                    onClick = { onOpen(SettingsRoute.REMINDER) },
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = com.subtrack.R.drawable.ic_chart,
                    title = "预算与统计",
                    status = SettingsSummaries.budgetLine(settings.monthlyBudgetMinor),
                    tone = if (settings.monthlyBudgetMinor == null) Tone.Muted else Tone.Neutral,
                    tag = "settings-nav-budget",
                    onClick = { onOpen(SettingsRoute.BUDGET) },
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = com.subtrack.R.drawable.ic_card,
                    title = "币种与汇率",
                    status = SettingsSummaries.currencyLine(
                        multiEnabled = settings.multiCurrencyEnabled,
                        manualCount = manualRates,
                        onlineEnabled = settings.network.fxEnabled,
                        fetchedAtMillis = settings.network.fxFetchedAt,
                        nowMillis = nowMillis,
                    ),
                    tone = if (settings.multiCurrencyEnabled) Tone.Neutral else Tone.Muted,
                    tag = "settings-nav-currency",
                    onClick = { onOpen(SettingsRoute.CURRENCY) },
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = com.subtrack.R.drawable.ic_sparkle,
                    title = "显示与外观",
                    status = SettingsSummaries.themeLine(settings.themeMode),
                    tag = "settings-nav-appearance",
                    onClick = { onOpen(SettingsRoute.APPEARANCE) },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "数据与服务") {
                SettingsNavRow(
                    icon = com.subtrack.R.drawable.ic_cloud,
                    title = "数据与同步",
                    status = SettingsSummaries.syncLine(
                        active = sync.active,
                        pendingUploads = sync.pendingUploads,
                        conflicts = sync.conflicts,
                        initError = sync.initError,
                        configured = sync.configured,
                    ),
                    tone = syncTone,
                    tag = "settings-nav-data",
                    onClick = { onOpen(SettingsRoute.DATA_SYNC) },
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = com.subtrack.R.drawable.ic_wifi,
                    title = "联网与 AI",
                    status = SettingsSummaries.networkLine(settings.network),
                    tone = networkTone,
                    tag = "settings-nav-network",
                    onClick = { onOpen(SettingsRoute.NETWORK) },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "应用") {
                SettingsNavRow(
                    icon = com.subtrack.R.drawable.ic_lock,
                    title = "安全与隐私",
                    status = SettingsSummaries.securityLine(settings.appLockEnabled),
                    tone = if (settings.appLockEnabled) Tone.Success else Tone.Muted,
                    tag = "settings-nav-security",
                    onClick = { onOpen(SettingsRoute.SECURITY) },
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = com.subtrack.R.drawable.ic_info,
                    title = "关于与更新",
                    status = SettingsSummaries.aboutLine(BuildConfig.VERSION_NAME),
                    tag = "settings-nav-about",
                    onClick = { onOpen(SettingsRoute.ABOUT) },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            GlassCard(padding = PaddingValues(14.dp), corner = SettingsMetrics.groupCorner) {
                Text(
                    "本地优先",
                    style = Type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = InkSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "默认不上传台账。联网服务逐项启用，断网仍可记账、备份和提醒。",
                    style = Type.caption.copy(lineHeight = 17.sp),
                    color = InkSecondary,
                )
            }
        }
    }
}
