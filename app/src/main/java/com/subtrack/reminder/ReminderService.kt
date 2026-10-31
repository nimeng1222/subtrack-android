package com.subtrack.reminder

import com.subtrack.core.money.Money
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.ReminderDao
import com.subtrack.data.db.ScheduledReminderEntity
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.repository.SubscriptionRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 提醒的调度中枢。职责：
 * 1. 重排（replan）：按当前订阅重写未来的提醒计划，并为到期当天注册精确闹钟；
 * 2. 扫描（sweep）：把已到点的提醒补发出去，然后重排、清理；
 * 3. 撤销（cancelFor）：订阅被停用/删除时把它所有待发项与系统闹钟撤掉；
 * 4. 精确投递（deliverExact）：到期闹钟触发时领取并发送，与扫描共享幂等状态。
 *
 * 并发约定：replan / sweep / cancelFor / deliverExact 全部串行在同一个 [Mutex] 上。
 * 打开 App、每日扫描、通知动作可能同时进来，没有这把锁时"删旧行 + 插新行"会交错出重复计划。
 *
 * 投递幂等：一条计划要么被精确闹钟、要么被扫描先"领取"（DAO.claimFired 的原子 UPDATE），
 * 领取失败的调用方直接放弃，同一期绝不投两次。已领取（FIRED）的节点在重排时也不再重建。
 *
 * 提醒时刻与默认提前天数来自设置，Planner 本身不硬编码。
 */
