package com.subtrack

import android.app.Application
import android.content.Context
import com.subtrack.core.time.AppClock
import com.subtrack.core.time.DeviceClock
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.repository.SubscriptionRepositoryImpl
import com.subtrack.data.settings.SettingsStore
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.repository.SubscriptionRepository
import com.subtrack.reminder.AlarmReminderScheduler
import com.subtrack.reminder.DailySweepScheduler
import com.subtrack.reminder.NotificationHelper
import com.subtrack.reminder.ReminderPlanner
import com.subtrack.reminder.ReminderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.glance.appwidget.updateAll

class AppContainer(context: Context) {
    val clock: AppClock = DeviceClock
    private val database = SubTrackDatabase.get(context)
    /** F8: one production write path. Starts as a pass-through and is bound to the encrypted outbox once a vault is active. */
    val syncRuntime = com.subtrack.sync.SwitchableSyncWriteGateway()
    val syncService = com.subtrack.sync.SyncProductService(context.applicationContext, database, syncRuntime)
    val repository: SubscriptionRepository = SubscriptionRepositoryImpl(
        subscriptionDao = database.subscriptionDao(),
        paymentDao = database.paymentRecordDao(),
        categoryDao = database.categoryDao(),
        clock = clock,
        database = database,
        syncGateway = syncRuntime,
        // T1: outer business transactions run under the same vault→Room order as sync and hop onto the tx thread.
        transactionCoordinator = object : com.subtrack.sync.SyncTransactionCoordinator {
            override suspend fun <T> run(block: suspend () -> T): T =
                com.subtrack.sync.withCoordinatedTransaction(database, syncService.activeVault) { block() }
        },
    )
    private val syncStartup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        // S2: real production initialization chain: legacy migration + interrupted-confirmation recovery BEFORE the UI can
        // preview/publish. Failures are surfaced (not swallowed) and the gate is still released so the app stays usable.
        syncStartup.launch {
            runCatching {
                syncService.migrateLegacyDisabledState()
                syncService.recoverInterruptedPublish()
            }.onFailure { syncService.recordInitializationError(it) }
            syncService.markInitialized()
        }
    }
    suspend fun awaitSyncReady() = syncService.ensureInitialized()
    val settingsStore = SettingsStore(context.applicationContext)
    /**
     * AI 对话历史：应用级持有，切换底部 Tab 不会丢；同时由 [AiChatStore] 原子写入本地文件，
     * 杀进程/重启后仍可恢复（本地优先、无账号）。
     */
    val aiChats = com.subtrack.ai.AiChatStore(context.applicationContext)

    val loadedSettings = com.subtrack.data.settings.LoadedSettings()
    val settingsState = loadedSettings.state
    val settings: AppSettings get() = loadedSettings.current()
    suspend fun awaitSettings(): AppSettings = loadedSettings.await()

    val widgetInputs get() = com.subtrack.widget.widgetInputs(repository.observeAll(), settingsState)

    val planner = ReminderPlanner(clock)
    val scheduler = AlarmReminderScheduler(context)
    val reminderService = ReminderService(
        notifier = com.subtrack.reminder.NotificationNotifier(context.applicationContext),
        repository = repository,
        reminderDao = database.reminderDao(),
        planner = planner,
        scheduler = scheduler,
        clock = clock,
        settingsProvider = { settings },
        awaitSettings = { awaitSettings() },
        // G7: only the designated reminder device schedules; role changes and remote applies re-plan immediately.
        shouldSchedule = { syncService.shouldScheduleReminders() },
    )

    /**
     * 应用一份新设置。返回 true 表示提醒相关的配置（时刻 / 全局提前量）发生了变化，
     * 调用方需要重排提醒并重挂每日扫描。
     */
    init {
        syncService.afterRemoteApply = { runCatching { reminderService.reconcile() } }
        syncService.afterReminderRoleChanged = { runCatching { reminderService.reconcile() } }
    }

    fun applySettings(s: AppSettings): Boolean {
        val previous = (settingsState.value as? com.subtrack.data.settings.SettingsState.Loaded)?.settings
        loadedSettings.publish(s)
        return previous == null || previous.reminderTime != s.reminderTime ||
            previous.defaultReminderOffsets != s.defaultReminderOffsets
    }
}

/** 一次状态变更的落库动作：流水（保留币种）→ 订阅 → 重排提醒（+ 明日计划）。
 *  叫 applyOutcome 而不是 apply：后者会被 Kotlin 的作用域函数 apply 抢解析。 */
suspend fun AppContainer.applyOutcome(outcome: com.subtrack.domain.state.SubscriptionActions.Outcome) {
    awaitSettings()
    val applied = repository.runInTransaction {
        val expected = outcome.expected
        if (expected != null && repository.get(expected.id) != expected) return@runInTransaction false
        repository.recordPayments(outcome.payments)
        repository.upsert(outcome.subscription)
        true
    }
    if (!applied) return
    repository.get(outcome.subscription.id)?.let { current ->
        reminderService.replan(current)
        if (outcome.snoozeRequested && current == outcome.subscription) reminderService.scheduleSnoozeTomorrow(current)
    }
}

class SubTrackApp : Application() {
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
        scope.launch {
            // 先加载设置再规划：提醒时刻（reminderTime）不能先用默认值排一遍。
            // 首次加载完成或提醒相关设置变化时，重排全部提醒并按新时刻重挂每日扫描。
            container.settingsStore.observe().collect { s ->
                if (container.applySettings(s)) {
                    runCatching { container.reminderService.reconcile() }
                    DailySweepScheduler.schedule(this@SubTrackApp, s.reminderTime)
                }
            }
        }
        scope.launch { container.repository.seedIfEmpty() }
        // Q4: translate legacy (schema-4 prefs) unpublished edits into the persistent edit log once per install.
        scope.launch { runCatching { container.syncService.migrateLegacyDisabledState() } }
        scope.launch { com.subtrack.network.AutoFxRefresh.run(container.settingsStore, container.clock) }
        // Both inputs wait for Loaded(settings); publication happens before the refresh can read it.
        scope.launch {
            com.subtrack.widget.refreshWidgets(container.widgetInputs) {
                runCatching { com.subtrack.widget.SubTrackWidget().updateAll(this@SubTrackApp) }
                    .onFailure { android.util.Log.e("SubTrackWidget", "Widget refresh failed", it) }
            }
        }
    }
}
