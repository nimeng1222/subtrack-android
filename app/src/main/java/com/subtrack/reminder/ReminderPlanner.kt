package com.subtrack.reminder

import com.subtrack.core.time.AppClock
import com.subtrack.domain.cycle.CycleEngine
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class ReminderKind {
    /** 提前 N 天。 */
    ADVANCE,

    /** 到期当天。 */
    ONSET,

    /** AC-25：过期后第 1/3/7 天的补发。 */
    FOLLOW_UP,

    /**
     * "明天再说"的持久明日计划。由 ReminderService 直接落库（不经过 plan()），
     * 这样逾期超过 7 天、规划器不再生成任何节点时明日提醒仍然存在；
     * 发送前接收端会校验订阅与期次仍有效，确认续费/取消后自然作废。
     */
    SNOOZE,
}

data class PlannedReminder(
    val subscriptionId: String,
    val kind: ReminderKind,
    /** ADVANCE 为正数；ONSET 为 0；FOLLOW_UP 为过期天数（正数）。 */
    val offsetDays: Int,
    val chargeDate: LocalDate,
    val fireAtMillis: Long,
)

/**
 * 提醒规划。AC-21~AC-25 的规则集中在这里，UI 与 Service 不得自行判断。
 *
 * 设计取舍：只把 **ONSET**（到期当天）注册成精确闹钟，其余（提前 N 天、过期补发）
 * 由每日 09:00 的扫描补发。精确闹钟配额与后台唤醒都有代价，而提前提醒对时刻容忍度高
 * ——错半小时用户无感，但漏一条就失信。
 *
 * 提醒时刻由 [AppSettings.reminderTime] 注入（裁决 D8），禁止在这里硬编码 09:00。
 */
class ReminderPlanner(private val clock: AppClock) {

    /** 过期补发只在第 1/3/7 天各发一次，之后只在应用内标记（AC-25）。 */
    private val followUpDays = listOf(1, 3, 7)

    fun plan(
        sub: Subscription,
        settings: AppSettings = AppSettings(),
        horizonDays: Long = 30,
    ): List<PlannedReminder> {
        if (sub.isDeleted ||
            sub.status == SubscriptionStatus.PAUSED ||
            sub.status == SubscriptionStatus.CANCELED ||
            sub.status == SubscriptionStatus.EXPIRED
        ) return emptyList()

        val offsets = AppSettings.sanitizeOffsets(sub.reminderOffsets)
        if (offsets.isEmpty()) return emptyList()

        val now = clock.today()
        val time = settings.reminderTime
        val firstCharge = sub.currentChargeDate() ?: return emptyList()
        val result = mutableListOf<PlannedReminder>()

        // ── 逾期补发（AC-25）：本条已过期时，按第 1/3/7 天排未来几次 ──
        // 判定"错过"用触发时刻（fireDate < 今天），不能用 overdue 数：
        // 第 1 天节点在 overdue==1（即今天）时仍未发送，用 d <= overdue 会把它漏掉。
        if (firstCharge.isBefore(now)) {
            // 整期静音（"本期不再提醒"把 mutedUntil 写成本期扣费日）：该期全部补发都不发
            val periodMuted = sub.mutedUntil?.let { it >= firstCharge } == true
            for (d in followUpDays) {
                if (periodMuted) break
                val fireDate = firstCharge.plusDays(d.toLong())
                if (fireDate.isBefore(now)) continue // 这个节点已经彻底错过（且没有未发送的记录）
                result += PlannedReminder(
                    subscriptionId = sub.id,
                    kind = ReminderKind.FOLLOW_UP,
                    offsetDays = d,
                    chargeDate = firstCharge,
                    fireAtMillis = fireAtMillis(fireDate, time),
                )
            }
            return result
        }

        // ── 正常路径：以 nextChargeDate 为第一笔，之后从 anchor 重算，保证月末不漂移 ──
        val windowEnd = now.plusDays(horizonDays)
        val charges = buildList {
            var cursor: LocalDate? = firstCharge
            var guard = 0
            while (cursor != null && !cursor.isAfter(windowEnd) && guard++ < 60) {
                add(cursor)
                cursor = CycleEngine.nextOccurrenceAfter(sub.anchorDate, sub.cycle, sub.customCycleDays, cursor)
            }
        }

        for (charge in charges) {
            for (offset in offsets) {
                val fireDate = charge.minusDays(offset.toLong())
                if (fireDate.isBefore(now)) continue
                if (mutedAt(sub, charge)) continue
                result += PlannedReminder(
                    subscriptionId = sub.id,
                    kind = if (offset == 0) ReminderKind.ONSET else ReminderKind.ADVANCE,
                    offsetDays = offset,
                    chargeDate = charge,
                    fireAtMillis = fireAtMillis(fireDate, time),
                )
            }
        }
        return result
    }

    /** 保存订阅时只注册到期当天这一条精确闹钟。 */
    fun onsetOf(sub: Subscription, settings: AppSettings = AppSettings()): PlannedReminder? {
        val next = sub.nextChargeDate ?: return null
        if (next.isBefore(clock.today())) return null
        return PlannedReminder(
            subscriptionId = sub.id,
            kind = ReminderKind.ONSET,
            offsetDays = 0,
            chargeDate = next,
            fireAtMillis = fireAtMillis(next, settings.reminderTime),
        )
    }

    private fun mutedAt(sub: Subscription, chargeDate: LocalDate): Boolean {
        val muted = sub.mutedUntil ?: return false
        return chargeDate <= muted
    }

    private fun fireAtMillis(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

fun BillingCycleType.label(): String = when (this) {
    BillingCycleType.WEEKLY -> "周付"
    BillingCycleType.BIWEEKLY -> "双周付"
    BillingCycleType.FOUR_WEEKLY -> "四周付"
    BillingCycleType.MONTHLY -> "月付"
    BillingCycleType.QUARTERLY -> "季付"
    BillingCycleType.SEMIANNUAL -> "半年付"
    BillingCycleType.YEARLY -> "年付"
    BillingCycleType.CUSTOM_DAYS -> "自定义"
    BillingCycleType.ONE_TIME -> "一次性"
}
