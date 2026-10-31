package com.subtrack.ui.state

import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.repository.PaymentRecord
import com.subtrack.domain.state.SubscriptionActions
import java.time.LocalDate

/**
 * 主界面的一次性提示（toast / 导入摘要）。用数据类而不是直接弹 Toast，
 * 是为了让提示文案能在 Compose 里以统一组件渲染、也便于测试。
 */
sealed interface UiMessage {
    data class Toast(val text: String) : UiMessage
    data class ImportSummary(
        val added: Int,
        val skipped: Int,
        val corrected: Int,
        val addedPayments: Int,
        val error: String? = null,
        /** CSV 导入的逐行错误（行号 + 原因），最多展示前若干条。 */
        val csvErrors: List<String> = emptyList(),
    ) : UiMessage

    /** AC-46：读取失败必须能重试，不能伪装成"数据为空"。 */
    data class LoadError(val reason: String) : UiMessage
}

/** 首页"全部订阅"的筛选态。 */
enum class ListFilter(val label: String) {
    ALL("全部"),
    ACTIVE("生效中"),
    PAUSED("已暂停"),
    ENDED("已结束"),
}

/**
 * 纯函数式的视图状态推导。放这里而不是写在 Composable 里，是为了能在单测里验证
 * "同一份数据在列表/首页/统计三处口径一致"（AC-20）。
 */
object DashboardState {

    /** AC-41：待确认数为 0 时要显示"全部已处理"，而不是藏起来。 */
    fun pendingLabel(pendingCount: Int): String =
        if (pendingCount == 0) "全部已处理" else "$pendingCount 项待确认"

    /** AC-43：近期到期最多 5 条，超出显示"查看全部"。 */
    const val UPCOMING_LIMIT = 5

    fun filter(subs: List<Subscription>, filter: ListFilter): List<Subscription> = when (filter) {
        ListFilter.ALL -> subs
        ListFilter.ACTIVE -> subs.filter { it.isForecastIncluded }
        ListFilter.PAUSED -> subs.filter { it.status == com.subtrack.domain.model.SubscriptionStatus.PAUSED }
        ListFilter.ENDED -> subs.filter {
            it.status == com.subtrack.domain.model.SubscriptionStatus.CANCELED ||
                it.status == com.subtrack.domain.model.SubscriptionStatus.EXPIRED
        }
    }
}

/** 设置页的状态与动作，抽出来让 SettingsScreen 变成纯展示。 */
data class SettingsUiState(
    val settings: AppSettings,
    val notificationsEnabled: Boolean,
    val exactAlarmAllowed: Boolean,
    val batteryOptimized: Boolean,
)

/** 一次操作后要落的库与要刷的提醒，集中成一个可测试的结构。 */
data class MutationPlan(
    val subscription: Subscription?,
    val payments: List<PaymentRecord>,
    val message: String?,
) {
    companion object {
        fun from(outcome: SubscriptionActions.Outcome, today: LocalDate): MutationPlan = MutationPlan(
            subscription = outcome.subscription,
            payments = outcome.payments,
            message = outcome.toast,
        )
    }
}
