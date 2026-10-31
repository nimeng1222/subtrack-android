package com.subtrack.domain

import com.subtrack.core.time.AppClock
import com.subtrack.data.backup.BackupCodec
import com.subtrack.data.backup.BackupFormatException
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Limits
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.state.DisplayStateResolver
import com.subtrack.domain.state.SubscriptionActions
import com.subtrack.domain.state.SubscriptionDisplayState
import com.subtrack.domain.stats.CurrencyConverter
import com.subtrack.domain.stats.MonthlyCashflow
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.domain.repository.PaymentRecord
import com.subtrack.reminder.ReminderKind
import com.subtrack.reminder.ReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 本轮（2026-10-27）审查问题的行为回归。
 * 只断言最终行为，不断言函数存在或常量值；每一组对应审查报告的编号见注释。
 */
class ReviewRegressionTest {

    private val today = LocalDate.of(2026, 10, 27)
    private val clock = object : AppClock {
        override fun today() = today
        override fun nowMillis() = 0L
        override fun reminderTime() = LocalTime.of(9, 0)
    }
    private val planner = ReminderPlanner(clock)

    private fun sub(
        next: LocalDate? = today.plusDays(1),
        priceMinor: Long = 1000L,
        cycle: BillingCycleType = BillingCycleType.MONTHLY,
        customDays: Int? = null,
        currency: String = "CNY",
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    ) = Subscription(
        id = "review",
        name = "Review",
        categoryId = "other",
        cycle = cycle,
        customCycleDays = customDays,
        anchorDate = today.minusMonths(6),
        nextChargeDate = next,
        priceMinor = priceMinor,
        currency = currency,
        status = status,
    )

    // ────────────── #4 / #21 导入合并与空提醒偏移 ──────────────

    @Test
    fun `同ID导入默认跳过并保留本地数据`() {
        val backup = BackupCodec.exportJson(listOf(sub(priceMinor = 1000L)))
        val locally = sub(priceMinor = 2500L).copy(
            nextChargeDate = today.plusDays(20),
            status = SubscriptionStatus.CANCELED,
        )
        val result = BackupCodec.importBackup(backup, existingIds = setOf(locally.id))
        assertTrue(result.subscriptions.isEmpty())
        assertEquals(1, result.skipped)
        assertEquals(0, result.added)
        assertEquals(0, result.corrected)
    }

    @Test
    fun `软删除的ID同样算已存在_导入不得复活或覆盖`() {
        val deleted = sub().copy(deletedAtUtcMillis = 123L)
        val backup = BackupCodec.exportJson(listOf(sub()))
        // 调用方契约：existingIds 必须取自 allIds()（含软删除）
        val result = BackupCodec.importBackup(backup, existingIds = setOf(deleted.id))
        assertTrue("软删除记录不得被备份复活", result.subscriptions.isEmpty())
        assertEquals(1, result.skipped)
    }

    @Test
    fun `合法JSON的空提醒偏移导入后保持为空`() {
        val text = """
            {"formatVersion":2,"app":"SubTrack","exportedAtUtcMillis":0,
             "subscriptions":[{"id":"r1","name":"R","categoryId":"other","cycle":"MONTHLY",
             "anchorDate":"2026-04-27","priceMinor":1000,"reminderOffsets":[]}]}
        """.trimIndent()
        val imported = BackupCodec.importBackup(text).subscriptions.single()
        assertTrue("明确空列表不得被换回默认 [3,0]", imported.reminderOffsets.isEmpty())
        // 空偏移的订阅规划不出任何提醒（用户关闭提醒的意图必须存活）
        assertTrue(planner.plan(imported, AppSettings()).isEmpty())
    }

    // ────────────── #5 导入校验边界 ──────────────

    @Test
    fun `周期超出引擎范围被拒绝_3650为合法上界`() {
        val over = sub(cycle = BillingCycleType.CUSTOM_DAYS, customDays = 3651)
        org.junit.Assert.assertThrows(BackupFormatException::class.java) {
            BackupCodec.importBackup(BackupCodec.exportJson(listOf(over)))
        }
        val edge = sub(cycle = BillingCycleType.CUSTOM_DAYS, customDays = 3650)
        assertEquals(3650, BackupCodec.importBackup(BackupCodec.exportJson(listOf(edge))).subscriptions.single().customCycleDays)
        assertEquals(1, BackupCodec.importBackup(
            BackupCodec.exportJson(listOf(sub(cycle = BillingCycleType.CUSTOM_DAYS, customDays = 1))),
        ).subscriptions.single().customCycleDays)
    }

