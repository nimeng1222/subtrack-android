package com.subtrack.domain

import com.subtrack.data.backup.BackupCodec
import com.subtrack.data.backup.BackupFormatException
import com.subtrack.data.backup.ImportResult
import com.subtrack.data.backup.PaymentKey
import com.subtrack.data.backup.PaymentRecord
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 备份 v2 的验收测试（AC-52 ~ AC-59）。
 *
 * 这些测试之所以能在纯 JVM 下跑，是因为 codec 刻意不依赖 org.json
 * （org.json 在 Android 单测里是 stub，一调用就 "not mocked"）。
 */
class BackupCodecTest {

    // ------------------------------------------------------------ 测试数据

    private val today: LocalDate = LocalDate.of(2026, 10, 26)

    /** 覆盖所有"容易在序列化里丢"的字段：可空日期、自定义天数、备注、多提醒偏移。 */
    private fun richSubscription() = Subscription(
        id = "sub-rich",
        name = "腾讯视频 VIP",
        categoryId = "video",
        paymentMethodId = "card-001",
        cycle = BillingCycleType.CUSTOM_DAYS,
        customCycleDays = 45,
        anchorDate = LocalDate.of(2025, 3, 1),
        nextChargeDate = LocalDate.of(2026, 11, 15),
        priceMinor = 2_550L,
        currency = "CNY",
        status = SubscriptionStatus.TRIAL,
        autoRenew = false,
        trialEndDate = LocalDate.of(2026, 12, 1),
        canceledAt = LocalDate.of(2026, 9, 30),
        reminderOffsets = listOf(7, 3, 0),
        mutedUntil = LocalDate.of(2026, 11, 1),
        note = "年付更划算,备注里有\\反斜杠和\"引号\"",
        deletedAtUtcMillis = null,
    )

    /** 一次性买断 + 各种"可选字段全空"的极端，验证 null 往返不会变成空串或 0。 */
    private fun minimalSubscription() = Subscription(
        id = "sub-min",
        name = "买断 · 一次性",
        categoryId = "other",
        paymentMethodId = null,
        cycle = BillingCycleType.ONE_TIME,
        customCycleDays = null,
        anchorDate = today,
        nextChargeDate = null,
        priceMinor = 19_900L,
        currency = "CNY",
        status = SubscriptionStatus.ACTIVE,
        autoRenew = true,
        trialEndDate = null,
        canceledAt = null,
        reminderOffsets = listOf(0),
        mutedUntil = null,
        note = null,
        deletedAtUtcMillis = null,
    )

    private fun samplePayments(): List<PaymentRecord> = listOf(
        PaymentRecord(
            id = "pay-1",
            subscriptionId = "sub-rich",
            paidAt = LocalDate.of(2026, 9, 15),
            amountMinor = 2_550L,
            currency = "CNY",
            source = "USER_CONFIRMED",
            createdAtUtcMillis = 1_790_000_000_000L,
            note = "首次支付",
        ),
        PaymentRecord(
            id = "pay-2",
            subscriptionId = "sub-rich",
            paidAt = LocalDate.of(2026, 10, 15),
            amountMinor = 2_550L,
            source = "AUTO_RENEW",
            createdAtUtcMillis = 1_792_000_000_000L,
            note = null,
        ),
    )

    private fun sampleSettings() = AppSettings(
        defaultReminderOffsets = listOf(3, 0),
        reminderTime = LocalTime.of(21, 30),
        notificationsBannerDismissed = true,
        // L1/L2 新增字段也必须有值，否则"备份丢设置"这个 bug 测不出来
        themeMode = com.subtrack.domain.model.ThemeMode.DARK,
        appLockEnabled = true,
        monthlyBudgetMinor = 200_00L,
        priceWatchEnabled = true,
        healthCheckEnabled = true,
        multiCurrencyEnabled = true,
        fxRates = mapOf("USD" to 720L, "EUR" to 780L),
    )

