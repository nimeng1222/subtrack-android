package com.subtrack.domain.parse

import com.subtrack.domain.model.BillingCycleType

/**
 * 从剪贴板文本里解析出一笔支付信息（L3 零权限替代方案）。
 *
 * 背景：真正的"自动识别"要读通知或短信，那需要高敏权限，会毁掉本产品
 * "权限清单只有 5 个且无 INTERNET" 这个核心卖点。所以做了这个折中：
 * 用户在支付成功页复制文案 → 打开 App 时读剪贴板 → 解析成待确认草稿。
 *
 * 三条铁律：
 * 1. **只产出草稿，绝不自动写库**。误识别比不识别更伤信任。
 * 2. **纯函数**：不碰 Android API，才能在 JVM 单测里覆盖各种支付文案。
 * 3. **宁可少识别，不要错识别**：拿不准就返回 null，让用户手动录入。
 */
object PaymentTextParser {

    /** 解析结果。金额一律 Long 分。 */
    data class Parsed(
        /** 商户名（尽可能提取，提不到就用"未知商户"）。 */
        val merchant: String,
        /** 金额（分）。 */
        val amountMinor: Long,
        /** 推测的周期；仅当文案里出现"每月/包月/年费"等词时才给。 */
        val suggestedCycle: BillingCycleType?,
        /** 命中的平台，用于给用户看"识别自微信支付"。 */
        val source: String?,
        /** 原始文本片段，便于用户核对。 */
        val raw: String,
    ) {
        /** 置信度只在两处打折：金额是靠猜的、商户没认出来。 */
        val isConfident: Boolean
            get() = amountMinor > 0L && merchant != UNKNOWN_MERCHANT
    }

    const val UNKNOWN_MERCHANT = "未知商户"

    /** 已知平台关键词 → 展示名。命中即认为文本来自该平台的支付回执。 */
    private val PLATFORM_HINTS = listOf(
        "微信支付" to "微信支付",
        "微信" to "微信支付",
        "支付宝" to "支付宝",
        "Apple Pay" to "Apple Pay",
        "云闪付" to "云闪付",
        "银行" to "银行",
    )

    /** 周期关键词 → 周期类型。顺序有讲究：先匹配更具体的。 */
    private val CYCLE_HINTS = listOf(
        "连续包月" to BillingCycleType.MONTHLY,
        "包月" to BillingCycleType.MONTHLY,
        "每月" to BillingCycleType.MONTHLY,
        "月费" to BillingCycleType.MONTHLY,
        "连续包年" to BillingCycleType.YEARLY,
        "包年" to BillingCycleType.YEARLY,
        "年费" to BillingCycleType.YEARLY,
        "每年" to BillingCycleType.YEARLY,
        "季度" to BillingCycleType.QUARTERLY,
        "每季" to BillingCycleType.QUARTERLY,
    )

    /**
     * 金额正则。按"最不容易误判"的顺序尝试：
     * 1. 带货币符号的（¥25.00 / ￥25 / $9.99）；
     * 2. "金额" 后面的数字；
     * 3. 光秃秃的两位小数（最后兜底，最容易误判，所以放最后）。
     */
    private val AMOUNT_PATTERNS = listOf(
        Regex("""[¥￥]\s*([0-9]{1,6}(?:\.[0-9]{1,2})?)"""),
        Regex("""([0-9]{1,6}(?:\.[0-9]{1,2})?)\s*元"""),
        Regex("""(?:金额|付款|支付|扣款|实付|合计)[：:\s]*([0-9]{1,6}(?:\.[0-9]{1,2})?)"""),
        Regex("""\$\s*([0-9]{1,4}(?:\.[0-9]{1,2})?)"""),
        Regex("""([0-9]{1,6}\.[0-9]{2})"""),
    )