    @Test
    fun `金额会撑爆年化运算的被拒绝_上限内合法`() {
        val bad = sub(priceMinor = Long.MAX_VALUE)
        org.junit.Assert.assertThrows(BackupFormatException::class.java) {
            BackupCodec.importBackup(BackupCodec.exportJson(listOf(bad)))
        }
        // 上限本身必须能安全年化：MAX_PRICE_MINOR × 365 不溢出 Long
        val edge = sub(priceMinor = Limits.MAX_PRICE_MINOR)
        val parsed = BackupCodec.importBackup(BackupCodec.exportJson(listOf(edge))).subscriptions.single()
        com.subtrack.domain.stats.Annualizer.annualizeMinor(parsed.priceMinor, parsed.cycle, parsed.customCycleDays)
    }

    @Test
    fun `流水金额溢出同样被拒绝`() {
        val text = """
            {"formatVersion":2,"app":"SubTrack","exportedAtUtcMillis":0,
             "subscriptions":[],"payments":[{"id":"p1","subscriptionId":"ghost",
             "paidAt":"2026-10-01","amountMinor":${Long.MAX_VALUE}}]}
        """.trimIndent()
        org.junit.Assert.assertThrows(BackupFormatException::class.java) {
            BackupCodec.importBackup(text)
        }
    }

    @Test
    fun `汇率超出运算上限被丢弃`() {
        val text = """
            {"formatVersion":2,"app":"SubTrack","exportedAtUtcMillis":0,
             "subscriptions":[],"settings":{"multiCurrencyEnabled":true,
             "fxRates":{"USD":${Limits.MAX_FX_RATE + 1},"EUR":${Limits.MAX_FX_RATE}}}}
        """.trimIndent()
        val settings = BackupCodec.importBackup(text).settings
        assertEquals(mapOf("EUR" to Limits.MAX_FX_RATE), settings?.fxRates)
    }

    // ────────────── #13 静音整期 / 明日计划契约 ──────────────

    @Test
    fun `静音本期覆盖全部补发且不影响下一期`() {
        val due = today.minusDays(1)
        val muted = SubscriptionActions.muteThisCycle(sub(next = due), due).subscription
        assertTrue("静音后该期不应再有任何计划（含第 1/3/7 天补发）", planner.plan(muted).isEmpty())
        // 下一期（未静音）：恢复后下一期的到期提醒照常规划
        val nextPeriod = muted.copy(nextChargeDate = today.plusDays(10), mutedUntil = null)
        assertTrue(planner.plan(nextPeriod).isNotEmpty())
    }