    private fun sampleBackupText(): String = BackupCodec.exportJson(
        subscriptions = listOf(richSubscription(), minimalSubscription()),
        payments = samplePayments(),
        settings = sampleSettings(),
    )

    // ------------------------------------------------------------ AC-52 / AC-58 往返

    @Test
    fun `roundtrip preserves every subscription field`() {
        val original = listOf(richSubscription(), minimalSubscription())
        val result = BackupCodec.importBackup(BackupCodec.exportJson(original))

        // 直接比 data class 等于逐字段比对，任何一个字段丢失都会红
        assertEquals(original, result.subscriptions)
        assertEquals(2, result.added)
        assertEquals(0, result.skipped)
        assertEquals(0, result.corrected)
    }

    @Test
    fun `roundtrip preserves payments and settings`() {
        val payments = samplePayments()
        val settings = sampleSettings()
        val result = BackupCodec.importBackup(
            BackupCodec.exportJson(listOf(richSubscription()), payments, settings),
        )

        assertEquals(payments, result.payments)
        assertEquals(2, result.addedPayments)
        assertEquals(0, result.skippedPayments)
        assertEquals(settings.defaultReminderOffsets, result.settings?.defaultReminderOffsets)
        assertEquals(settings.reminderTime, result.settings?.reminderTime)
        assertEquals(settings.notificationsBannerDismissed, result.settings?.notificationsBannerDismissed)
    }

    @Test
    fun `roundtrip keeps null optional fields null instead of empty or zero`() {
        val result = BackupCodec.importBackup(BackupCodec.exportJson(listOf(minimalSubscription())))
        val sub = result.subscriptions.single()

        assertNull(sub.paymentMethodId)
        assertNull(sub.nextChargeDate)
        assertNull(sub.customCycleDays)
        assertNull(sub.trialEndDate)
        assertNull(sub.canceledAt)
        assertNull(sub.mutedUntil)
        assertNull(sub.note)
        assertNull(sub.deletedAtUtcMillis)
        assertEquals(BillingCycleType.ONE_TIME, sub.cycle)
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        // 布尔字段在任何情况下都不能被"null 兜底"逻辑翻成 false
        assertTrue(sub.autoRenew)
        assertEquals(listOf(0), sub.reminderOffsets)
    }

    @Test
    fun `roundtrip keeps false autoRenew false`() {
        val result = BackupCodec.importBackup(
            BackupCodec.exportJson(listOf(richSubscription().copy(autoRenew = false))),
        )
        assertFalse(result.subscriptions.single().autoRenew)
    }

    @Test
    fun `roundtrip covers every billing cycle type`() {
        val subs = BillingCycleType.entries.mapIndexed { index, cycle ->
            Subscription(
                id = "sub-$cycle",
                name = "周期 $cycle",
                categoryId = "other",
                cycle = cycle,
                customCycleDays = if (cycle == BillingCycleType.CUSTOM_DAYS) 17 else null,
                anchorDate = today.plusDays(index.toLong()),
                nextChargeDate = today.plusDays(index.toLong() + 5),
                priceMinor = 100L + index,
                status = SubscriptionStatus.entries[index % SubscriptionStatus.entries.size],
                reminderOffsets = listOf(7, 1),
            )
        }
        val result = BackupCodec.importBackup(BackupCodec.exportJson(subs))
        assertEquals(subs, result.subscriptions)
    }

    // ------------------------------------------------------------ AC-58 幂等

    @Test
    fun `second import of the same file adds nothing`() {
        val text = sampleBackupText()
        val first: ImportResult = BackupCodec.importBackup(text)

        // 第一次导入的内容已经写进库，第二次把库内状态喂回去
        val second = BackupCodec.importBackup(
            text,
            existingIds = first.subscriptions.map { it.id }.toSet(),
            existingPaymentKeys = first.payments.map { it.key }.toSet(),
        )

        assertEquals(0, second.added)
        assertEquals(2, second.skipped)
        assertEquals(0, second.corrected)
        // 幂等的另一半：没有东西需要回写
        assertTrue(second.subscriptions.isEmpty())
        assertEquals(0, second.addedPayments)
        assertEquals(2, second.skippedPayments)
        assertTrue(second.payments.isEmpty())
    }