    /**
     * 解析一段文本。
     *
     * @return 解析失败返回 null（调用方应静默忽略，不要弹错误——用户只是复制了别的东西）。
     */
    fun parse(text: String?): Parsed? {
        if (text.isNullOrBlank()) return null
        // 太长的大概率不是支付文案（用户可能复制了整篇文章）
        val trimmed = text.trim()
        if (trimmed.length > 300) return null
        if (trimmed.lines().size > 6) return null

        val amountMinor = firstAmount(trimmed) ?: return null
        // 金额为 0 或离谱地大，都不是我们要的
        if (amountMinor <= 0L || amountMinor > 99_999_99L) return null

        val source = PLATFORM_HINTS.firstOrNull { trimmed.contains(it.first) }?.second
        val cycle = CYCLE_HINTS.firstOrNull { trimmed.contains(it.first) }?.second

        return Parsed(
            merchant = guessMerchant(trimmed, source),
            amountMinor = amountMinor,
            suggestedCycle = cycle,
            source = source,
            raw = trimmed.take(120),
        )
    }

    /** 金额：先试带符号的，再试"金额"前缀，最后才用裸小数兜底。 */
    private fun firstAmount(text: String): Long? {
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val raw = match.groupValues[1]
            val minor = yuanToMinor(raw) ?: continue
            if (minor > 0L) return minor
        }
        return null
    }

    /** "25" → 2500；"25.5" → 2550；"25.55" → 2555。全程整数，不碰 Double。 */
    private fun yuanToMinor(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val dot = text.indexOf('.')
        return if (dot < 0) {
            text.toLongOrNull()?.let { Math.multiplyExact(it, 100L) }
        } else {
            val yuan = text.substring(0, dot).toLongOrNull() ?: return null
            val fracRaw = text.substring(dot + 1)
            // 一位小数补 0，两位直接用；超过两位是解析器不该给我们的
            val frac = when (fracRaw.length) {
                1 -> fracRaw.toLongOrNull()?.times(10)
                2 -> fracRaw.toLongOrNull()
                else -> null
            } ?: return null
            Math.addExact(Math.multiplyExact(yuan, 100L), frac)
        }
    }

    /**
     * 商户名提取。两级策略，优先级从高到低：
     *
     * 1. **键值形式**（`商户：网易云音乐`、`商户名称: xxx`）——最可靠，直接取冒号后的值；
     * 2. **兜底**：去掉平台名/金额/噪声词后，取第一个像名字的片段。
     *
     * 认不出来就返回 [UNKNOWN_MERCHANT]，而不是硬凑——错名字比没名字更误导。
     */
    private fun guessMerchant(text: String, source: String?): String {
        // 1) 键值形式：冒号后面到行尾（或下一个分隔符）就是商户名
        MERCHANT_KEY_PATTERN.find(text)?.let { match ->
            val name = match.groupValues[1].trim()
                .split(Regex("""[\s，,。、：:；;（）()\[\]【】「」]+"""))
                .firstOrNull { it.length in 2..24 }
            if (name != null) return name
        }
        // 2) 兜底
        return guessMerchantFallback(text)
    }

    /** 商户名的键名变体。`商户名称`比`商户`长，要放前面才不会被截断。 */
    private val MERCHANT_KEY_PATTERN = Regex(
        """(?:商户名称|商户名|商户|收款方|商家|服务商|商品名称|项目)[：:\s]+([^\n]{2,30})""",
    )

    private fun guessMerchantFallback(text: String): String {
        // 去掉金额部分，避免把数字当名字
        var cleaned = text
        AMOUNT_PATTERNS.forEach { cleaned = cleaned.replace(it, " ") }
        // 去掉平台名
        PLATFORM_HINTS.forEach { cleaned = cleaned.replace(it.first, " ") }
        // 去掉常见噪声词
        listOf("支付成功", "付款成功", "已支付", "扣款成功", "消费", "元", "人民币", "金额", "实付")
            .forEach { cleaned = cleaned.replace(it, " ") }

        val candidate = cleaned
            .split(Regex("""[\s，,。.、：:；;（）()\[\]【】「」]+"""))
            .map { it.trim() }
            .filter { it.length in 2..24 }
            // 排除纯数字/纯符号
            .firstOrNull { token -> token.any { it.isLetter() || it.code > 0x4E00 } }
            ?: return UNKNOWN_MERCHANT

        return candidate
    }
}
