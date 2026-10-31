package com.subtrack.domain.stats

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import java.time.LocalDate

/**
 * 订阅体检（L2，默认关闭）。
 *
 * 文档 §11.3 给的思路是"用 payment_record + 分类占比"，并指出更强的版本需要读设备使用统计
 * （那要 PACKAGE_USAGE_STATS 权限）。这里刻意只做**不出权限**的版本：
 * 靠"用户多久没处理过它"和"它占月均的比重"来判断价值，不假装知道用户用不用。
 *
 * 这一层只产出"值得看一眼"的条目，不替用户下结论——标记取消仍由用户决定。
 */
object SubscriptionHealth {

    enum class Severity { INFO, WARN }

    data class Finding(
        val subscription: Subscription,
        val severity: Severity,
        val title: String,
        val detail: String,
        /** 建议的动作，UI 上直接渲染成按钮文案。 */
        val suggestedAction: String,
    ) {
        val id: String get() = subscription.id
    }

    /**
     * 体检规则（全部是确定性判断，不猜）：
     * 1. 待确认超过 2 个周期 → 提醒处理，否则账会失真；
     * 2. 单条占月均 > 30% 且是月付及以上 → 建议复核是否还在用（大额订阅最值得审视）；
     * 3. 一次性买断超过 1 年 → 建议归档（不再产生任何价值，只占列表）；
     * 4. 年付但下次扣费在 60 天内 → 提前提醒，年付最容易在续费时才想起来。
     */
    fun findings(
        subs: List<Subscription>,
        today: LocalDate,
        forecastMonthlyMinor: Long,
    ): List<Finding> {
        val out = ArrayList<Finding>()

        subs.filter { it.isForecastIncluded }.forEach { sub ->
            val next = sub.nextChargeDate
            val overdueDays = if (next != null && next.isBefore(today)) {
                java.time.temporal.ChronoUnit.DAYS.between(next, today)
            } else {
                0L
            }

            // 规则 1：待确认过久
            if (overdueDays > 0) {
                val periods = overduePeriods(sub, overdueDays)
                if (periods >= 2) {
                    out += Finding(
                        subscription = sub,
                        severity = Severity.WARN,
                        title = "${sub.name} 已过期 $overdueDays 天",
                        detail = "超过 $periods 个周期没确认，统计里的月均已经不可信了",
                        suggestedAction = "去处理",
                    )
                }
            }

            // 规则 2：大额订阅占比过高
            if (forecastMonthlyMinor > 0L && forecastMonthlyMinor != Long.MAX_VALUE) {
                val share = annualShare(sub, forecastMonthlyMinor)
                if (share >= 0.30) {
                    out += Finding(
                        subscription = sub,
                        severity = Severity.INFO,
                        title = "${sub.name} 占月均 ${(share * 100).toInt()}%",
                        detail = "单条占比偏高，值得确认是否还在用",
                        suggestedAction = "查看",
                    )
                }
            }

            // 规则 3：一次性买断超过一年
            if (sub.cycle == BillingCycleType.ONE_TIME) {
                val paid = sub.nextChargeDate ?: sub.anchorDate
                val days = java.time.temporal.ChronoUnit.DAYS.between(paid, today)
                if (days > 365) {
                    out += Finding(
                        subscription = sub,
                        severity = Severity.INFO,
                        title = "${sub.name} 已经一年以上",
                        detail = "一次性买断不再产生价值，可以归档让列表更清爽",
                        suggestedAction = "归档",
                    )
                }
            }

            // 规则 4：年付即将续费
            if (sub.cycle == BillingCycleType.YEARLY && next != null && !next.isBefore(today)) {
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, next)
                if (days <= 60) {
                    out += Finding(
                        subscription = sub,
                        severity = Severity.INFO,
                        title = "${sub.name} 还有 $days 天年付续费",
                        detail = "年付金额较大，建议提前决定是否续",
                        suggestedAction = "查看",
                    )
                }
            }
        }

        // WARN 优先，同级按标题稳定排序（避免每次进入顺序都变）
        return out.sortedWith(
            compareBy({ it.severity.ordinal }, { it.title }),
        ).reversed()
    }

    /** 逾期了几个周期：用下一次扣费日的推进步长反推。 */
    private fun overduePeriods(sub: Subscription, overdueDays: Long): Int {
        val step = when (sub.cycle) {
            BillingCycleType.WEEKLY -> 7L
            BillingCycleType.BIWEEKLY -> 14L
            BillingCycleType.FOUR_WEEKLY -> 28L
            BillingCycleType.MONTHLY -> 30L
            BillingCycleType.QUARTERLY -> 91L
            BillingCycleType.SEMIANNUAL -> 182L
            BillingCycleType.YEARLY -> 365L
            BillingCycleType.CUSTOM_DAYS -> (sub.customCycleDays ?: 30).toLong()
            BillingCycleType.ONE_TIME -> 3650L
        }
        return (overdueDays / step).toInt()
    }

    /** 该条年化占"月均 × 12"的比例。 */
    private fun annualShare(sub: Subscription, forecastMonthlyMinor: Long): Double {
        val annual = com.subtrack.domain.stats.Annualizer
            .annualizeMinor(sub.priceMinor, sub.cycle, sub.customCycleDays)
        val totalAnnual = forecastMonthlyMinor.toBigInteger() * 12.toBigInteger()
        if (totalAnnual.signum() <= 0) return 0.0
        return annual.toDouble() / totalAnnual.toDouble()
    }
}