    @Test
    fun `existing id with different content is skipped not overwritten`() {
        // v1.3.2 审查结论：本地价格被用户改成 25 元后，导入旧备份（10 元）不得回退。
        // "修正"不再覆盖本地改动——已有 id 一律跳过，本地数据优先（设置页承诺的语义）。
        val text = BackupCodec.exportJson(listOf(richSubscription()))
        val locallyEdited = richSubscription().copy(name = "本地改过的名字", priceMinor = 2_500L)

        val result = BackupCodec.importBackup(text, existingIds = setOf(locallyEdited.id))

        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
        assertEquals(0, result.corrected)
        assertTrue(result.subscriptions.isEmpty())
    }

    @Test
    fun `duplicate payment keys inside one file are collapsed`() {
        val duplicated = samplePayments() + samplePayments().map { it.copy(id = "${it.id}-dup") }
        val result = BackupCodec.importBackup(
            BackupCodec.exportJson(listOf(richSubscription()), duplicated),
        )

        assertEquals(2, result.addedPayments)
        assertEquals(2, result.skippedPayments)
        assertEquals(2, result.payments.size)
    }

    @Test
    fun `payment dedup uses subscriptionId plus paidAt`() {
        val payment = samplePayments().first()
        val result = BackupCodec.importBackup(
            BackupCodec.exportJson(listOf(richSubscription()), listOf(payment)),
            existingPaymentKeys = setOf(PaymentKey(payment.subscriptionId, payment.paidAt)),
        )
        assertEquals(0, result.addedPayments)
        assertEquals(1, result.skippedPayments)
    }

    // ------------------------------------------------------------ AC-56 版本过新

    @Test
    fun `future format version is rejected with upgrade hint`() {
        val future = """
            {
              "formatVersion": 99,
              "subscriptions": []
            }
        """.trimIndent()
        val error = expectReject(future)
        assertTrue("实际提示：${error.message}", error.message!!.contains("更新版本"))
        assertTrue("实际提示：${error.message}", error.message!!.contains("升级"))
    }

    @Test
    fun `exported file declares format version two`() {
        val text = BackupCodec.exportJson(listOf(richSubscription()))
        assertTrue("导出文件必须带 formatVersion: 2", text.contains("\"formatVersion\": 2"))
        assertEquals(2, BackupCodec.FORMAT_VERSION)
    }

    // ------------------------------------------------------------ AC-57 损坏拒绝

    @Test
    fun `broken json is rejected`() {
        expectReject("""{"formatVersion": 2, "subscriptions": [""")
        expectReject("这不是 JSON")
        expectReject("")
        expectReject("""{"formatVersion": 2, "subscriptions": [{"id": }]}""")
    }

    @Test
    fun `missing required field is rejected`() {
        // 缺 anchorDate（必填）
        val missingAnchor = """
            {
              "formatVersion": 2,
              "subscriptions": [
                {"id": "a", "name": "音乐", "categoryId": "music", "cycle": "MONTHLY", "priceMinor": 1000}
              ]
            }
        """.trimIndent()
        val error = expectReject(missingAnchor)
        assertTrue("实际提示：${error.message}", error.message!!.contains("anchorDate"))
    }

    @Test
    fun `one bad entry fails the whole import leaving no partial data`() {
        // 第一条完全合法，第二条 cycle 是未知枚举值
        val oneBad = """
            {
              "formatVersion": 2,
              "subscriptions": [
                {
                  "id": "ok", "name": "网易云音乐", "categoryId": "music", "cycle": "MONTHLY",
                  "anchorDate": "2026-01-01", "priceMinor": 1500
                },
                {
                  "id": "bad", "name": "坏条目", "categoryId": "other", "cycle": "EVERY_FULL_MOON",
                  "anchorDate": "2026-01-01", "priceMinor": 1500
                }
              ]
            }
        """.trimIndent()

        // 整次导入抛异常 ⇒ 调用方一个 Subscription 都拿不到，不可能写进半套数据
        expectReject(oneBad)
    }

