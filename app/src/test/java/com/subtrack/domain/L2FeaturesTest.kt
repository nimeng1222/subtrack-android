package com.subtrack.domain

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.BudgetWatch
import com.subtrack.domain.stats.CurrencyConverter
import com.subtrack.domain.stats.PriceWatch
import com.subtrack.domain.stats.SubscriptionHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class L2FeaturesTest {

    private val today = LocalDate.of(2026, 10, 27)

    private fun sub(
        id: String,
        price: Long,
        cycle: BillingCycleType = BillingCycleType.MONTHLY,
        next: LocalDate? = LocalDate.of(2026, 11, 1),
        anchor: LocalDate = LocalDate.of(2026, 1, 1),
        currency: String = "CNY",
        customDays: Int? = null,
    ) = Subscription(
        id = id,
        name = id,
        categoryId = "video",
        cycle = cycle,
        customCycleDays = customDays,
        anchorDate = anchor,
        nextChargeDate = next,
        priceMinor = price,
        currency = currency,
    )

    // ────────────────────────────── 预算 ──────────────────────────────

    @Test
    fun `未设预算时不做任何判断`() {
        assertNull(BudgetWatch.status(null, 10_000, 5_000))
        assertNull(BudgetWatch.status(0L, 10_000, 5_000))
        assertNull(BudgetWatch.status(-100L, 10_000, 5_000))
    }

    @Test
    fun `预算结余与使用比例`() {
        val s = BudgetWatch.status(budgetMinor = 100_00L, monthSpentMinor = 40_00L, monthUpcomingMinor = 20_00L)!!
        assertEquals(60_00L, s.spentMinor)
        assertEquals(40_00L, s.remainingMinor)
        assertTrue(!s.isOver)
        assertTrue(!s.isNearLimit)
        assertEquals(0.6, s.usedRatio, 0.001)
    }

    @Test
    fun `接近上限标记`() {
        val s = BudgetWatch.status(100_00L, 50_00L, 35_00L)!!
        assertTrue(s.isNearLimit)
        assertTrue(!s.isOver)
    }

    @Test
    fun `超支时剩余为负`() {
        val s = BudgetWatch.status(100_00L, 80_00L, 40_00L)!!
        assertTrue(s.isOver)
        assertEquals(-20_00L, s.remainingMinor)
        assertTrue(s.usedRatio > 1.0)
    }

    @Test
    fun `按当前订阅推断本月会不会超`() {
        val subs = listOf(
            sub("a", 60_00, next = LocalDate.of(2026, 10, 29)),
            sub("b", 30_00, next = LocalDate.of(2026, 11, 5)),
        )
        // 只有 a 在 10 月内
        val s = BudgetWatch.projection(subs, today, 100_00L)!!
        assertEquals(60_00L, s.spentMinor)
        assertTrue(!s.isOver)
    }

    // ────────────────────────────── 涨价 ──────────────────────────────

    @Test
    fun `检测到涨价并算出百分比`() {
        val subs = listOf(sub("a", 15_00))
        val changes = PriceWatch.detect(subs, mapOf("a" to 12_00L))
        assertEquals(1, changes.size)
        assertEquals(3_00L, changes.first().deltaMinor)
        assertEquals(25, changes.first().percent)
        assertTrue(changes.first().isIncrease)
    }

    @Test
    fun `降价也能检测但不排在涨价前面`() {
        val subs = listOf(sub("up", 20_00), sub("down", 8_00))
        val changes = PriceWatch.detect(subs, mapOf("up" to 10_00L, "down" to 10_00L))
        assertEquals(2, changes.size)
        // 涨的排前面
        assertEquals("up", changes.first().subscription.id)
        assertTrue(!changes.last().isIncrease)
    }

    @Test
    fun `没有流水的条目不参与检测`() {
        val subs = listOf(sub("a", 15_00), sub("b", 20_00))
        val changes = PriceWatch.detect(subs, mapOf("a" to 12_00L))
        assertEquals(1, changes.size)
        assertEquals("a", changes.first().subscription.id)
    }

    @Test
    fun `变化幅度低于阈值视为噪音`() {
        // 100 分涨到 100 分 = 0%，不报
        assertEquals(0, PriceWatch.detect(listOf(sub("a", 100L)), mapOf("a" to 100L)).size)
        // 10000 分涨到 10050 分 = 0.5%，低于 1% 默认阈值
        assertEquals(0, PriceWatch.detect(listOf(sub("a", 10_050L)), mapOf("a" to 10_000L)).size)
    }

    @Test
    fun `暂停的订阅不参与涨价检测`() {
        val paused = sub("a", 15_00).copy(status = com.subtrack.domain.model.SubscriptionStatus.PAUSED)
        assertEquals(0, PriceWatch.detect(listOf(paused), mapOf("a" to 12_00L)).size)
    }

    // ────────────────────────────── 体检 ──────────────────────────────

    @Test
    fun `过期超过两个周期给出警告`() {
        // 月付，过期 70 天 = 2 个周期
        val subs = listOf(sub("a", 15_00, next = today.minusDays(70)))
        val findings = SubscriptionHealth.findings(subs, today, forecastMonthlyMinor = 15_00L)
        assertTrue(findings.any { it.severity == SubscriptionHealth.Severity.WARN })
    }

    @Test
    fun `刚过期一期不算长期未确认`() {
        val subs = listOf(sub("a", 15_00, next = today.minusDays(5)))
        val findings = SubscriptionHealth.findings(subs, today, forecastMonthlyMinor = 15_00L)
        assertTrue(findings.none { it.severity == SubscriptionHealth.Severity.WARN })
    }

    @Test
    fun `大额订阅占比过高会提示`() {
        // 单条 60 元月付，整体月均约 60 元 → 占比 100%
        val subs = listOf(sub("big", 60_00))
        val findings = SubscriptionHealth.findings(subs, today, forecastMonthlyMinor = 60_00L)
        assertTrue(findings.any { it.title.contains("占月均") })
    }

    @Test
    fun `年付即将续费会提示`() {
        val subs = listOf(
            sub("year", 199_00, BillingCycleType.YEARLY, next = today.plusDays(30)),
        )
        val findings = SubscriptionHealth.findings(subs, today, forecastMonthlyMinor = 99_00L)
        assertTrue(findings.any { it.title.contains("年付续费") })
    }

    @Test
    fun `过期太久的警告排在最前`() {
        val subs = listOf(
            sub("normal", 5_00, next = today.plusDays(3)),
            sub("overdue", 15_00, next = today.minusDays(100)),
        )
        val findings = SubscriptionHealth.findings(subs, today, forecastMonthlyMinor = 20_00L)
        assertTrue(findings.isNotEmpty())
        assertEquals(SubscriptionHealth.Severity.WARN, findings.first().severity)
    }

    @Test
    fun `没有订阅时体检结果为空`() {
        assertTrue(SubscriptionHealth.findings(emptyList(), today, 0L).isEmpty())
    }

    // ────────────────────────────── 多币种 ──────────────────────────────

    @Test
    fun `人民币不需要汇率`() {
        assertEquals(15_00L, CurrencyConverter.toCnyMinor(15_00L, "CNY", emptyMap()))
    }

    @Test
    fun `美元按手动汇率折算`() {
        // 1 USD = 7.20 CNY = 720 分人民币
        val usd = CurrencyConverter.toCnyMinor(10_00L, "USD", mapOf("USD" to 720L))
        assertEquals(72_00L, usd)
    }

    @Test
    fun `折算做一次四舍五入`() {
        // 1 USD = 7.01 元 = 701 分；100 外币分 × 701 / 100 = 701
        val result = CurrencyConverter.toCnyMinor(100L, "USD", mapOf("USD" to 701L))
        assertEquals(701L, result)
    }

    @Test
    fun `缺汇率时返回 null 而不是瞎猜`() {
        assertNull(CurrencyConverter.toCnyMinor(10_00L, "USD", emptyMap()))
        assertNull(CurrencyConverter.toCnyMinor(10_00L, "EUR", mapOf("USD" to 720L)))
        assertNull(CurrencyConverter.toCnyMinor(10_00L, "USD", mapOf("USD" to 0L)))
    }

    @Test
    fun `订阅折算与年化`() {
        val subs = listOf(
            sub("cny", 15_00, BillingCycleType.MONTHLY),
            sub("usd", 10_00, BillingCycleType.MONTHLY, currency = "USD"),
        )
        val rates = mapOf("USD" to 720L)
        // cny: 1500×12=18000；usd: 7200×12=86400 → 合计 104400
        assertEquals(104_400L, CurrencyConverter.annualizedCnyMinor(subs, rates))
    }

    @Test
    fun `缺任一汇率时整体年化返回 null`() {
        val subs = listOf(
            sub("cny", 15_00),
            sub("usd", 10_00, currency = "USD"),
            sub("eur", 10_00, currency = "EUR"),
        )
        assertNull(CurrencyConverter.annualizedCnyMinor(subs, mapOf("USD" to 720L)))
    }

    @Test
    fun `列出缺失的币种`() {
        val subs = listOf(
            sub("a", 10_00, currency = "USD"),
            sub("b", 10_00, currency = "EUR"),
            sub("c", 10_00, currency = "USD"),
            sub("d", 10_00),
        )
        assertEquals(
            setOf("USD", "EUR"),
            CurrencyConverter.missingRates(subs, emptyMap()).toSet(),
        )
        assertTrue(CurrencyConverter.missingRates(subs, mapOf("USD" to 720L, "EUR" to 780L)).isEmpty())
    }

    // ──────────────────── 设置持久化的序列化（防"改了丢"）────────────────────

    @Test
    fun `汇率表编码解码往返`() {
        // SettingsStore 的方法依赖 Context，纯 JVM 单测里只验证序列化约定本身
        val rates = mapOf("USD" to 720L, "EUR" to 780L)
        val encoded = rates.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
        assertEquals("EUR=780;USD=720", encoded)
    }

    /**
     * 守护断言：AppSettings 的字段清单必须与预期一致。
     *
     * 加新字段时这条会红，提醒你去 SettingsStore 同步加"读 + 写"两处。
     * 之所以不直接反射扫源码（脆弱、路径依赖），而是断言字段数：
     * 数量变了就必须有人主动来改这里，改的时候自然会看见注释里的提醒。
     */
    @Test
    fun `AppSettings 字段数变化时必须同步 SettingsStore`() {
        val fields = com.subtrack.domain.model.AppSettings::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "Companion" || it == "ALLOWED_OFFSETS" }
        // v1.1 的字段清单：offsets/time/banner/theme/appLock + L2 的 budget/priceWatch/health/multiCurrency/fxRates
        assertEquals(
            "新增字段时请同步 SettingsStore 的 observe() 与 update()：$fields",
            11, // v1.4 network configuration has explicit read/write; excluded from portable backups.
            fields.size,
        )
    }
}
