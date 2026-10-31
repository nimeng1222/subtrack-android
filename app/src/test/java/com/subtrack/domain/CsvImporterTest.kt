package com.subtrack.domain

import com.subtrack.data.backup.BackupCodec
import com.subtrack.data.backup.CsvImporter
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * CSV 导入的验收测试。
 *
 * 重点不在"能读"，而在**读回来的东西和导出去的一致**：
 * 往返（[BackupCodec.exportCsv] → [CsvImporter.parse]）一旦失真，用户就会在 Excel 里改一次、
 * 导回来一次、发现数据变了，然后再也不敢用这个功能。
 *
 * 所有断言都显式传 [CsvImporter.parse] 的 `today`，不依赖真实时钟，跨零点跑也不会抖。
 */
class CsvImporterTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 26)

    private val header = "name,category,cycle,price_yuan,start_date,next_charge_date,status,note"

    // ------------------------------------------------------------ 1. 真往返

    @Test
    fun `csv exported by BackupCodec imports back unchanged`() {
        val exported = listOf(
            Subscription(
                id = "sub-1",
                name = "网易云音乐",
                categoryId = "music",
                cycle = BillingCycleType.MONTHLY,
                anchorDate = LocalDate.of(2025, 9, 26),
                nextChargeDate = LocalDate.of(2026, 11, 26),
                priceMinor = 1_500L,
                status = SubscriptionStatus.ACTIVE,
                note = "学生优惠",
            ),
            Subscription(
                id = "sub-2",
                name = "腾讯视频 VIP",
                categoryId = "video",
                cycle = BillingCycleType.YEARLY,
                anchorDate = LocalDate.of(2025, 3, 1),
                nextChargeDate = LocalDate.of(2027, 3, 1),
                priceMinor = 25_500L,
                status = SubscriptionStatus.TRIAL,
                note = null,
            ),
            Subscription(
                id = "sub-3",
                name = "买断 · 一次性",
                categoryId = "other",
                cycle = BillingCycleType.ONE_TIME,
                anchorDate = LocalDate.of(2026, 1, 5),
                nextChargeDate = null,
                priceMinor = 19_900L,
                status = SubscriptionStatus.EXPIRED,
                note = "备注,含逗号",
            ),
        )

        val result = CsvImporter.parse(BackupCodec.exportCsv(exported), today = today)

        assertTrue("往返不应产生任何行错误：${result.errors}", result.errors.isEmpty())
        assertEquals(3, result.totalRows)
        assertEquals(3, result.subscriptions.size)

        // id 是按行号重算的，不能和原 id 比；其余业务字段必须逐条一致
        val imported = result.subscriptions
        assertEquals(exported.map { it.name }, imported.map { it.name })
        assertEquals(exported.map { it.categoryId }, imported.map { it.categoryId })
        assertEquals(exported.map { it.cycle }, imported.map { it.cycle })
        assertEquals(exported.map { it.priceMinor }, imported.map { it.priceMinor })
        assertEquals(exported.map { it.anchorDate }, imported.map { it.anchorDate })
        assertEquals(exported.map { it.nextChargeDate }, imported.map { it.nextChargeDate })
        assertEquals(exported.map { it.status }, imported.map { it.status })
        assertEquals(exported.map { it.note }, imported.map { it.note })
    }

    // ------------------------------------------------------------ 2. BOM

    @Test
    fun `bom is optional`() {
        val row = "网易云音乐,music,MONTHLY,15.00,2026-01-01,,ACTIVE,"
        val withBom = CsvImporter.parse("\uFEFF$header\n$row", today = today)
        val withoutBom = CsvImporter.parse("$header\n$row", today = today)

        assertEquals(1, withBom.subscriptions.size)
        assertEquals(1, withoutBom.subscriptions.size)
        // 有无 BOM 必须得到完全相同的结果，否则"Windows 记事本另存"就会改变导入行为
        assertEquals(withoutBom.subscriptions, withBom.subscriptions)
        assertTrue(withBom.errors.isEmpty())
        assertTrue(withoutBom.errors.isEmpty())
    }

    // ------------------------------------------------------------ 3. 列序 / 列名

    @Test
    fun `columns are matched by name not position`() {
        // 故意打乱列序，并用中文表头：用户在 Excel 里拖过列就是这个样子
        val text = """
            备注,金额,名称,状态,周期,开始日期,下次到期日,分类
            年付更划算,￥99.00,百度网盘,生效,年付,2025-06-01,2026-06-01,cloud
        """.trimIndent()

        val sub = CsvImporter.parse(text, today = today).subscriptions.single()
        assertEquals("百度网盘", sub.name)
        assertEquals("cloud", sub.categoryId)
        assertEquals(BillingCycleType.YEARLY, sub.cycle)
        assertEquals(9_900L, sub.priceMinor)
        assertEquals(LocalDate.of(2025, 6, 1), sub.anchorDate)
        assertEquals(LocalDate.of(2026, 6, 1), sub.nextChargeDate)
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals("年付更划算", sub.note)
    }

    @Test
    fun `unknown extra columns are ignored`() {
        val text = """
            name,给用户看的备注,cycle,price_yuan,start_date,next_charge_date,status,note,id
            网易云音乐,随便写,MONTHLY,15,2026-01-01,,ACTIVE,正式备注,existing-id
        """.trimIndent()

        val sub = CsvImporter.parse(text, today = today).subscriptions.single()
        assertEquals("网易云音乐", sub.name)
        assertEquals("正式备注", sub.note)
        // CSV 里的 id 列不参与决策（导出本来也没有这一列），id 始终由行号+名称确定性生成
        assertTrue(sub.id.startsWith("csv-"))
    }

    // ------------------------------------------------------------ 4. RFC 4180 引号

    @Test
    fun `quoted fields may contain commas newlines and double quotes`() {
        val text = header + "\n" +
            "\"套餐, 含逗号\",video,MONTHLY,15.00,2026-01-01,,ACTIVE,\"第一行\n第二行，还有\"\"引号\"\"\"\n"

        val result = CsvImporter.parse(text, today = today)
        assertTrue(result.errors.isEmpty())
        val sub = result.subscriptions.single()
        assertEquals("套餐, 含逗号", sub.name)
        // 换行和翻倍的引号都要原样还原，不能留下 "" 或吃掉换行
        assertEquals("第一行\n第二行，还有\"引号\"", sub.note)
    }

    @Test
    fun `unterminated quote is reported instead of silently dropping data`() {
        val text = header + "\n网易云音乐,music,MONTHLY,15.00,2026-01-01,,ACTIVE,\"没闭合的备注\n"
        val result = CsvImporter.parse(text, today = today)

        assertTrue(result.subscriptions.isEmpty())
        // 引号没闭合只能放弃：后面的内容都在引号里，无法判断记录边界
        assertEquals(1, result.errors.size)
        assertEquals(2, result.errors.single().lineNumber)
    }

    // ------------------------------------------------------------ 5. 金额

    @Test
    fun `amounts are converted to minor units exactly`() {
        fun minorOf(raw: String): Long? {
            val text = header + "\n网易云音乐,music,MONTHLY,\"$raw\",2026-01-01,,ACTIVE,\n"
            return CsvImporter.parse(text, today = today).subscriptions.firstOrNull()?.priceMinor
        }

        assertEquals(1_500L, minorOf("15"))        // 整数元
        assertEquals(1_550L, minorOf("15.5"))      // 一位小数
        assertEquals(1_550L, minorOf("15.50"))     // 两位小数
        assertEquals(1_500L, minorOf("￥15.00"))    // 全角人民币符号
        assertEquals(1_500L, minorOf("¥15.00"))    // 半角人民币符号
        assertEquals(1_299_00L, minorOf("¥1,299.00")) // 带千分位
        assertEquals(1_500L, minorOf("15元"))       // 带中文单位
        assertEquals(5L, minorOf("0.05"))          // 分位补零
        assertEquals(0L, minorOf("0"))             // 免费
        assertEquals(19_900L, minorOf("199.00"))

        // 超过两位小数必须拒绝：四舍五入等于替用户改账
        assertNull(minorOf("15.555"))
        assertNull(minorOf("15.5.5"))
        assertNull(minorOf("abc"))
        assertNull(minorOf("-15"))
        assertNull(minorOf("1e3"))
    }

    // ------------------------------------------------------------ 6. 逐行容错

    @Test
    fun `bad rows are reported without breaking good ones`() {
        val text = """
            $header
            网易云音乐,music,MONTHLY,15.00,2026-01-01,,ACTIVE,正常行
            坏金额,music,MONTHLY,十五块,2026-01-01,,ACTIVE,
            坏日期,video,YEARLY,199.00,2026-02-30,,ACTIVE,
            坏周期,ai,MONTHLYY,20.00,2026-01-01,,ACTIVE,
            坏状态,other,MONTHLY,20.00,2026-01-01,,ACTIVATED,
            ,,MONTHLY,20.00,2026-01-01,,ACTIVE,
            ,,,,,
            腾讯视频,video,YEARLY,255.00,2025-03-01,2027-03-01,TRIAL,正常行
        """.trimIndent()

        val result = CsvImporter.parse(text, today = today)

        // 好的两条照样导入，不会被坏行连累
        assertEquals(listOf("网易云音乐", "腾讯视频"), result.subscriptions.map { it.name })
        // 纯空行 `,,,,,` 是 Excel 另存的常见尾巴，不计入 totalRows（实现约定见 CsvImporter）
        assertEquals(7, result.totalRows)
        // 金额 / 日期 / 周期 / 状态 / 空名称 = 5 条错误
        assertEquals(5, result.errors.size)
        assertTrue(result.errors[0].message.contains("金额"))
        assertTrue(result.errors[1].message.contains("开始日期"))
        assertTrue(result.errors[2].message.contains("周期"))
        assertTrue(result.errors[3].message.contains("状态"))
        assertTrue(result.errors[4].message.contains("名称"))
    }

    // ------------------------------------------------------------ 7. 行号

    @Test
    fun `row errors carry the real physical line number`() {
        // 第 1 行是表头，所以第 3 条数据在第 4 行
        val text = """
            $header
            第一条,music,MONTHLY,15.00,2026-01-01,,ACTIVE,
            第二条,music,MONTHLY,16.00,2026-01-01,,ACTIVE,
            第三条,music,MONTHLY,不是数字,2026-01-01,,ACTIVE,
            第四条,music,MONTHLY,18.00,2026-01-01,,ACTIVE,
        """.trimIndent()

        val result = CsvImporter.parse(text, today = today)

        assertEquals(4, result.totalRows)
        assertEquals(3, result.subscriptions.size)
        assertEquals(1, result.errors.size)
        assertEquals(4, result.errors.single().lineNumber)
    }

    @Test
    fun `line numbers stay correct after a multi line field`() {
        // 第一条的备注里含换行：它占了第 2、3 两行，所以第二条数据从第 4 行开始。
        // 行号如果只在记录边界 +1（而不是逐字符数换行），这里会错报成 3。
        val text = header + "\n" +
            "第一条,video,MONTHLY,15.00,2026-01-01,,ACTIVE,\"第一行\n第二行\"\n" +
            "第二条,video,MONTHLY,坏金额,2026-01-01,,ACTIVE,\n"

        val result = CsvImporter.parse(text, today = today)

        assertEquals(2, result.totalRows)
        assertEquals(1, result.subscriptions.size)
        assertEquals(4, result.errors.single().lineNumber)
    }

    // ------------------------------------------------------------ 8. 幂等

    @Test
    fun `importing the same text twice does not duplicate`() {
        val text = BackupCodec.exportCsv(
            listOf(
                Subscription(
                    id = "a",
                    name = "网易云音乐",
                    categoryId = "music",
                    cycle = BillingCycleType.MONTHLY,
                    anchorDate = today,
                    nextChargeDate = null,
                    priceMinor = 1_500L,
                ),
                Subscription(
                    id = "b",
                    name = "百度网盘",
                    categoryId = "cloud",
                    cycle = BillingCycleType.YEARLY,
                    anchorDate = today,
                    nextChargeDate = null,
                    priceMinor = 9_900L,
                ),
            ),
        )

        val first = CsvImporter.parse(text, today = today)
        assertEquals(2, first.subscriptions.size)
        // id 必须是确定性的，否则第二次解析拿不到同样的 id，"幂等"无从谈起
        assertEquals(first.subscriptions.map { it.id }, CsvImporter.parse(text, today = today).subscriptions.map { it.id })

        val second = CsvImporter.parse(
            text,
            existingIds = first.subscriptions.map { it.id }.toSet(),
            today = today,
        )
        assertTrue(second.subscriptions.isEmpty())
        assertTrue(second.errors.isEmpty())
        // totalRows 是"文件里有多少数据行"，不会因为跳过而变少
        assertEquals(2, second.totalRows)
        assertEquals(2, second.skipped)
    }

    // ------------------------------------------------------------ 9. 中英文枚举

    @Test
    fun `chinese and english cycle and status names both parse`() {
        val chineseToEnglish = mapOf(
            "周付" to BillingCycleType.WEEKLY,
            "双周付" to BillingCycleType.BIWEEKLY,
            "四周付" to BillingCycleType.FOUR_WEEKLY,
            "月付" to BillingCycleType.MONTHLY,
            "季付" to BillingCycleType.QUARTERLY,
            "半年付" to BillingCycleType.SEMIANNUAL,
            "年付" to BillingCycleType.YEARLY,
            "自定义" to BillingCycleType.CUSTOM_DAYS,
            "一次性" to BillingCycleType.ONE_TIME,
        )
        chineseToEnglish.forEach { (chinese, expected) ->
            val text = "$header\n网易云音乐,music,$chinese,15.00,2026-01-01,,ACTIVE,\n"
            val result = CsvImporter.parse(text, today = today)
            assertTrue("中文周期「$chinese」应被识别：${result.errors}", result.errors.isEmpty())
            assertEquals("中文周期「$chinese」", expected, result.subscriptions.single().cycle)
        }

        val chineseStatus = mapOf(
            "生效" to SubscriptionStatus.ACTIVE,
            "试用" to SubscriptionStatus.TRIAL,
            "暂停" to SubscriptionStatus.PAUSED,
            "已取消" to SubscriptionStatus.CANCELED,
            "已过期" to SubscriptionStatus.EXPIRED,
        )
        chineseStatus.forEach { (chinese, expected) ->
            val text = "$header\n网易云音乐,music,MONTHLY,15.00,2026-01-01,,$chinese,\n"
            val result = CsvImporter.parse(text, today = today)
            assertTrue("中文状态「$chinese」应被识别：${result.errors}", result.errors.isEmpty())
            assertEquals("中文状态「$chinese」", expected, result.subscriptions.single().status)
        }

        // 英文枚举名（导出的写法）同样认，且大小写不敏感
        val english = "$header\n网易云音乐,music,quarterly,15.00,2026-01-01,,trial,\n"
        val sub = CsvImporter.parse(english, today = today).subscriptions.single()
        assertEquals(BillingCycleType.QUARTERLY, sub.cycle)
        assertEquals(SubscriptionStatus.TRIAL, sub.status)
    }

    @Test
    fun `unknown cycle is an error instead of defaulting to monthly`() {
        val text = "$header\n网易云音乐,music,EVERY_FULL_MOON,15.00,2026-01-01,,ACTIVE,\n"
        val result = CsvImporter.parse(text, today = today)

        assertTrue(result.subscriptions.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().message.contains("EVERY_FULL_MOON"))
    }

    // ------------------------------------------------------------ 10. 空输入

    @Test
    fun `empty text and header only produce zero rows and no errors`() {
        val empty = CsvImporter.parse("", today = today)
        assertEquals(0, empty.totalRows)
        assertTrue(empty.subscriptions.isEmpty())
        assertTrue(empty.errors.isEmpty())

        val onlyBom = CsvImporter.parse("\uFEFF", today = today)
        assertEquals(0, onlyBom.totalRows)
        assertTrue(onlyBom.errors.isEmpty())

        val headerOnly = CsvImporter.parse(header, today = today)
        assertEquals(0, headerOnly.totalRows)
        assertTrue(headerOnly.subscriptions.isEmpty())
        assertTrue(headerOnly.errors.isEmpty())

        // 导出空列表 = BOM + 表头，必须能被自己读回
        val exportedEmpty = BackupCodec.exportCsv(emptyList())
        val roundtripEmpty = CsvImporter.parse(exportedEmpty, today = today)
        assertEquals(0, roundtripEmpty.totalRows)
        assertTrue(roundtripEmpty.errors.isEmpty())
    }

    // ------------------------------------------------------------ 11. 缺省字段

    @Test
    fun `missing start date falls back to the injected today`() {
        val text = "$header\n网易云音乐,music,MONTHLY,15.00,,,ACTIVE,\n"
        val sub = CsvImporter.parse(text, today = today).subscriptions.single()

        // 用注入的 today 而不是 LocalDate.now()，这条断言才可能稳定
        assertEquals(today, sub.anchorDate)
        assertNull(sub.nextChargeDate)
    }

    @Test
    fun `custom cycle days defaults instead of failing the roundtrip`() {
        // 导出文件不带 customCycleDays，若这里报错，自定义周期的数据就再也导不回来
        val text = "$header\n网易云音乐,music,CUSTOM_DAYS,15.00,2026-01-01,,ACTIVE,\n"
        val result = CsvImporter.parse(text, today = today)

        assertTrue(result.errors.isEmpty())
        assertEquals(BillingCycleType.CUSTOM_DAYS, result.subscriptions.single().cycle)
        assertEquals(30, result.subscriptions.single().customCycleDays)
    }

    @Test
    fun `missing category column falls back to other`() {
        val text = "name,cycle,price_yuan,start_date\n网易云音乐,MONTHLY,15,2026-01-01"
        val sub = CsvImporter.parse(text, today = today).subscriptions.single()
        assertEquals("other", sub.categoryId)
    }

    // ------------------------------------------------------------ 12. 文件名

    @Test
    fun `default file name is zero padded`() {
        assertEquals("SubTrack-20261026.csv", CsvImporter.defaultFileName(today))
        assertEquals("SubTrack-20260105.csv", CsvImporter.defaultFileName(LocalDate.of(2026, 1, 5)))
    }
}