    @Test
    fun `unknown status and bad date are rejected instead of silently defaulted`() {
        expectReject(
            """
            {"formatVersion": 2, "subscriptions": [
              {"id":"a","name":"n","categoryId":"other","cycle":"MONTHLY",
               "anchorDate":"2026-01-01","priceMinor":1,"status":"WEIRD"}
            ]}
            """.trimIndent(),
        )
        expectReject(
            """
            {"formatVersion": 2, "subscriptions": [
              {"id":"a","name":"n","categoryId":"other","cycle":"MONTHLY",
               "anchorDate":"2026-13-45","priceMinor":1}
            ]}
            """.trimIndent(),
        )
    }

    @Test
    fun `optional fields may be absent without being an error`() {
        val minimal = """
            {"formatVersion": 2, "subscriptions": [
              {"id":"a","name":"最简条目","categoryId":"other","cycle":"MONTHLY",
               "anchorDate":"2026-01-01","priceMinor":1200}
            ]}
        """.trimIndent()
        val result = BackupCodec.importBackup(minimal)

        val sub = result.subscriptions.single()
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertTrue(sub.autoRenew)
        assertEquals("CNY", sub.currency)
        assertEquals(listOf(3, 0), sub.reminderOffsets)
        assertNull(sub.note)
        // v1 备份没有 payments / settings 字段，按"没有"处理
        assertTrue(result.payments.isEmpty())
        assertNull(result.settings)
    }

    @Test
    fun `duplicate subscription ids are rejected`() {
        val duplicated = """
            {"formatVersion": 2, "subscriptions": [
              {"id":"same","name":"A","categoryId":"other","cycle":"MONTHLY",
               "anchorDate":"2026-01-01","priceMinor":1},
              {"id":"same","name":"B","categoryId":"other","cycle":"MONTHLY",
               "anchorDate":"2026-01-01","priceMinor":2}
            ]}
        """.trimIndent()
        expectReject(duplicated)
    }

    // ------------------------------------------------------------ 空备份

    @Test
    fun `empty backup roundtrips`() {
        val text = BackupCodec.exportJson(emptyList(), emptyList(), null)
        val result = BackupCodec.importBackup(text)

        assertTrue(result.subscriptions.isEmpty())
        assertTrue(result.payments.isEmpty())
        assertNull(result.settings)
        assertEquals(0, result.added)
        assertEquals(0, result.skipped)
        assertEquals(0, result.corrected)

        // 空备份重复导入也必须幂等
        val second = BackupCodec.importBackup(text, existingIds = emptySet())
        assertEquals(0, second.added)
    }

    @Test
    fun `settings survive when subscriptions are empty`() {
        val result = BackupCodec.importBackup(BackupCodec.exportJson(emptyList(), emptyList(), sampleSettings()))
        assertEquals(sampleSettings(), result.settings)
    }

    // ------------------------------------------------------------ AC-53 文件名

    @Test
    fun `default file name is stable and zero padded`() {
        assertEquals("SubTrack-20261026.json", BackupCodec.defaultFileName(today))
        // 个位数月份必须补零，否则排序会乱
        assertEquals("SubTrack-20260105.json", BackupCodec.defaultFileName(LocalDate.of(2026, 1, 5)))
    }

    // ------------------------------------------------------------ AC-59 CSV

    @Test
    fun `csv starts with utf8 bom`() {
        val csv = BackupCodec.exportCsv(listOf(richSubscription()))
        assertEquals('\uFEFF', csv.first())
        assertTrue(csv.startsWith("\uFEFFname,category,cycle"))
    }