    @Test
    fun `逾期第1天仍规划当天补发`() {
        val plans = planner.plan(sub(next = today.minusDays(1)))
        val day1 = plans.filter { it.kind == ReminderKind.FOLLOW_UP && it.offsetDays == 1 }
        assertTrue("第 1 天补发在逾期第 1 天当天必须存在", day1.isNotEmpty())
        // 且触发时刻就是今天（当天扫描/打开 App 就能发出去，而不是拖到次日）
        assertEquals(
            today,
            Instant.ofEpochMilli(day1.single().fireAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
        )
    }

    @Test
    fun `逾期第3天补发当天与第7天`() {
        val plans = planner.plan(sub(next = today.minusDays(3)))
        assertEquals(listOf(3, 7), plans.filter { it.kind == ReminderKind.FOLLOW_UP }.map { it.offsetDays })
    }

    // ────────────── #14 统一折算口径 ──────────────

    @Test
    fun `现金流与流水汇总的折算口径一致且缺汇率不硬加`() {
        val usd = sub(priceMinor = 10_00L, currency = "USD")
        val fx = mapOf("USD" to 720L)
        // 本月将扣：10 美元 × 7.20 = 72 元 = 7200 分
        assertEquals(7200L, MonthlyCashflow.dueThisMonthMinor(listOf(usd), today, fx))
        // 缺汇率：排除而不是当人民币硬算
        assertEquals(0L, MonthlyCashflow.dueThisMonthMinor(listOf(usd), today, emptyMap()))
        // 流水同口径
        val records = listOf(
            PaymentRecord("p1", "review", today, 10_00L, "USD"),
            PaymentRecord("p2", "review", today, 5_00L, "EUR"),
        )
        assertEquals(7200L to 1, CurrencyConverter.paymentsCnySummary(records, fx))
        // 未启用多币种：完全按原样
        assertEquals(15_00L to 2, CurrencyConverter.paymentsCnySummary(records, null))
    }

    @Test
    fun `月均明细与统计卡在多币种下同口径`() {
        val subs = listOf(sub(priceMinor = 10_00L, currency = "USD"), sub(priceMinor = 30_00L))
        val fx = mapOf("USD" to 720L)
        val snapshot = StatsEngine.snapshot(subs, today, 0, 0, 0, 0, null, fx)
        assertEquals(snapshot.forecastMonthlyMinor, StatsEngine.monthlyBreakdown(subs, fx).totalMinor)
        assertEquals(snapshot.forecastAnnualMinor, StatsEngine.annualBreakdown(subs, fx).totalMinor)
    }

    // ────────────── #16 预算口径 ──────────────

    @Test
    fun `预算占用为本月已付加待扣`() {
        val status = com.subtrack.domain.stats.BudgetWatch.status(
            budgetMinor = 80_00L,
            monthSpentMinor = 30_00L,
            monthUpcomingMinor = 60_00L,
        )
        assertEquals(90_00L, status?.spentMinor)
        assertTrue(status?.isOver == true)
    }

    // ────────────── #17 试用转正 ──────────────

    @Test
    fun `待确认试用确认续费后转正并清空试用结束日`() {
        val trial = sub(next = today.minusDays(2), status = SubscriptionStatus.TRIAL)
            .copy(trialEndDate = today.minusDays(2))
        val outcome = SubscriptionActions.confirmRenewal(trial, today)
        assertEquals(SubscriptionStatus.ACTIVE, outcome.subscription.status)
        assertNull(outcome.subscription.trialEndDate)
        // 下一期由锚点对齐（可能就是今天），但绝不能落在过去
        assertTrue(!outcome.subscription.nextChargeDate!!.isBefore(today))
        // 确认后不再派生为待确认（否则可以无限重复推进）
        assertFalse(
            DisplayStateResolver.resolve(outcome.subscription, today, hasPaymentOnDueDate = false)
                is SubscriptionDisplayState.PendingConfirm,
        )
        // 以试用结束日为锚记流水，而不是 nextChargeDate 之外随意一天
        assertEquals(today.minusDays(2), outcome.payments.single().paidAt)
        // 币种随订阅保留
        val usdTrial = trial.copy(currency = "USD", priceMinor = 10_00L)
        assertEquals("USD", SubscriptionActions.confirmRenewal(usdTrial, today).payments.single().currency)
    }

    // ────────────── #18 买断累计支出 ──────────────

    @Test
    fun `确认后的买断仍在累计支出中`() {
        val oneTime = sub(cycle = BillingCycleType.ONE_TIME, priceMinor = 10_00L)
        val confirmed = SubscriptionActions.confirmRenewal(oneTime, today).subscription
        assertEquals(SubscriptionStatus.EXPIRED, confirmed.status)
        val snapshot = StatsEngine.snapshot(listOf(confirmed), today, 0, 0, 0, 0, null)
        assertEquals(10_00L, snapshot.oneTimeTotalMinor)
        assertEquals(1, snapshot.oneTimeCount)
    }

    // ────────────── #19 删除语义（领域口径） ──────────────

    @Test
    fun `软删除订阅退出预测与现金流统计`() {
        val active = sub(priceMinor = 10_00L, next = today.plusDays(2))
        val deleted = active.copy(deletedAtUtcMillis = 1L)
        val snapshot = StatsEngine.snapshot(listOf(deleted), today, 0, 0, 0, 0, null)
        assertEquals(0L, snapshot.forecastMonthlyMinor)
        assertEquals(0, MonthlyCashflow.dueThisMonthCount(listOf(deleted), today))
        assertTrue(planner.plan(deleted).isEmpty())
    }
}
