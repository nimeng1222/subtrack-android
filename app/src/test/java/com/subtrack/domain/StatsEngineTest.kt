package com.subtrack.domain

import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.stats.MonthlyCashflow
import com.subtrack.domain.stats.StatsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsEngineTest {

    private val today = LocalDate.of(2026, 10, 26)

    private fun sub(
        name: String,
        price: Long,
        cycle: BillingCycleType = BillingCycleType.MONTHLY,
        category: String = "video",
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        next: LocalDate? = LocalDate.of(2026, 10, 28),
        customDays: Int? = null,
        currency: String = "CNY",
    ) = Subscription(
        id = name,
        name = name,
        categoryId = category,
        cycle = cycle,
        customCycleDays = customDays,
        currency = currency,
        anchorDate = LocalDate.of(2026, 1, 1),
        nextChargeDate = next,
        priceMinor = price,
        status = status,
    )

    private fun snap(
        subs: List<Subscription>,
        paymentsTotal: Long = 0,
        paymentsCount: Int = 0,
        firstPayment: LocalDate? = null,
    ) = StatsEngine.snapshot(
        subs = subs,
        today = today,
        dueThisMonthMinor = MonthlyCashflow.dueThisMonthMinor(subs, today),
        dueThisMonthCount = MonthlyCashflow.dueThisMonthCount(subs, today),
        paymentsTotalMinor = paymentsTotal,
        paymentsCount = paymentsCount,
        firstPaymentDate = firstPayment,
    )

    // ── 文档 §5.4 的算例：年付 199 + 月付 25 + 每 4 周 30 ──
    @Test
    fun `文档算例 年度889 月均74点08 差4分`() {
        val s = snap(
            listOf(
                sub("年付", 199_00, BillingCycleType.YEARLY),
                sub("月付", 25_00, BillingCycleType.MONTHLY),
                sub("四周", 30_00, BillingCycleType.FOUR_WEEKLY),
            ),
        )
        assertEquals(88_900L, s.forecastAnnualMinor)
        assertEquals(7_408L, s.forecastMonthlyMinor)
        // AC-33：展示值 |月均×12 − 年度| ≤ 0.06 元 = 6 分
        assertTrue(kotlin.math.abs(s.forecastMonthlyMinor * 12 - s.forecastAnnualMinor) <= 6L)
    }

    @Test
    fun `AC-33 单条年付199元 误差4分仍在阈值内`() {
        val s = snap(listOf(sub("年付", 199_00, BillingCycleType.YEARLY)))
        assertEquals(19_900L, s.forecastAnnualMinor)
        assertEquals(1_658L, s.forecastMonthlyMinor)
        assertEquals(4L, kotlin.math.abs(s.forecastMonthlyMinor * 12 - s.forecastAnnualMinor))
    }

    // ── 系数守护：这三格写错不会抛异常，只会静默算错 ──
    @Test
    fun `系数守护 四周付3000分必须年化39000而不是36000`() {
        val s = snap(listOf(sub("四周", 3_000, BillingCycleType.FOUR_WEEKLY)))
        assertEquals(39_000L, s.forecastAnnualMinor)
    }

    @Test
    fun `系数守护 双周付3000分必须年化78000`() {
        val s = snap(listOf(sub("双周", 3_000, BillingCycleType.BIWEEKLY)))
        assertEquals(78_000L, s.forecastAnnualMinor)
    }

    @Test
    fun `系数守护 半年付3000分必须年化6000`() {
        val s = snap(listOf(sub("半年", 3_000, BillingCycleType.SEMIANNUAL)))
        assertEquals(6_000L, s.forecastAnnualMinor)
    }

    // ── 状态对统计的影响 ──
    @Test
    fun `暂停与取消与过期都不计入预测`() {
        val s = snap(
            listOf(
                sub("a", 10_00, status = SubscriptionStatus.ACTIVE),
                sub("b", 10_00, status = SubscriptionStatus.PAUSED),
                sub("c", 10_00, status = SubscriptionStatus.CANCELED),
                sub("d", 10_00, status = SubscriptionStatus.EXPIRED),
            ),
        )
        assertEquals(12_000L, s.forecastAnnualMinor)
        assertEquals(1, s.activeCount)
    }

    @Test
    fun `试用计入预测并单独标注转正增量`() {
        val s = snap(
            listOf(
                sub("正式", 10_00),
                sub("试用", 20_00, status = SubscriptionStatus.TRIAL),
            ),
        )
        assertEquals(36_000L, s.forecastAnnualMinor)
        assertEquals(1, s.trialCount)
        assertEquals(2_000L, s.trialMonthlyDeltaMinor)
    }

    @Test
    fun `待确认仍计入预测并单独统计金额`() {
        // next 已过期 → 待确认展示态
        val s = snap(listOf(sub("过期", 25_00, next = LocalDate.of(2026, 10, 20))))
        assertEquals(30_000L, s.forecastAnnualMinor)
        assertEquals(1, s.pendingCount)
        assertEquals(30_000L, s.pendingMinor)
    }

    @Test
    fun `一次性不进年化只单独汇总`() {
        val s = snap(
            listOf(
                sub("月付", 10_00),
                sub("买断", 99_00, BillingCycleType.ONE_TIME, next = LocalDate.of(2026, 10, 27)),
            ),
        )
        assertEquals(12_000L, s.forecastAnnualMinor)
        assertEquals(1, s.oneTimeCount)
        assertEquals(99_00L, s.oneTimeTotalMinor)
    }

    // ── AC-36a：覆盖不足 90 天不外推 ──
    @Test
    fun `流水覆盖不足90天不展示实际月均`() {
        val s = snap(
            listOf(sub("a", 10_00)),
            paymentsTotal = 30_000, paymentsCount = 3,
            firstPayment = today.minusDays(30),
        )
        assertNull(s.actualMonthlyMinor)
    }

    @Test
    fun `流水覆盖满90天才展示实际月均`() {
        val s = snap(
            listOf(sub("a", 10_00)),
            paymentsTotal = 30_000, paymentsCount = 3,
            firstPayment = today.minusDays(120),
        )
        assertEquals(10_000L, s.actualMonthlyMinor)
    }

    @Test
    fun `没有流水时不展示实际月均`() {
        val s = snap(listOf(sub("a", 10_00)))
        assertNull(s.actualMonthlyMinor)
    }

    // ── AC-36b：差值洞察 ──
    @Test
    fun `预计显著低于实际提示漏记`() {
        val insight = StatsEngine.insight(forecastMonthlyMinor = 5_000, actualMonthlyMinor = 10_000, sampleCount = 5)
        assertNotNull(insight)
        assertTrue(insight!!.suggestAdd)
        assertTrue(insight.headline.contains("低"))
    }

    @Test
    fun `预计显著高于实际提示僵尸订阅`() {
        val insight = StatsEngine.insight(forecastMonthlyMinor = 20_000, actualMonthlyMinor = 10_000, sampleCount = 5)
        assertNotNull(insight)
        assertTrue(!insight!!.suggestAdd)
        assertTrue(insight.headline.contains("高"))
    }

    @Test
    fun `两口径接近时不报警`() {
        val insight = StatsEngine.insight(forecastMonthlyMinor = 10_000, actualMonthlyMinor = 10_050, sampleCount = 5)
        assertNotNull(insight)
        assertTrue(!insight!!.suggestAdd)
        assertTrue(insight.headline.contains("吻合"))
    }

    @Test
    fun `差值小于10元视为噪声`() {
        // 差额 12% 但绝对值只有 5 元，不该提示
        val insight = StatsEngine.insight(forecastMonthlyMinor = 4_200, actualMonthlyMinor = 4_700, sampleCount = 5)
        assertNotNull(insight)
        assertTrue(!insight!!.suggestAdd)
    }

    // ── 分类聚合 ──
    @Test
    fun `分类按金额降序且带条目数`() {
        val s = snap(
            listOf(
                sub("v1", 10_00, category = "video"),
                sub("v2", 5_00, category = "video"),
                sub("m1", 100_00, category = "music"),
            ),
        )
        assertEquals("music", s.categories.first().categoryId)
        assertEquals(1, s.categories.first().count)
        assertEquals("video", s.categories.last().categoryId)
        assertEquals(2, s.categories.last().count)
    }

    @Test
    fun `分类占比基准点合计接近一万`() {
        val s = snap(
            listOf(
                sub("a", 10_00, category = "video"),
                sub("b", 10_00, category = "music"),
                sub("c", 10_00, category = "cloud"),
            ),
        )
        val total = s.categories.sumOf { it.annualMinor }
        val sum = s.categories.sumOf { it.basisPoints(total) }
        // 允许四舍五入造成的 ±2 个基准点偏差
        assertTrue(kotlin.math.abs(sum - 10_000L) <= 2L)
    }

    // ── 展开明细（AC-35）──
    @Test
    fun `月均明细列出每条与系数且总额与统计卡一致`() {
        val b = StatsEngine.monthlyBreakdown(
            listOf(
                sub("年付", 199_00, BillingCycleType.YEARLY),
                sub("月付", 25_00),
            ),
        )
        assertEquals("预计月均", b.title)
        assertEquals(2, b.lines.size)
        assertTrue(b.lines.any { it.detail.contains("×1") })
        assertTrue(b.lines.any { it.detail.contains("×12") })
        // v1.3.2 把年度合计塞进了"预计月均"明细的总额（统计卡 41.58、点开变 499）。
        // 明细总额必须与统计卡的月均同口径；年度合计由 annualBreakdown 呈现。
        val annual = 199_00L + 25_00L * 12
        assertEquals(annual / 12L + if (annual % 12L >= 6L) 1 else 0, b.totalMinor)
        assertEquals(
            StatsEngine.snapshot(listOf(sub("年付", 199_00, BillingCycleType.YEARLY), sub("月付", 25_00)), today, 0, 0, 0, 0, null)
                .forecastMonthlyMinor,
            b.totalMinor,
        )
    }

    @Test
    fun `年度明细总额保留年度合计`() {
        val subs = listOf(
            sub("年付", 199_00, BillingCycleType.YEARLY),
            sub("月付", 25_00),
        )
        val b = StatsEngine.annualBreakdown(subs)
        assertEquals("年度总支出", b.title)
        assertEquals(199_00L + 25_00L * 12, b.totalMinor)
    }

    @Test
    fun `本月将扣明细只含本月扣款日`() {
        val b = StatsEngine.dueBreakdown(
            listOf(
                sub("本月", 10_00, next = LocalDate.of(2026, 10, 28)),
                sub("下月", 20_00, next = LocalDate.of(2026, 11, 5)),
            ),
            today,
        )
        assertEquals(1, b.lines.size)
        assertEquals(10_00L, b.totalMinor)
    }

    @Test
    fun `本月将扣不含暂停与一次性外的活动态`() {
        val subs = listOf(
            sub("活动", 10_00, next = LocalDate.of(2026, 10, 28)),
            sub("暂停", 20_00, status = SubscriptionStatus.PAUSED, next = LocalDate.of(2026, 10, 29)),
        )
        assertEquals(10_00L, MonthlyCashflow.dueThisMonthMinor(subs, today))
        assertEquals(1, MonthlyCashflow.dueThisMonthCount(subs, today))
    }
    // ────────────── 多币种对统计口径的影响 ──────────────

    @Test
    fun `多币种未启用时外币按原值计算`() {
        // fxRates=null 表示未启用：行为与从前完全一致（不折算、不排除）
        val s = snap(listOf(sub("usd", 10_00, currency = "USD")))
        assertEquals(120_00L, s.forecastAnnualMinor)
    }

    @Test
    fun `多币种启用且有汇率时折算`() {
        val subs = listOf(sub("usd", 10_00, currency = "USD"))
        val s = StatsEngine.snapshot(
            subs = subs,
            today = today,
            dueThisMonthMinor = 0L,
            dueThisMonthCount = 0,
            paymentsTotalMinor = 0L,
            paymentsCount = 0,
            firstPaymentDate = null,
            fxRates = mapOf("USD" to 720L),
        )
        // 10 USD = 7200 分 = 72 元/月 → 年化 86400
        assertEquals(86_400L, s.forecastAnnualMinor)
    }

    @Test
    fun `多币种启用但缺汇率时排除该条而不是当人民币硬算`() {
        // 这是关键行为：缺 USD 汇率时，10 美元不能算成 10 元。
        // 排除后只剩 CNY 那条，否则月均会凭空缩水，比显示不出来更误导。
        val subs = listOf(
            sub("cny", 15_00),
            sub("usd", 10_00, currency = "USD"),
        )
        val s = StatsEngine.snapshot(
            subs = subs,
            today = today,
            dueThisMonthMinor = 0L,
            dueThisMonthCount = 0,
            paymentsTotalMinor = 0L,
            paymentsCount = 0,
            firstPaymentDate = null,
            fxRates = emptyMap(),
        )
        // 只有 CNY 那条参与：1500×12 = 18000
        assertEquals(18_000L, s.forecastAnnualMinor)
        // 但 activeCount 仍统计两条（它反映"有几条在生效"，不是"有几条能算钱"）
        assertEquals(2, s.activeCount)
    }

}

class AppSettingsTest {

    @Test
    fun `合法偏移被去重降序`() {
        assertEquals(listOf(30, 7, 0), AppSettings.sanitizeOffsets(listOf(0, 7, 30, 7)))
    }

    @Test
    fun `非法偏移被过滤`() {
        assertEquals(listOf(3, 0), AppSettings.sanitizeOffsets(listOf(3, 4, 100, 0, -1)))
    }

    @Test
    fun `默认提前天数符合 AC-21`() {
        assertEquals(listOf(3, 0), AppSettings().defaultReminderOffsets)
    }

    @Test
    fun `允许集合与文档一致`() {
        assertEquals(listOf(0, 1, 2, 3, 5, 7, 14, 30), AppSettings.ALLOWED_OFFSETS)
    }
}