    @Test
    fun `csv header column order is fixed`() {
        val csv = BackupCodec.exportCsv(emptyList()).removePrefix("\uFEFF")
        assertEquals("name,category,cycle,price_yuan,start_date,next_charge_date,status,note", csv)
    }

    @Test
    fun `csv escapes commas quotes and newlines`() {
        val tricky = richSubscription().copy(
            name = "套餐, 含逗号",
            note = "第一行\n第二行，还有\"引号\"",
        )
        // 不能用 lines()：备注里的换行是"合法"的字符串内容，它被包在引号里，
        // 按行切会把一条记录切成两半，反而测不出转义是否正确
        val row = BackupCodec.exportCsv(listOf(tricky)).removePrefix("\uFEFF").substringAfter("\n")

        assertEquals(
            "\"套餐, 含逗号\",video,CUSTOM_DAYS,25.50,2025-03-01,2026-11-15,TRIAL," +
                "\"第一行\n第二行，还有\"\"引号\"\"\"",
            row,
        )
    }

    @Test
    fun `csv amount has two decimals`() {
        val row = BackupCodec.exportCsv(listOf(richSubscription().copy(priceMinor = 2_550L)))
            .removePrefix("\uFEFF").lines()[1]
        // 列序：名称/分类/金额/...
        assertEquals("25.50", row.split(',')[3])
    }

    @Test
    fun `csv amount pads single digit cents and handles zero`() {
        fun amountColumn(minor: Long): String =
            BackupCodec.exportCsv(listOf(minimalSubscription().copy(priceMinor = minor)))
                .removePrefix("\uFEFF").lines()[1].split(',')[3]

        assertEquals("0.05", amountColumn(5L))
        assertEquals("0.00", amountColumn(0L))
        assertEquals("10.00", amountColumn(1_000L))
    }

    @Test
    fun `csv keeps null next charge date as empty and null note as empty`() {
        val row = BackupCodec.exportCsv(listOf(minimalSubscription())).removePrefix("\uFEFF").lines()[1]
        val cols = row.split(',')
        assertEquals("", cols[5]) // next_charge_date
        assertEquals("", cols[7]) // note
        assertEquals("199.00", cols[3])
    }

    // ------------------------------------------------------------ v1 兼容

    @Test
    fun `v1 backup without payments and settings still imports`() {
        val v1 = """
            {
              "formatVersion": 1,
              "app": "SubTrack",
              "subscriptions": [
                {"id":"seed-music","name":"网易云音乐","categoryId":"music","cycle":"MONTHLY",
                 "anchorDate":"2025-09-26","nextChargeDate":"2026-10-27","priceMinor":1500,
                 "reminderOffsets":"3,0"}
              ]
            }
        """.trimIndent()
        val result = BackupCodec.importBackup(v1)

        assertEquals(1, result.added)
        assertNull(result.settings)
        assertEquals(listOf(3, 0), result.subscriptions.single().reminderOffsets)
        assertEquals(LocalDate.of(2025, 9, 26), result.subscriptions.single().anchorDate)
    }

    @Test
    fun `legacy importJson still returns the subscription list`() {
        val subs = BackupCodec.importJson(BackupCodec.exportJson(listOf(richSubscription())))
        assertEquals(listOf(richSubscription()), subs)
    }

    // ------------------------------------------------------------ 辅助

    private fun expectReject(text: String): BackupFormatException {
        try {
            BackupCodec.importBackup(text)
        } catch (e: BackupFormatException) {
            return e
        }
        fail("期望抛 BackupFormatException，但导入成功了")
        throw AssertionError("unreachable")
    }

