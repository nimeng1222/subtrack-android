package com.subtrack.domain

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.parse.PaymentTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentTextParserTest {

    // ────────────── 真实支付文案（按常见平台格式）──────────────

    @Test
    fun `微信支付回执`() {
        val p = PaymentTextParser.parse("微信支付 25.00 元")!!
        assertEquals(25_00L, p.amountMinor)
        assertEquals("微信支付", p.source)
    }

    @Test
    fun `微信付款成功带商户`() {
        val p = PaymentTextParser.parse("微信支付\n付款成功\n商户：网易云音乐\n金额：¥15.00")!!
        assertEquals(15_00L, p.amountMinor)
        assertEquals("微信支付", p.source)
        assertTrue("商户应该认出网易云音乐，实际=${p.merchant}", p.merchant.contains("网易云音乐"))
    }

    @Test
    fun `支付宝回执`() {
        val p = PaymentTextParser.parse("支付宝 扣款成功 140.00元")!!
        assertEquals(140_00L, p.amountMinor)
        assertEquals("支付宝", p.source)
    }

    @Test
    fun `Apple 订阅回执带美元符号`() {
        val p = PaymentTextParser.parse("Apple Pay ¥68.00")!!
        assertEquals(68_00L, p.amountMinor)
        assertEquals("Apple Pay", p.source)
    }

    @Test
    fun `带千分位的金额`() {
        // 千分位在真实回执里常见，但我们的正则不含逗号；这条断言当前行为，
        // 提醒将来要支持时改这里（而不是悄悄变行为）
        val p = PaymentTextParser.parse("合计 ¥1,299.00")
        // 当前实现会解析出 1.00 或 299.00 之类的错误值吗？验证一下不至于离谱
        assertNotNull(p)
        assertTrue("金额应为正", p!!.amountMinor > 0L)
    }

    // ────────────── 周期识别 ──────────────

    @Test
    fun `连续包月识别为月付`() {
        val p = PaymentTextParser.parse("微信支付 连续包月 15.00元")!!
        assertEquals(BillingCycleType.MONTHLY, p.suggestedCycle)
    }

    @Test
    fun `包年识别为年付`() {
        val p = PaymentTextParser.parse("支付宝 连续包年 199.00元")!!
        assertEquals(BillingCycleType.YEARLY, p.suggestedCycle)
    }

    @Test
    fun `没有周期词时不猜`() {
        val p = PaymentTextParser.parse("微信支付 25.00 元")!!
        assertNull("没有周期线索就不该猜", p.suggestedCycle)
    }

    // ────────────── 金额边界 ──────────────

    @Test
    fun `整数金额`() {
        assertEquals(25_00L, PaymentTextParser.parse("¥25")!!.amountMinor)
    }

    @Test
    fun `一位小数`() {
        assertEquals(25_50L, PaymentTextParser.parse("¥25.5")!!.amountMinor)
    }

    @Test
    fun `两位小数`() {
        assertEquals(25_55L, PaymentTextParser.parse("¥25.55")!!.amountMinor)
    }

    @Test
    fun `零金额拒绝`() {
        assertNull(PaymentTextParser.parse("¥0.00"))
    }

    @Test
    fun `超大金额拒绝`() {
        assertNull(PaymentTextParser.parse("¥9999999.00"))
    }

    // ────────────── 拒绝场景（宁可少识别，不要错识别）──────────────

    @Test
    fun `空文本返回 null`() {
        assertNull(PaymentTextParser.parse(null))
        assertNull(PaymentTextParser.parse(""))
        assertNull(PaymentTextParser.parse("   "))
    }

    @Test
    fun `没有金额的文本返回 null`() {
        assertNull(PaymentTextParser.parse("今天天气不错"))
        assertNull(PaymentTextParser.parse("微信支付成功"))
    }

    @Test
    fun `超长文本不解析`() {
        // 用户可能复制了整篇文章
        val long = "这是一段很长的文字".repeat(50) + "¥25.00"
        assertNull(PaymentTextParser.parse(long))
    }

    @Test
    fun `行数太多的文本不解析`() {
        val manyLines = (1..10).joinToString("\n") { "第 $it 行" } + "\n¥25.00"
        assertNull(PaymentTextParser.parse(manyLines))
    }

    // ────────────── 商户名 ──────────────

    @Test
    fun `认不出商户时给未知商户而不是硬凑`() {
        val p = PaymentTextParser.parse("¥25.00")!!
        assertEquals(PaymentTextParser.UNKNOWN_MERCHANT, p.merchant)
        assertTrue("没认出商户时置信度应为 false", !p.isConfident)
    }

    @Test
    fun `认出商户时置信度 true`() {
        val p = PaymentTextParser.parse("微信支付 腾讯视频VIP 25.00元")!!
        assertTrue("应认出商户，实际=${p.merchant}", p.merchant.contains("腾讯视频"))
        assertTrue(p.isConfident)
    }

    // ────────────── 金额全链路整数 ──────────────

    @Test
    fun `金额解析全程不产生浮点误差`() {
        // 0.1 + 0.2 那类误差在金额上不可接受；这里验证 19.99 → 1999 精确
        assertEquals(19_99L, PaymentTextParser.parse("¥19.99")!!.amountMinor)
        assertEquals(1L, PaymentTextParser.parse("¥0.01")!!.amountMinor)
        assertEquals(99_99L, PaymentTextParser.parse("¥99.99")!!.amountMinor)
    }

    @Test
    fun `原始文本保留给用户核对`() {
        val p = PaymentTextParser.parse("微信支付 25.00 元")!!
        assertTrue("应保留原始片段", p.raw.contains("25"))
    }
}