class ReminderService(
    private val notifier: ReminderNotifier,
    private val repository: SubscriptionRepository,
    private val reminderDao: ReminderDao,
    private val planner: ReminderPlanner,
    private val scheduler: ReminderScheduler,
    private val clock: AppClock,
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    private val awaitSettings: suspend () -> Unit = {},
    /**
     * N4 reminder-device role: when this returns false (this device is NOT the designated reminder device), reconcile/
     * replan cancel local pending plans instead of scheduling. Default true preserves single-device behaviour.
     */
    private val shouldSchedule: suspend () -> Boolean = { true },
) {

    private val mutex = Mutex()

    /** 全量对账：按当前订阅重排未来 30 天。 */
    suspend fun reconcile() {
        awaitSettings()
        if (!shouldSchedule()) { cancelAllLocked(); return }
        mutex.withLock {
            // O5: sweep PENDING plans whose subscription is gone or soft-deleted. Iterating active subscriptions alone
            // would leave those alarms scheduled forever.
            for (row in reminderDao.pendingAll()) {
                val sub = repository.get(row.subscriptionId)
                if (sub == null || sub.isDeleted) {
                    scheduler.cancel(row.id.toInt())
                    reminderDao.deletePendingById(row.id)
                }
            }
            repository.observeAll().first().forEach { replanLocked(it) }
        }
    }

    suspend fun replan(sub: Subscription) {
        awaitSettings()
        if (!shouldSchedule()) {
            mutex.withLock {
                reminderDao.pendingFor(sub.id).forEach { scheduler.cancel(it.id.toInt()) }
                reminderDao.cancelPendingFor(sub.id)
            }
            return
        }
        mutex.withLock {
            val current = repository.get(sub.id)
            if (current == null || current.isDeleted) {
                reminderDao.pendingFor(sub.id).forEach { scheduler.cancel(it.id.toInt()) }
                reminderDao.cancelPendingFor(sub.id)
            } else {
                replanLocked(current)
            }
        }
    }

    private suspend fun cancelAllLocked() = mutex.withLock {
        // O5: cancel by PLAN rows, not by active subscriptions — deleted/orphan plans must be cancelled too.
        for (row in reminderDao.pendingAll()) {
            scheduler.cancel(row.id.toInt())
            reminderDao.deletePendingById(row.id)
        }
    }

    /** Keep pending identities, recompute their timestamps, retire invalid periods, and re-register alarms.
     * FIRED/MERGED/EXPIRED nodes are not recreated. Historical validity never depends on today's plan window.
     */
    private suspend fun replanLocked(snapshot: Subscription) {
        val sub = normalizeLegacySnooze(snapshot)
        reminderDao.pendingFor(sub.id).forEach { row ->
            scheduler.cancel(row.id.toInt())
            if (!validPeriodNode(sub, row)) {
                reminderDao.retire(row.id, "INVALID")
            } else {
                // Recompute time even when the old timestamp is already due (time/zone changes).
                reminderDao.reschedule(row.id, expectedFireAt(row))
            }
        }

        val doneKeys = reminderDao.allFor(sub.id)
            .filter { it.state in listOf("FIRED", "MERGED", "EXPIRED") }
            .map { Triple(it.kind, it.chargeDateEpochDay, it.offsetDays) }
            .toSet()
        val keptKeys = reminderDao.pendingFor(sub.id)
            .map { Triple(it.kind, it.chargeDateEpochDay, it.offsetDays) }
            .toSet()

        val settings = settingsProvider()
        val fresh = planner.plan(sub, settings).filter { plan ->
            val key = Triple(plan.kind.name, plan.chargeDate.toEpochDay().toInt(), plan.offsetDays)
            key !in doneKeys && key !in keptKeys
        }

        val rows = fresh.map {
            ScheduledReminderEntity(
                subscriptionId = it.subscriptionId,
                kind = it.kind.name,
                offsetDays = it.offsetDays,
                chargeDateEpochDay = it.chargeDate.toEpochDay().toInt(),
                fireAtUtcMillis = it.fireAtMillis,
                createdAtUtcMillis = clock.nowMillis(),
            )
        }
        reminderDao.insertAll(rows)

        // Register retained rows too: cancelling an old alarm must not leave its retained row asleep.
        reminderDao.pendingFor(sub.id).filter { it.kind == ReminderKind.ONSET.name }.forEach { row ->
            scheduler.schedule(row.id.toInt(), row.fireAtUtcMillis, sub.name,
                "${Money.format(sub.priceMinor)} · ${sub.cycle.label()}", sub.id,
                row.chargeDateEpochDay, row.id.toInt(), row.id)
        }
    }

    /**
     * "明天再说"的持久明日计划：明天 reminderTime 发一条待确认提醒。
     * 逾期超过 7 天时规划器不再产出任何节点，明日提醒只能靠这条独立计划兜底；
     * 同一期重复点击先撤旧计划；确认续费/取消后发送前校验会把它作废。
     */
    suspend fun scheduleSnoozeTomorrow(sub: Subscription) {
        awaitSettings()
        mutex.withLock {
            val current = repository.get(sub.id) ?: return
            if (current != sub || current.isDeleted || current.status !in listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL) ||
                current.mutedUntil?.let { it >= (current.currentChargeDate() ?: return) } == true) return
            val tomorrow = clock.today().plusDays(1)
            val chargeDay = currentPeriodDay(sub) ?: clock.today().toEpochDay().toInt()
            reminderDao.cancelSnoozeFor(sub.id, chargeDay)
            reminderDao.insertAll(
                listOf(
                    ScheduledReminderEntity(
                        subscriptionId = sub.id,
                        kind = ReminderKind.SNOOZE.name,
                        offsetDays = 0,
                        chargeDateEpochDay = chargeDay,
                        fireAtUtcMillis = fireAtMillis(tomorrow, settingsProvider().reminderTime),
                        createdAtUtcMillis = clock.nowMillis(),
                    ),
                ),
            )
        }
    }

    /** 订阅被暂停/取消/删除时：撤销它所有已注册的闹钟与 PENDING 行（含明日计划）。 */
    suspend fun cancelFor(subscriptionId: String) {
        awaitSettings()
        mutex.withLock {
            reminderDao.pendingFor(subscriptionId).forEach { scheduler.cancel(it.id.toInt()) }
            reminderDao.cancelPendingFor(subscriptionId)
        }
    }

    /**
     * 每日扫描：补发到点的提醒，然后重排、再补一轮、清理。
     * 第二轮是给"重排刚插入、当下就已到点"的计划用的——典型是逾期第 1 天的补发：
     * 上一个扫描点之后进入逾期的订阅，重排会生成 fireAt=当下 的 FOLLOW_UP 行。
     */
    suspend fun sweep() {
        awaitSettings()
        mutex.withLock {
            fireDue()
            repository.observeAll().first().forEach { replanLocked(it) }
            fireDue()
            reminderDao.cleanup(clock.nowMillis() - 30L * 24 * 60 * 60 * 1000)
        }
    }

    /** Exact and legacy triggers share the same database claim and recovery policy as sweeps. */
    suspend fun deliverExact(reminderRowId: Long) {
        awaitSettings()
        mutex.withLock {
            val row = reminderDao.byId(reminderRowId) ?: return
            deliverRows(listOf(row))
        }
    }

    suspend fun deliverLegacy(subscriptionId: String, chargeDay: Int) {
        awaitSettings()
        mutex.withLock {
            val sub = repository.get(subscriptionId)?.let { normalizeLegacySnooze(it) } ?: return
            if (sub.currentChargeDate()?.toEpochDay() != chargeDay.toLong() || sub.isDeleted) return
            replanLocked(sub)
            // Upgrade intents represent ONSET. Missing rows may be reconstructed, never directly shown.
            var row = reminderDao.allFor(sub.id).firstOrNull {
                it.kind == "ONSET" && it.chargeDateEpochDay == chargeDay
            }
            if (row == null && !LocalDate.ofEpochDay(chargeDay.toLong()).isAfter(clock.today())) {
                val candidate = ScheduledReminderEntity(subscriptionId = sub.id, kind = "ONSET", offsetDays = 0,
                    chargeDateEpochDay = chargeDay, fireAtUtcMillis = fireAtMillis(LocalDate.ofEpochDay(chargeDay.toLong()), settingsProvider().reminderTime),
                    createdAtUtcMillis = clock.nowMillis())
                if (!validPeriodNode(sub, candidate)) return
                val id = reminderDao.insertAll(listOf(candidate)).single()
                row = candidate.copy(id = id)
            }
            if (row != null) deliverRows(listOf(row))
        }
    }

    /** 立即发一条测试提醒（AC-28a 自检卡用）。 */
    suspend fun sendTestNotification() {
        val sub = repository.observeAll().first().firstOrNull()
        notifier.show(
            title = "测试提醒",
            body = sub?.let { "如果你看到这条，提醒功能正常（示例：${it.name} ${Money.format(it.priceMinor)}）" }
                ?: "如果你看到这条，提醒功能正常",
            // 用哨兵 id 而不是真实订阅 id：测试通知上的「已续费/明天再说/不再提醒」
            // 绝不能改动真实数据。用户点它只是为了验证通知能弹出来，
            // 若拿着真实 id + chargeDate=1970，会写一笔 1970 年的假流水并推进下次扣费日。
            subscriptionId = TEST_SUBSCRIPTION_ID,
            chargeDateEpochDay = clock.today().toEpochDay().toInt(),
            notificationId = TEST_NOTIFICATION_ID,
        )
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun fireDue() = deliverRows(reminderDao.pendingDue(clock.nowMillis()))

    /** Up to seven days late; merge overdue nodes per subscription/period/day, never flood on boot.
     * DELIVERY markers persist the actual delivery date without changing the database schema.
     */
    private suspend fun deliverRows(rows: List<ScheduledReminderEntity>) {
        repository.runInTransaction { deliverRowsInTransaction(rows) }
    }

    private suspend fun deliverRowsInTransaction(rows: List<ScheduledReminderEntity>) {
        for ((key, group) in rows.filter { it.state == STATE_PENDING }.groupBy { it.subscriptionId to it.chargeDateEpochDay }) {
            val sub = repository.get(key.first)?.let { normalizeLegacySnooze(it) }
            val valid = mutableListOf<ScheduledReminderEntity>()
            for (row in group.sortedBy { it.fireAtUtcMillis }) {
                if (sub == null || !validPeriodNode(sub, row)) {
                    reminderDao.retire(row.id, "INVALID")
                    continue
                }
                val effective = expectedFireAt(row)
                if (effective != row.fireAtUtcMillis) reminderDao.reschedule(row.id, effective)
                if (effective > clock.nowMillis()) continue
                val age = java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.Instant.ofEpochMilli(effective).atZone(ZoneId.systemDefault()).toLocalDate(), clock.today())
                if (age > 7) { reminderDao.retire(row.id, "EXPIRED"); continue }
                valid += row.copy(fireAtUtcMillis = effective)
            }
            if (sub == null || valid.isEmpty()) continue
            val history = reminderDao.allFor(sub.id)
            val sentToday = history.any { it.kind == "DELIVERY" && it.chargeDateEpochDay == key.second &&
                java.time.Instant.ofEpochMilli(it.fireAtUtcMillis).atZone(ZoneId.systemDefault()).toLocalDate() == clock.today() }
            // A pending/fired snooze defers ordinary reminders until its chosen day, then merges them.
            val snooze = history.filter { it.kind == "SNOOZE" && it.chargeDateEpochDay == key.second && it.state in listOf("PENDING", "FIRED") }
                .maxByOrNull { it.createdAtUtcMillis }
            val deferred = snooze != null && expectedFireAt(snooze) > clock.nowMillis()
            if (deferred) continue
            val selected = valid.firstOrNull { it.kind == "SNOOZE" } ?: valid.first()
            if (!sentToday && reminderDao.claimFired(selected.id) == 1) {
                reminderDao.insertAll(listOf(ScheduledReminderEntity(subscriptionId = sub.id, kind = "DELIVERY", offsetDays = 0,
                    chargeDateEpochDay = key.second, fireAtUtcMillis = clock.nowMillis(), state = "FIRED", createdAtUtcMillis = clock.nowMillis())))
                val kind = ReminderKind.valueOf(selected.kind)
                notifier.show(if (selected.chargeDateEpochDay < clock.today().toEpochDay()) "${sub.name} 待确认" else notificationTitle(sub, selected.offsetDays, kind),
                    notificationBody(sub, kind), sub.id, selected.chargeDateEpochDay, selected.id.toInt())
            }
            valid.filter { sentToday || it.id != selected.id }.forEach { reminderDao.retire(it.id, "MERGED") }
        }
    }

    /** Lazy, idempotent data compatibility: run before either replan or delivery can retire the old row.
     * Re-read inside a Room transaction so a concurrent explicit mute can never be cleared by an old snapshot.
     */
    private suspend fun normalizeLegacySnooze(snapshot: Subscription): Subscription {
        if (snapshot.mutedUntil == null) return snapshot
        return repository.runInTransaction {
            val current = repository.get(snapshot.id) ?: return@runInTransaction snapshot
            if (hasLegacySnoozeMute(current, reminderDao.allFor(current.id), clock.nowMillis())) {
                current.copy(mutedUntil = null).also { repository.upsert(it) }
            } else current
        }
    }

    /** Period validity is independent of today's future planning window. */
    private fun validPeriodNode(sub: Subscription, row: ScheduledReminderEntity): Boolean {
        if (sub.isDeleted || sub.status !in listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL)) return false
        val current = sub.currentChargeDate() ?: return false
        val charge = LocalDate.ofEpochDay(row.chargeDateEpochDay.toLong())
        if (charge < current || sub.mutedUntil?.let { it >= charge } == true) return false
        if (charge != current) {
            if (sub.cycle == com.subtrack.domain.model.BillingCycleType.ONE_TIME || current < clock.today()) return false
            if (com.subtrack.domain.cycle.CycleEngine.occurrenceAtOrAfter(sub.anchorDate, sub.cycle, sub.customCycleDays, charge) != charge) return false
        }
        return when (row.kind) {
            "SNOOZE" -> charge == current
            "FOLLOW_UP" -> charge == current && row.offsetDays in listOf(1, 3, 7) && sub.reminderOffsets.isNotEmpty()
            "ONSET" -> row.offsetDays == 0 && 0 in sub.reminderOffsets
            "ADVANCE" -> row.offsetDays > 0 && row.offsetDays in sub.reminderOffsets
            else -> false
        }
    }

    private fun expectedFireAt(row: ScheduledReminderEntity): Long {
        if (row.kind == "SNOOZE") {
            val requestedDate = java.time.Instant.ofEpochMilli(row.createdAtUtcMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            return fireAtMillis(requestedDate.plusDays(1), settingsProvider().reminderTime)
        }
        val charge = LocalDate.ofEpochDay(row.chargeDateEpochDay.toLong())
        val date = if (row.kind == "FOLLOW_UP") charge.plusDays(row.offsetDays.toLong()) else charge.minusDays(row.offsetDays.toLong())
        return fireAtMillis(date, settingsProvider().reminderTime)
    }

    private fun currentPeriodDay(sub: Subscription): Int? = sub.currentChargeDate()?.toEpochDay()?.toInt()

    private fun fireAtMillis(date: LocalDate, time: java.time.LocalTime): Long =
        date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun notificationTitle(sub: Subscription, offsetDays: Int, kind: ReminderKind): String = when (kind) {
        ReminderKind.ONSET -> "${sub.name} 今天到期"
        ReminderKind.ADVANCE -> if (offsetDays > 0) "${sub.name} 还有 $offsetDays 天到期" else "${sub.name} 今天到期"
        ReminderKind.FOLLOW_UP -> "${sub.name} 已过期 $offsetDays 天"
        ReminderKind.SNOOZE -> "${sub.name} 待确认"
    }

    private fun notificationBody(sub: Subscription, kind: ReminderKind): String = when (kind) {
        ReminderKind.FOLLOW_UP -> "${Money.format(sub.priceMinor)} 可能已扣，点开确认"
        ReminderKind.SNOOZE -> "昨天你说今天再说：${Money.format(sub.priceMinor)} 待确认"
        else -> "${Money.format(sub.priceMinor)} · ${sub.cycle.label()}"
    }

    companion object {
        const val TEST_NOTIFICATION_ID = 990_001

        /** 测试通知专用的订阅 id 哨兵。ActionReceiver 见到它只关通知，不碰数据。 */
        const val TEST_SUBSCRIPTION_ID = "__test_notification__"

        private const val STATE_PENDING = "PENDING"
    }
}
