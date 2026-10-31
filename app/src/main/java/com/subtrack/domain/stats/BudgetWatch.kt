package com.subtrack.domain.stats

import com.subtrack.domain.model.Subscription
import java.time.LocalDate

/**
 * 预算与超支预警（L2，默认关闭）。
 *
 * 文档 §1.5 说"无数据密度时阈值无意义"——所以这里不做任何"智能推荐预算"，
 * 只做用户自己填上限之后的确定性判断。宁可功能弱，也不要假装懂用户。
 */
object BudgetWatch {

    data class Status(
        val budgetMinor: Long,
        /** 本月已扣 + 本月还将扣（现金流口径，与"本月将扣"一致）。 */
        val spentMinor: Long,
        val remainingMinor: Long,
        /** 0..1，可为 >1（超支）。 */
        val usedRatio: Double,
    ) {
        val isOver: Boolean get() = remainingMinor < 0
        val isNearLimit: Boolean get() = !isOver && usedRatio >= 0.8
    }

    /**
     * @param budgetMinor 用户设定的月度上限；null 时返回 null（不做任何提示）。
     * @param monthSpentMinor 本月已经发生的扣费合计（来自 payment_record）。
     * @param monthUpcomingMinor 本月还将扣的合计（来自订阅的下次扣费日）。
     */
    fun status(
        budgetMinor: Long?,
        monthSpentMinor: Long,
        monthUpcomingMinor: Long,
    ): Status? {
        if (budgetMinor == null || budgetMinor <= 0L) return null
        val used = com.subtrack.core.money.AmountMath.add(monthSpentMinor, monthUpcomingMinor)
        return Status(
            budgetMinor = budgetMinor,
            spentMinor = used,
            remainingMinor = budgetMinor - used,
            usedRatio = used.toDouble() / budgetMinor,
        )
    }

    /**
     * 按当前订阅推算"这个月会不会超"。用于设置预算时给一句人话反馈，
     * 而不是让用户自己拿月均去比。
     */
    fun projection(subs: List<Subscription>, today: LocalDate, budgetMinor: Long?): Status? =
        status(
            budgetMinor = budgetMinor,
            monthSpentMinor = 0L,
            monthUpcomingMinor = MonthlyCashflow.dueThisMonthMinor(subs, today),
        )
}
