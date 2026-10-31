package com.subtrack.ai

import com.subtrack.core.money.Money
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 发给模型的台账摘要。
 *
 * 用户选择"自动带上台账"，所以这里把**回答问题必需的最小事实**整理成一段纯文本：
 * 名称、金额/币种、周期、下次扣费、状态，加上近 90 天与本月的实付合计。
 * 刻意不含：备注、供应商链接、内部 ID、备份内容、任何密钥。
 */
object LedgerDigest {
    private const val MAX_ITEMS = 40

    fun build(
        subscriptions: List<Subscription>,
        today: LocalDate,
        recentPaidMinor: Long?,
        monthPaidMinor: Long?,
    ): String {
        if (subscriptions.isEmpty()) return "本机台账：暂无订阅（用户还没有录入订阅）。"
        val listed = subscriptions.sortedBy { it.nextChargeDate ?: LocalDate.MAX }.take(MAX_ITEMS)
        val sb = StringBuilder()
        sb.append("本机台账（截至 ").append(today).append("，共 ").append(subscriptions.size).append(" 条）：\n")
        listed.forEach { sub ->
            sb.append("· ").append(sub.name)
                .append("｜").append(priceText(sub))
                .append("｜").append(cycleText(sub))
            sub.nextChargeDate?.let { date ->
                val days = ChronoUnit.DAYS.between(today, date)
                sb.append("｜下次 ").append(date)
                sb.append(if (days >= 0) "（${days} 天后）" else "（已过 ${-days} 天，待确认）")
            }
            sb.append("｜").append(stateText(sub))
            sb.append('\n')
        }
        if (subscriptions.size > MAX_ITEMS) {
            sb.append("（其余 ").append(subscriptions.size - MAX_ITEMS).append(" 条未列出）\n")
        }
        recentPaidMinor?.let { sb.append("近 90 天实付合计：").append(Money.format(it)).append('\n') }
        monthPaidMinor?.let { sb.append("本月已付：").append(Money.format(it)).append('\n') }
        sb.append("注：金额是记账原币；非人民币条目未做折算，回答时不要把它们直接相加。")
        return sb.toString()
    }

    private fun priceText(sub: Subscription): String {
        val amount = runCatching { Money.format(sub.priceMinor) }.getOrDefault("${sub.priceMinor / 100.0}")
        return if (sub.currency == "CNY") amount else "$amount ${sub.currency}"
    }

    private fun stateText(sub: Subscription): String = when (sub.status) {
        SubscriptionStatus.PAUSED -> "已暂停"
        SubscriptionStatus.CANCELED -> "已取消"
        SubscriptionStatus.EXPIRED -> "已结束"
        SubscriptionStatus.TRIAL -> "试用中"
        else -> "生效中"
    }

    private fun cycleText(sub: Subscription): String = when (sub.cycle) {
        BillingCycleType.WEEKLY -> "每周"
        BillingCycleType.BIWEEKLY -> "每两周"
        BillingCycleType.FOUR_WEEKLY -> "每四周"
        BillingCycleType.MONTHLY -> "每月"
        BillingCycleType.QUARTERLY -> "每季度"
        BillingCycleType.SEMIANNUAL -> "每半年"
        BillingCycleType.YEARLY -> "每年"
        BillingCycleType.CUSTOM_DAYS -> "每 ${sub.customCycleDays ?: "?"} 天"
        BillingCycleType.ONE_TIME -> "一次性"
    }
}
