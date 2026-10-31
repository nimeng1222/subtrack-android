package com.subtrack.reminder

import com.subtrack.core.time.AppClock
import com.subtrack.data.db.ReminderDao
import com.subtrack.data.db.ScheduledReminderEntity
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.repository.SubscriptionRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * ReminderService 的调度状态机回归（JVM 替身：内存 DAO + 记录型调度器/通知器 + 假仓库）。
 * 对应本轮审查：#2 重排不积累/停用即撤销、#3 当天未发送不删、#12 已发送不重建、
 * #13 明日计划可恢复可去重、发送前校验无效计划。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReminderServiceTest {

    private val today = LocalDate.of(2026, 10, 27)
    private val zone = ZoneId.systemDefault()
    private val nowMillis = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    private var currentDate = today
    private var currentMillis = nowMillis
    private val clock = object : AppClock {
        override fun today() = currentDate
        override fun nowMillis() = currentMillis
        override fun reminderTime() = LocalTime.of(9, 0)
    }

    /** 内存版 scheduled_reminder。 */
    private class FakeReminderDao : ReminderDao {
        val rows = LinkedHashMap<Long, ScheduledReminderEntity>()
        private var nextId = 100L

        override suspend fun insertAll(items: List<ScheduledReminderEntity>): List<Long> =
            items.map { val id = ++nextId; rows[id] = it.copy(id = id); id }

        override suspend fun reschedule(id: Long, time: Long) { rows[id]?.let { rows[id] = it.copy(fireAtUtcMillis = time) } }
        override suspend fun retire(id: Long, state: String) { rows[id]?.takeIf { it.state == "PENDING" }?.let { rows[id] = it.copy(state = state) } }

        override suspend fun byId(id: Long): ScheduledReminderEntity? = rows[id]

        override suspend fun pendingFor(subscriptionId: String): List<ScheduledReminderEntity> =
            rows.values.filter { it.subscriptionId == subscriptionId && it.state == "PENDING" }

        override suspend fun allFor(subscriptionId: String): List<ScheduledReminderEntity> =
            rows.values.filter { it.subscriptionId == subscriptionId }

        override suspend fun pendingAll(): List<ScheduledReminderEntity> = rows.values.filter { it.state == "PENDING" }

        override suspend fun deletePendingById(id: Long) {
            rows[id]?.takeIf { it.state == "PENDING" }?.let { rows.remove(id) }
        }

        override suspend fun cancelPendingFor(subscriptionId: String) {
            rows.entries.removeAll { it.value.subscriptionId == subscriptionId && it.value.state == "PENDING" }
        }

        override suspend fun cancelFutureRecurringFor(subscriptionId: String, now: Long) {
            rows.entries.removeAll {
                it.value.subscriptionId == subscriptionId &&
                    it.value.state == "PENDING" && it.value.kind != "SNOOZE" &&
                    it.value.fireAtUtcMillis > now
            }
        }

        override suspend fun cancelSnoozeFor(subscriptionId: String, chargeDay: Int) {
            rows.entries.removeAll {
                it.value.subscriptionId == subscriptionId &&
                    it.value.kind == "SNOOZE" && it.value.chargeDateEpochDay == chargeDay &&
                    it.value.state == "PENDING"
            }
        }

        override suspend fun pendingAfter(now: Long): List<ScheduledReminderEntity> =
            rows.values.filter { it.state == "PENDING" && it.fireAtUtcMillis > now }

        override suspend fun pendingDue(now: Long): List<ScheduledReminderEntity> =
            rows.values.filter { it.state == "PENDING" && it.fireAtUtcMillis <= now }

        override suspend fun markFired(id: Long) {
            rows[id] = rows[id]!!.copy(state = "FIRED")
        }

        override suspend fun claimFired(id: Long): Int {
            val row = rows[id] ?: return 0
            if (row.state != "PENDING") return 0
            rows[id] = row.copy(state = "FIRED")
            return 1
        }

        override suspend fun cleanup(before: Long) {
            rows.entries.removeAll { it.value.state != "PENDING" && it.value.fireAtUtcMillis < before }
        }
    }

    /** 记录 schedule/cancel 调用的假调度器。 */
    private class FakeScheduler : ReminderScheduler {
        val scheduled = mutableMapOf<Int, Long>()
        val cancelled = mutableSetOf<Int>()

        override fun schedule(
            reminderId: Int,
            fireAtMillis: Long,
            title: String,
            body: String,
            subscriptionId: String,
            chargeDateEpochDay: Int,
            notificationId: Int,
            reminderRowId: Long,
        ) {
            scheduled[reminderId] = fireAtMillis
        }

        override fun cancel(reminderId: Int) {
            cancelled += reminderId
            scheduled.remove(reminderId)
        }
    }

    /** 记录投递的假通知器：断言"发了什么"而不依赖 Android。 */
    private class FakeNotifier : ReminderNotifier {
        val delivered = mutableListOf<Pair<String, Int>>() // (subscriptionId, notificationId)
        override fun show(title: String, body: String, subscriptionId: String, chargeDateEpochDay: Int, notificationId: Int) {
            delivered += subscriptionId to notificationId
        }
    }

    private class FakeRepository(initial: List<Subscription>) : SubscriptionRepository {
        val subs = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<Subscription>> = subs
        override suspend fun get(id: String): Subscription? = subs.value.firstOrNull { it.id == id }
        override suspend fun upsert(subscription: Subscription) {
            subs.value = subs.value.filterNot { it.id == subscription.id } + subscription
        }
        override suspend fun archive(id: String) = Unit
        override suspend fun recordPayment(subscriptionId: String, paidAt: LocalDate, amountMinor: Long) = Unit
        override suspend fun recordPayments(records: List<com.subtrack.domain.repository.PaymentRecord>) = Unit
        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        override suspend fun totalPaid(subscriptionId: String): Long = 0L
        override suspend fun paymentsSummary(from: LocalDate, to: LocalDate): Pair<Long, Int> = 0L to 0
        override suspend fun paymentsBetween(from: LocalDate, to: LocalDate) = emptyList<com.subtrack.domain.repository.PaymentRecord>()
        override suspend fun seedIfEmpty() = Unit
        override suspend fun firstPaymentDate(): LocalDate? = null
        override suspend fun paymentsOf(subscriptionId: String) = emptyList<com.subtrack.domain.repository.PaymentRecord>()
        override suspend fun allPayments() = emptyList<com.subtrack.domain.repository.PaymentRecord>()
        override suspend fun insertPayments(records: List<com.subtrack.domain.repository.PaymentRecord>) = Unit
        override suspend fun allIds(): Set<String> = subs.value.map { it.id }.toSet()
        override suspend fun observeAllSnapshot(): List<Subscription> = subs.value
        override suspend fun lastPaidAmounts(): Map<String, Long> = emptyMap()
    }

    private fun sub(
        next: LocalDate = today.plusDays(1),
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    ) = Subscription(
        id = "s1", name = "网易云", categoryId = "music",
        cycle = BillingCycleType.MONTHLY, anchorDate = today.minusMonths(1),
        nextChargeDate = next, priceMinor = 1500L, status = status,
    )

    private class Ctx(
        val svc: ReminderService,
        val dao: FakeReminderDao,
        val notifier: FakeNotifier,
        val repo: FakeRepository,
    )

    private fun harness(repoSubs: List<Subscription>): Ctx {
        val dao = FakeReminderDao()
        val notifier = FakeNotifier()
        val repo = FakeRepository(repoSubs)
        val svc = ReminderService(
            notifier = notifier,
            repository = repo,
            reminderDao = dao,
            planner = ReminderPlanner(clock),
            scheduler = FakeScheduler(),
            clock = clock,
            settingsProvider = { AppSettings() },
        )
        return Ctx(svc, dao, notifier, repo)
    }

    // ────────────── #2 连续重排不积累 ──────────────

    @Test
    fun `连续重排不积累计划`() = runTest {
        val s = sub(next = today.plusDays(1)) // 只会产出 1 条 ONSET
        val ctx = harness(listOf(s)); val svc = ctx.svc; val dao = ctx.dao

        svc.replan(s)
        val afterFirst = dao.rows.size
        assertEquals(1, afterFirst)

        repeat(3) { svc.replan(s) }
        assertEquals("重排必须替换而不是追加计划", afterFirst, dao.rows.size)
    }

    @Test
    fun `暂停订阅被cancelFor清空后_扫描不再投递`() = runTest {
        val active = sub(next = today) // 今天到期 → 有一条 ONSET
        val ctx = harness(listOf(active)); val svc = ctx.svc; val dao = ctx.dao; val notifier = ctx.notifier
        svc.replan(active)
        assertTrue(dao.pendingFor(active.id).isNotEmpty())

        // 暂停 = 状态更新 + cancelFor（与 App 侧 apply 后的撤销动作一致）
        ctx.repo.subs.value = listOf(active.copy(status = SubscriptionStatus.PAUSED))
        svc.cancelFor(active.id)
        assertTrue("暂停/取消/删除后不应残留 PENDING 计划", dao.pendingFor(active.id).isEmpty())
        svc.sweep()
        assertEquals("已撤销的计划不得再投递", 0, notifier.delivered.size)
    }

    // ────────────── #3 当天未发送的提醒不被重排删除 ──────────────

    @Test
    fun `重排保留当天已到点但未发送的补发`() = runTest {
        // 已过期 1 天：第 1 天补发的触发时刻就是"现在"，第 3/7 天在未来
        val overdue = sub(next = today.minusDays(1))
        val ctx = harness(listOf(overdue)); val svc = ctx.svc; val dao = ctx.dao

        svc.replan(overdue)
        val day1 = dao.pendingFor(overdue.id).filter { it.kind == "FOLLOW_UP" }.single { it.offsetDays == 1 }
        assertTrue("当天节点的触发时刻已到点", day1.fireAtUtcMillis <= nowMillis)

        // 用户此刻打开 App / 编辑 → 再次重排：当天未发送的节点必须还在
        svc.replan(overdue)
        val kept = dao.pendingFor(overdue.id).filter { it.kind == "FOLLOW_UP" }
        assertTrue(
            "当天未发送的补发不得被重排删除",
            kept.any { it.offsetDays == 1 && it.fireAtUtcMillis <= nowMillis },
        )
    }

    @Test
    fun `扫描会补发当天到点的补发节点`() = runTest {
        val overdue = sub(next = today.minusDays(1))
        val ctx = harness(listOf(overdue)); val svc = ctx.svc; val dao = ctx.dao; val notifier = ctx.notifier
        svc.replan(overdue)
        assertTrue(notifier.delivered.isEmpty()) // 规划本身不投递

        svc.sweep()
        assertEquals("扫描必须把当天到点的第 1 天补发发出去", 1, notifier.delivered.size)
        assertTrue(dao.pendingFor(overdue.id).none { it.kind == "FOLLOW_UP" && it.fireAtUtcMillis <= nowMillis })
    }

    // ────────────── #12 已发送节点不再重建 ──────────────

    @Test
    fun `已FIRED的节点在重排后不重建且不重复投递`() = runTest {
        val s = sub(next = today) // 今天到期
        val ctx = harness(listOf(s)); val svc = ctx.svc; val dao = ctx.dao; val notifier = ctx.notifier
        svc.replan(s)
        val onset = dao.pendingFor(s.id).single { it.kind == "ONSET" }

        // 精确闹钟投递（领取）
        svc.deliverExact(onset.id)
        assertEquals(1, notifier.delivered.size)

        // 当天稍后编辑订阅 → 重排：FIRED 的 ONSET 不得重建（否则次日扫描再发一遍）
        svc.replan(s.copy(name = "网易云改名"))
        assertTrue(dao.pendingFor(s.id).none { it.kind == "ONSET" })

        // 再扫描一轮：也不得重复投递
        svc.sweep()
        assertEquals(1, notifier.delivered.size)
    }

    @Test
    fun `精确投递与扫描并发只投一次`() = runTest {
        val s = sub(next = today)
        val ctx = harness(listOf(s)); val svc = ctx.svc; val dao = ctx.dao; val notifier = ctx.notifier
        svc.replan(s)
        val onset = dao.pendingFor(s.id).single { it.kind == "ONSET" }

        // 同一条计划被两条路径各领取一次：只有先到的投递
        svc.deliverExact(onset.id)
        svc.sweep()
        assertEquals(1, notifier.delivered.size)
    }

    // ────────────── #13 明日计划 ──────────────

    @Test
    fun `明日计划可建立_同一期去重_重排保留`() = runTest {
        val overdue = sub(next = today.minusDays(10)) // 逾期 >7 天：规划器不再产出任何节点
        val ctx = harness(listOf(overdue)); val svc = ctx.svc; val dao = ctx.dao
        svc.replan(overdue)
        assertTrue("逾期超过 7 天规划器为空，明日提醒必须由 SNOOZE 行承载", dao.pendingFor(overdue.id).isEmpty())

        svc.scheduleSnoozeTomorrow(overdue)
        val snooze = dao.pendingFor(overdue.id).filter { it.kind == "SNOOZE" }
        assertEquals(1, snooze.size)
        val fireDate = Instant.ofEpochMilli(snooze.single().fireAtUtcMillis).atZone(zone).toLocalDate()
        assertEquals("明日计划必须落在明天", today.plusDays(1), fireDate)

        // 重复点"明天再说"：同一期只有一条
        svc.scheduleSnoozeTomorrow(overdue)
        assertEquals(1, dao.pendingFor(overdue.id).filter { it.kind == "SNOOZE" }.size)

        // 反复重排不得删掉明日计划
        svc.replan(overdue)
        assertEquals(1, dao.pendingFor(overdue.id).filter { it.kind == "SNOOZE" }.size)
    }

    @Test
    fun `明日计划到期会投递一次`() = runTest {
        val overdue = sub(next = today.minusDays(10))
        val ctx = harness(listOf(overdue)); val svc = ctx.svc; val dao = ctx.dao; val notifier = ctx.notifier
        svc.scheduleSnoozeTomorrow(overdue)
        svc.sweep() // 今天的扫描：SNOOZE 行 fireAt 是明天 → 不投
        assertEquals(0, notifier.delivered.size)

        // Advance the injected clock: recovery must use the persisted requested day, not a forged timestamp.
        currentDate = today.plusDays(1)
        currentMillis = currentDate.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        svc.sweep()
        assertEquals(1, notifier.delivered.size)
        // 再扫一遍：不重复
        svc.sweep()
        assertEquals(1, notifier.delivered.size)
    }

    // ────────────── 发送前校验 ──────────────

    @Test
    fun `确认续费后旧明日计划失效不投递`() = runTest {
        val overdue = sub(next = today.minusDays(10))
        val ctx = harness(listOf(overdue)); val svc = ctx.svc; val dao = ctx.dao; val notifier = ctx.notifier
        svc.scheduleSnoozeTomorrow(overdue)
        val row = dao.pendingFor(overdue.id).filter { it.kind == "SNOOZE" }.single()
        dao.rows[row.id] = row.copy(fireAtUtcMillis = nowMillis - 1)

        // 用户确认续费：nextChargeDate 推进到未来 → 期次已变
        ctx.repo.subs.value = listOf(overdue.copy(nextChargeDate = today.plusDays(20)))
        svc.sweep()
        assertEquals("期次已处理的明日计划不得投递", 0, notifier.delivered.size)
    }

    @Test
    fun `已暂停订阅的到期行被扫描消耗而不投递`() = runTest {
        val paused = sub(next = today, status = SubscriptionStatus.PAUSED)
        val ctx = harness(listOf(paused)); val svc = ctx.svc; val dao = ctx.dao; val notifier = ctx.notifier
        // 直接种一条"今天到期"的 PENDING 行（模拟暂停前留下的计划）
        dao.insertAll(
            listOf(
                ScheduledReminderEntity(
                    subscriptionId = paused.id, kind = "ONSET", offsetDays = 0,
                    chargeDateEpochDay = today.toEpochDay().toInt(),
                    fireAtUtcMillis = nowMillis - 1, createdAtUtcMillis = nowMillis,
                ),
            ),
        )
        svc.sweep()
        assertEquals(0, notifier.delivered.size)
        assertEquals("未投递的无效计划不是已发送", "INVALID", dao.rows.values.single().state)
    }

    @Test
    fun acceptance_mute_must_cancel_existing_snooze() = runTest {
        val overdue = sub(next = today.minusDays(10))
        val ctx = harness(listOf(overdue))
        ctx.svc.scheduleSnoozeTomorrow(overdue)
        val row = ctx.dao.pendingFor(overdue.id).single()
        ctx.dao.rows[row.id] = row.copy(fireAtUtcMillis = nowMillis - 1)
        val muted = com.subtrack.domain.state.SubscriptionActions.muteThisCycle(overdue, overdue.nextChargeDate!!).subscription
        ctx.repo.subs.value = listOf(muted)
        ctx.svc.replan(muted)
        ctx.svc.sweep()
        assertEquals("Muted period must suppress existing snooze", 0, ctx.notifier.delivered.size)
    }

    @Test
    fun acceptance_sweep_must_recover_yesterday_persisted_onset() = runTest {
        val overdue = sub(next = today.minusDays(1))
        val ctx = harness(listOf(overdue))
        val yesterdayOnset = ScheduledReminderEntity(
            subscriptionId = overdue.id, kind = "ONSET", offsetDays = 0,
            chargeDateEpochDay = overdue.nextChargeDate!!.toEpochDay().toInt(),
            fireAtUtcMillis = nowMillis - 24L * 60 * 60 * 1000,
            createdAtUtcMillis = nowMillis - 48L * 60 * 60 * 1000)
        val id = ctx.dao.insertAll(listOf(yesterdayOnset)).single()
        ctx.svc.sweep()
        assertTrue("Persisted missed onset should be recovered", ctx.notifier.delivered.any { it.second == id.toInt() })
    }

    @Test
    fun acceptance_muting_current_period_must_not_mute_next_period_advance() = runTest {
        val s = sub(next = today.plusDays(1)).copy(cycle = BillingCycleType.WEEKLY, anchorDate = today.plusDays(1).minusWeeks(4), reminderOffsets = listOf(7, 0))
        val muted = com.subtrack.domain.state.SubscriptionActions.muteThisCycle(s, s.nextChargeDate!!).subscription
        val next = com.subtrack.domain.cycle.CycleEngine.nextOccurrenceAfter(s.anchorDate, s.cycle, null, s.nextChargeDate!!)!!
        val plans = ReminderPlanner(clock).plan(muted)
        assertTrue("Next cycle advance reminder must survive", plans.any { it.chargeDate == next && it.offsetDays == 7 })
        val ctx = harness(listOf(muted))
        ctx.svc.replan(muted)
        currentDate = today.plusDays(1)
        currentMillis = currentDate.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        ctx.svc.sweep()
        assertEquals(1, ctx.notifier.delivered.size)
        val delivered = ctx.dao.byId(ctx.notifier.delivered.single().second.toLong())!!
        assertEquals(next.toEpochDay().toInt(), delivered.chargeDateEpochDay)
        assertEquals("ADVANCE", delivered.kind)
    }

    @Test
    fun acceptance_changing_reminder_time_must_not_send_earlier_kept_row() = runTest {
        val s = sub(next = today)
        val dao = FakeReminderDao()
        val notifier = FakeNotifier()
        val repo = FakeRepository(listOf(s))
        var settings = AppSettings(reminderTime = LocalTime.of(8, 0))
        val svc = ReminderService(notifier, repo, dao, ReminderPlanner(clock), FakeScheduler(), clock, { settings })
        svc.replan(s)
        settings = settings.copy(reminderTime = LocalTime.of(18, 0))
        svc.reconcile()
        svc.sweep()
        assertEquals("New 18:00 setting must not deliver stale 08:00 row at 09:00", 0, notifier.delivered.size)
    }



    @Test fun time_change_delivers_at_new_time_once_and_never_before() = runTest {
        var now = nowMillis
        val moving = object : AppClock {
            override fun today() = today
            override fun nowMillis() = now
            override fun reminderTime() = LocalTime.of(9, 0)
        }
        val repo = FakeRepository(listOf(sub(next = today)))
        val dao = FakeReminderDao(); val notify = FakeNotifier()
        var settings = AppSettings(reminderTime = LocalTime.of(8, 0))
        val svc = ReminderService(notify, repo, dao, ReminderPlanner(moving), FakeScheduler(), moving, { settings })
        svc.reconcile()
        val id = dao.pendingFor("s1").first { it.kind == "ONSET" }.id
        settings = settings.copy(reminderTime = LocalTime.of(18, 0))
        svc.deliverExact(id) // A late old alarm races the settings observer.
        assertTrue(notify.delivered.isEmpty())
        svc.reconcile()
        now = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        svc.sweep(); svc.deliverExact(id); svc.sweep()
        assertEquals(1, notify.delivered.size)
    }

    @Test fun recovery_day_without_followup_merges_old_nodes_once() = runTest {
        val s = sub(next = today.minusDays(2))
        val ctx = harness(listOf(s))
        val rows = listOf(0 to "ONSET", 1 to "FOLLOW_UP").map { (offset, kind) ->
            ScheduledReminderEntity(subscriptionId = s.id, kind = kind, offsetDays = offset,
                chargeDateEpochDay = s.nextChargeDate!!.toEpochDay().toInt(),
                fireAtUtcMillis = nowMillis - (2 - offset) * 86400000L, createdAtUtcMillis = nowMillis - 3 * 86400000L)
        }
        val first = ctx.dao.insertAll(rows).first()
        ctx.svc.reconcile(); ctx.svc.sweep(); ctx.svc.sweep()
        assertEquals(listOf(s.id to first.toInt()), ctx.notifier.delivered)
        assertTrue(ctx.dao.rows.values.any { it.kind == "FOLLOW_UP" && it.offsetDays == 1 && it.state == "MERGED" })
    }

    @Test fun expired_recovery_nodes_are_explicitly_expired_without_flooding() = runTest {
        val s = sub(next = today.minusDays(20)); val ctx = harness(listOf(s))
        ctx.dao.insertAll(listOf(ScheduledReminderEntity(subscriptionId = s.id, kind = "ONSET", offsetDays = 0,
            chargeDateEpochDay = s.nextChargeDate!!.toEpochDay().toInt(), fireAtUtcMillis = nowMillis - 20 * 86400000L, createdAtUtcMillis = nowMillis)))
        ctx.svc.sweep()
        assertTrue(ctx.notifier.delivered.isEmpty())
        assertEquals("EXPIRED", ctx.dao.rows.values.first().state)
    }

    @Test fun legacy_intents_reuse_claim_and_reject_inactive_and_old_periods() = runTest {
        for (status in SubscriptionStatus.entries) {
            val s = sub(next = today, status = status); val ctx = harness(listOf(s))
            ctx.svc.deliverLegacy(s.id, today.minusMonths(1).toEpochDay().toInt())
            assertTrue(ctx.notifier.delivered.isEmpty())
            repeat(2) { ctx.svc.deliverLegacy(s.id, today.toEpochDay().toInt()) }
            assertEquals(if (status in listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL)) 1 else 0, ctx.notifier.delivered.size)
        }
        val s = sub(next = today).copy(deletedAtUtcMillis = 1); val ctx = harness(listOf(s))
        ctx.svc.deliverLegacy(s.id, today.toEpochDay().toInt())
        assertTrue(ctx.notifier.delivered.isEmpty())
    }

    @Test fun all_service_entrypoints_wait_for_settings_on_cold_start() = runTest {
        val gate = com.subtrack.data.settings.LoadedSettings()
        val repo = FakeRepository(listOf(sub(next = today)))
        val dao = FakeReminderDao(); val notify = FakeNotifier()
        val svc = ReminderService(notify, repo, dao, ReminderPlanner(clock), FakeScheduler(), clock,
            { gate.current() }, { gate.await() })
        val jobs = listOf(async { svc.sweep() }, async { svc.reconcile() },
            async { svc.deliverExact(1) }, async { svc.deliverLegacy("s1", today.toEpochDay().toInt()) })
        runCurrent()
        assertTrue(dao.rows.isEmpty()); assertTrue(notify.delivered.isEmpty())
        gate.publish(AppSettings(reminderTime = LocalTime.of(18, 0)))
        jobs.forEach { it.await() }
        assertTrue(notify.delivered.isEmpty())
        assertEquals(today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli(), dao.pendingFor("s1").first().fireAtUtcMillis)
    }

    @Test fun timezone_change_recalculates_pending_but_does_not_resend_fired() = runTest {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val s = sub(next = today.plusDays(1)); val ctx = harness(listOf(s))
            ctx.svc.replan(s)
            val before = ctx.dao.pendingFor(s.id).first().fireAtUtcMillis
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            ctx.svc.reconcile()
            assertEquals(before + 8 * 3600000L, ctx.dao.pendingFor(s.id).first().fireAtUtcMillis)
        } finally { java.util.TimeZone.setDefault(original) }
    }

    @Test fun snooze_tracks_new_time_and_survives_normal_replanning_until_tomorrow() = runTest {
        val s = sub(next = today.minusDays(2)); val repo = FakeRepository(listOf(s))
        val dao = FakeReminderDao(); val notify = FakeNotifier()
        var settings = AppSettings(reminderTime = LocalTime.of(8, 0))
        val svc = ReminderService(notify, repo, dao, ReminderPlanner(clock), FakeScheduler(), clock, { settings })
        val snoozed = com.subtrack.domain.state.SubscriptionActions.snooze(s, today).subscription
        repo.upsert(snoozed); svc.replan(snoozed); svc.scheduleSnoozeTomorrow(snoozed)
        currentDate = today.plusDays(1)
        currentMillis = currentDate.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        settings = settings.copy(reminderTime = LocalTime.of(18, 0))
        svc.sweep()
        assertTrue(notify.delivered.isEmpty())
        currentMillis = currentDate.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        svc.sweep(); svc.sweep()
        assertEquals(1, notify.delivered.size)
        assertTrue(dao.allFor(s.id).any { it.kind == "SNOOZE" && it.state == "FIRED" })
    }
    @Test fun acceptance_v13_persisted_snooze_survives_upgrade() = runTest {
        val old = sub(next = today.minusDays(10)).copy(mutedUntil = today)
        val ctx = harness(listOf(old))
        ctx.dao.insertAll(listOf(ScheduledReminderEntity(subscriptionId=old.id, kind="SNOOZE", offsetDays=0,
            chargeDateEpochDay=old.nextChargeDate!!.toEpochDay().toInt(),
            fireAtUtcMillis=today.plusDays(1).atTime(9,0).atZone(zone).toInstant().toEpochMilli(),
            createdAtUtcMillis=nowMillis)))
        currentDate = today.plusDays(1)
        currentMillis = currentDate.atTime(9,0).atZone(zone).toInstant().toEpochMilli()
        ctx.svc.sweep()
        assertEquals("An existing v13 snooze must still deliver after update",1,ctx.notifier.delivered.size)
        assertEquals(null, ctx.repo.get(old.id)!!.mutedUntil)
        val restarted = ReminderService(ctx.notifier, ctx.repo, ctx.dao, ReminderPlanner(clock), FakeScheduler(), clock)
        repeat(3) { restarted.reconcile(); restarted.sweep() }
        assertEquals(1, ctx.notifier.delivered.size)
        assertEquals(1, ctx.dao.allFor(old.id).count { it.kind == "SNOOZE" && it.state == "FIRED" })
    }

    @Test fun acceptance_pending_rows_retired_by_sweep_can_resume_same_period() = runTest {
        val original = sub(next=today)
        val ctx = harness(listOf(original))
        ctx.svc.replan(original)
        ctx.repo.upsert(original.copy(status=SubscriptionStatus.PAUSED))
        ctx.svc.sweep()
        assertTrue(ctx.dao.allFor(original.id).any { it.kind == "ONSET" && it.state == "INVALID" })
        assertTrue(ctx.notifier.delivered.isEmpty())
        ctx.repo.upsert(original)
        ctx.svc.replan(original)
        ctx.svc.sweep()
        assertEquals("Paused unsent nodes must not become permanently sent",1,ctx.notifier.delivered.size)
        ctx.repo.upsert(original.copy(status = SubscriptionStatus.PAUSED))
        ctx.svc.sweep()
        ctx.repo.upsert(original); ctx.svc.replan(original); ctx.svc.sweep()
        assertEquals("A genuinely sent node must not be resent after resume", 1, ctx.notifier.delivered.size)
    }


    private suspend fun legacySnooze(ctx: Ctx, s: Subscription, charge: LocalDate = s.currentChargeDate()!!,
                                     requested: LocalDate = today) {
        ctx.dao.insertAll(listOf(ScheduledReminderEntity(subscriptionId = s.id, kind = "SNOOZE", offsetDays = 0,
            chargeDateEpochDay = charge.toEpochDay().toInt(),
            fireAtUtcMillis = requested.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            createdAtUtcMillis = requested.atTime(9, 0).atZone(zone).toInstant().toEpochMilli())))
    }

    @Test fun legacy_snooze_followed_by_explicit_mute_is_not_unmuted_on_upgrade() = runTest {
        val old = sub(next = today.minusDays(10)).copy(mutedUntil = today)
        val ctx = harness(listOf(old)); legacySnooze(ctx, old)
        val muted = com.subtrack.domain.state.SubscriptionActions.muteThisCycle(old, old.nextChargeDate!!).subscription
        ctx.repo.upsert(muted)
        currentDate = today.plusDays(1); currentMillis += 86400000L
        repeat(3) { ctx.svc.reconcile(); ctx.svc.sweep() }
        assertTrue(ctx.notifier.delivered.isEmpty())
        assertEquals(old.nextChargeDate, ctx.repo.get(old.id)!!.mutedUntil)
        assertEquals("INVALID", ctx.dao.rows.values.single().state)
    }

    @Test fun unrelated_or_inconsistent_legacy_rows_never_clear_mute() = runTest {
        val old = sub(next = today.minusDays(10)).copy(mutedUntil = today)
        currentDate = today.plusDays(1); currentMillis += 86400000L
        for (scenario in 0..4) {
            val ctx = harness(listOf(old))
            when (scenario) {
                1 -> legacySnooze(ctx, old, charge = old.nextChargeDate!!.minusMonths(1))
                2 -> legacySnooze(ctx, old, requested = today.minusDays(1))
                3 -> legacySnooze(ctx, old, requested = today.plusDays(5))
                4 -> {
                    legacySnooze(ctx, old)
                    val row = ctx.dao.rows.values.single()
                    ctx.dao.rows[row.id] = row.copy(fireAtUtcMillis = row.createdAtUtcMillis - 1)
                }
            }
            ctx.svc.reconcile(); ctx.svc.sweep()
            assertEquals("scenario $scenario", old.mutedUntil, ctx.repo.get(old.id)!!.mutedUntil)
            assertTrue(ctx.notifier.delivered.isEmpty())
        }
    }

    @Test fun same_day_legacy_snooze_and_mute_are_ambiguous_and_remain_muted() = runTest {
        val old = sub(next = today).copy(mutedUntil = today)
        val ctx = harness(listOf(old)); legacySnooze(ctx, old)
        currentDate = today.plusDays(1); currentMillis += 86400000L
        repeat(2) { ctx.svc.reconcile(); ctx.svc.sweep() }
        assertTrue(ctx.notifier.delivered.isEmpty())
        assertEquals(today, ctx.repo.get(old.id)!!.mutedUntil)
    }

    @Test fun legacy_trial_snooze_uses_trial_period_not_next_charge() = runTest {
        val old = sub(next = today.plusDays(5), status = SubscriptionStatus.TRIAL)
            .copy(trialEndDate = today.minusDays(10), mutedUntil = today)
        val ctx = harness(listOf(old)); legacySnooze(ctx, old)
        currentDate = today.plusDays(1); currentMillis += 86400000L
        ctx.svc.sweep(); ctx.svc.sweep()
        assertEquals(1, ctx.notifier.delivered.size)
        assertEquals(null, ctx.repo.get(old.id)!!.mutedUntil)
        assertEquals(old.trialEndDate, ctx.repo.get(old.id)!!.trialEndDate)
    }

    @Test fun removing_offsets_then_restoring_same_period_rebuilds_invalid_unsent_node() = runTest {
        val original = sub(next = today); val ctx = harness(listOf(original))
        ctx.svc.replan(original)
        ctx.repo.upsert(original.copy(reminderOffsets = emptyList()))
        ctx.svc.sweep()
        assertTrue(ctx.notifier.delivered.isEmpty())
        assertTrue(ctx.dao.rows.values.all { it.state == "INVALID" })
        ctx.repo.upsert(original); ctx.svc.replan(original); ctx.svc.sweep(); ctx.svc.sweep()
        assertEquals(1, ctx.notifier.delivered.size)
    }

    @Test fun exact_receiver_retirement_also_allows_resume_but_fired_is_still_idempotent() = runTest {
        val original = sub(next = today); val ctx = harness(listOf(original))
        ctx.svc.replan(original)
        val oldId = ctx.dao.rows.values.single().id
        ctx.repo.upsert(original.copy(status = SubscriptionStatus.PAUSED))
        ctx.svc.deliverExact(oldId)
        assertEquals("INVALID", ctx.dao.byId(oldId)!!.state)
        ctx.repo.upsert(original); ctx.svc.replan(original); ctx.svc.sweep()
        ctx.svc.deliverExact(oldId); ctx.svc.sweep()
        assertEquals(1, ctx.notifier.delivered.size)
    }
}