    /**
     * 守护：设置的每个字段都必须能被备份往返。
     *
     * 这是本轮抓到的真实 bug——`BackupMapping` 的 settings 序列化只写了 3 个旧字段，
     * L1/L2 新增的 7 个字段全丢。导出再导入，主题/预算/汇率/hi 全没了，且不报错。
     *
     * 做法：构造一个"每个字段都不是默认值"的 AppSettings，往返后逐字段比对。
     * 加新字段时这条会红，逼你去改 BackupMapping 的写+读两处。
     */
    @Test
    fun `every settings field survives backup roundtrip`() {
        val full = AppSettings(
            defaultReminderOffsets = listOf(14, 7, 0),
            reminderTime = LocalTime.of(8, 15),
            notificationsBannerDismissed = true,
            themeMode = com.subtrack.domain.model.ThemeMode.DARK,
            appLockEnabled = true,
            monthlyBudgetMinor = 888_88L,
            priceWatchEnabled = true,
            healthCheckEnabled = true,
            multiCurrencyEnabled = true,
            fxRates = mapOf("USD" to 715L, "JPY" to 5L),
        )
        val text = BackupCodec.exportJson(listOf(minimalSubscription()), emptyList(), full)
        val back = BackupCodec.importBackup(text).settings!!

        assertEquals(full.defaultReminderOffsets, back.defaultReminderOffsets)
        assertEquals(full.reminderTime, back.reminderTime)
        assertEquals(full.notificationsBannerDismissed, back.notificationsBannerDismissed)
        assertEquals(full.themeMode, back.themeMode)
        assertEquals(full.appLockEnabled, back.appLockEnabled)
        assertEquals(full.monthlyBudgetMinor, back.monthlyBudgetMinor)
        assertEquals(full.priceWatchEnabled, back.priceWatchEnabled)
        assertEquals(full.healthCheckEnabled, back.healthCheckEnabled)
        assertEquals(full.multiCurrencyEnabled, back.multiCurrencyEnabled)
        assertEquals(full.fxRates, back.fxRates)
        // 整体比对兜底：将来加字段忘了在上面补断言，这条也能抓住
        assertEquals(full, back)
    }

    @Test
    fun `old backup without new settings fields falls back to defaults`() {
        // 模拟 L1 之前导出的备份：settings 只有 3 个旧字段
        val legacy = """
            {
              "formatVersion": 2,
              "app": "SubTrack",
              "subscriptions": [],
              "payments": [],
              "settings": {
                "defaultReminderOffsets": [3, 0],
                "reminderTime": "09:00",
                "notificationsBannerDismissed": false
              }
            }
        """.trimIndent()
        val settings = BackupCodec.importBackup(legacy).settings!!
        // 缺省字段必须回落到默认值，而不是报错（AC-57 允许可缺省字段缺省）
        assertEquals(com.subtrack.domain.model.ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(false, settings.appLockEnabled)
        assertNull(settings.monthlyBudgetMinor)
        assertEquals(emptyMap<String, Long>(), settings.fxRates)
    }

    /**
     * 断言序列化出来的 JSON 里**确实有这些键**，而不只是"往返能对上"。
     *
     * 为什么两条都要有：往返测试能抓"读不到"，但如果写和读同时漏了同一个字段，
     * 往返照样通过（两边都是默认值）。只有直接看 JSON 才能发现"没写出去"。
     */
    @Test
    fun `settings json contains all l1 l2 keys`() {
        val full = AppSettings(
            themeMode = com.subtrack.domain.model.ThemeMode.DARK,
            appLockEnabled = true,
            monthlyBudgetMinor = 300_00L,
            priceWatchEnabled = true,
            healthCheckEnabled = true,
            multiCurrencyEnabled = true,
            fxRates = mapOf("USD" to 720L),
        )
        val json = BackupCodec.exportJson(emptyList(), emptyList(), full)
        listOf(
            "themeMode", "appLockEnabled", "monthlyBudgetMinor",
            "priceWatchEnabled", "healthCheckEnabled", "multiCurrencyEnabled", "fxRates",
        ).forEach { key ->
            assertTrue("settings 里缺少键：$key", json.contains("\"$key\""))
        }
        // 值也要对（不只是键在）
        assertTrue(json.contains("DARK"))
        assertTrue(json.contains("30000"))
        assertTrue(json.contains("720"))
    }
}
