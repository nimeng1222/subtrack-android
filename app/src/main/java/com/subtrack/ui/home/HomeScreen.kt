package com.subtrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.state.DisplayStateResolver
import com.subtrack.domain.state.SubscriptionDisplayState
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.ui.components.AmbientBackground
import com.subtrack.ui.components.BottomFade
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.state.DashboardState
import com.subtrack.domain.stats.SubscriptionQuery
import com.subtrack.ui.state.ListFilter
import com.subtrack.ui.state.LoadState
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SubTrackTheme

/**
 * 首页（P0-5）。结构按 AC-41~AC-47：
 * 首屏四块（本月将扣 / 预计月均 / 年度 / 待确认）→ 待确认可处理 → 近期到期（最多 5 条）→ 全部订阅（可筛选）。
 *
 * 待确认不是"提醒一下"就完事：AC-42 要求它优先于金额展示，AC-19 要求每条都能就地处理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    subscriptions: List<Subscription>,
    stats: StatsEngine.Snapshot,
    today: LocalDate,
    criteria: SubscriptionQuery.Criteria,
    onCriteriaChange: (SubscriptionQuery.Criteria) -> Unit,
    onAdd: () -> Unit,
    onOpen: (Subscription) -> Unit,
    onOpenReminders: () -> Unit,
    onSelectTab: (Int) -> Unit,
    notificationsEnabled: Boolean,
    onEnableNotifications: () -> Unit,
    onPullRefresh: () -> Unit,
    loadState: LoadState,
    onRetry: () -> Unit,
    clipboardDraft: com.subtrack.domain.parse.PaymentTextParser.Parsed?,
    onUseClipboardDraft: (com.subtrack.domain.parse.PaymentTextParser.Parsed) -> Unit,
    onDismissClipboardDraft: () -> Unit,
    onConfirmPending: (Subscription) -> Unit,
    onCancelPending: (Subscription) -> Unit,
    onSnoozePending: (Subscription) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var expandedUpcoming by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var quickFilter by remember { mutableStateOf(ListFilter.ALL) }

    val states = remember(subscriptions, today) {
        subscriptions.associateWith { DisplayStateResolver.resolve(it, today, hasPaymentOnDueDate = false) }
    }
    val effectiveCriteria = remember(criteria, query) { criteria.copy(keyword = query) }
    val filtered = remember(subscriptions, effectiveCriteria, quickFilter) {
        val base = SubscriptionQuery.apply(subscriptions, effectiveCriteria)
        when (quickFilter) {
            ListFilter.ALL -> base
            ListFilter.ACTIVE -> base.filter { it.isForecastIncluded }
            ListFilter.PAUSED -> base.filter {
                it.status == com.subtrack.domain.model.SubscriptionStatus.PAUSED
            }
            ListFilter.ENDED -> base.filter {
                it.status == com.subtrack.domain.model.SubscriptionStatus.CANCELED ||
                    it.status == com.subtrack.domain.model.SubscriptionStatus.EXPIRED
            }
        }
    }
    // 待确认**不受筛选影响**：它是"待处理事项"，不是浏览列表的一部分。
    // 如果跟着筛选走，用户筛了个分类后待确认卡会消失，但顶部汇总仍显示"1 项待确认"，
    // 两边对不上，反而让人以为数据丢了。
    val pending = subscriptions.filter { states[it] is SubscriptionDisplayState.PendingConfirm }
    val upcomingAll = filtered
        .filter { states[it] == SubscriptionDisplayState.Active || states[it] == SubscriptionDisplayState.Trial }
        .filter { sub ->
            val next = sub.nextChargeDate ?: return@filter false
            !next.isBefore(today) && ChronoUnit.DAYS.between(today, next) <= 7
        }
        .sortedBy { it.nextChargeDate }
    val upcoming = if (expandedUpcoming) upcomingAll else upcomingAll.take(DashboardState.UPCOMING_LIMIT)
    val rest = filtered.filter { it !in upcomingAll && it !in pending }

    AmbientBackground {
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = onPullRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 150.dp),
                ) {
                    item { TopActions(onBell = onOpenReminders, onAdd = onAdd) }
                    item { Spacer(Modifier.height(6.dp)); Title("订阅") }
                    item { Spacer(Modifier.height(12.dp)); SearchField(query) { query = it } }

                    // AC-46：读取失败/加载中都不能渲染成"数据为空"。
                    // 失败时给可重试的错误卡；加载中给骨架，绝不先显示 ¥0.00 再跳变。
                    if (loadState is LoadState.Failed) {
                        item { Spacer(Modifier.height(16.dp)); ErrorCard(loadState.reason, onRetry) }
                    }
                    if (loadState is LoadState.Loading) {
                        item { Spacer(Modifier.height(16.dp)); LoadingCard() }
                    }

                    // 剪贴板识别提示：只在真的解析出金额时出现
                    clipboardDraft?.let { draft ->
                        item {
                            Spacer(Modifier.height(14.dp))
                            com.subtrack.ui.clipboard.ClipboardHintCard(
                                parsed = draft,
                                onUse = { onUseClipboardDraft(draft) },
                                onDismiss = onDismissClipboardDraft,
                            )
                        }
                    }

                    if (!notificationsEnabled) {
                        item { Spacer(Modifier.height(14.dp)); PermissionBanner(onEnableNotifications) }
                    }

                    // AC-42：待确认优先于金额展示
                    if (pending.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(14.dp))
                            PendingSection(
                                pending = pending,
                                states = states,
                                onOpen = onOpen,
                                onConfirm = onConfirmPending,
                                onCancel = onCancelPending,
                                onSnooze = onSnoozePending,
                            )
                        }
                    }

                    // 只有真正读到数据（Ready 且非空）才渲染金额，否则会是误导性的 ¥0.00
                    if (loadState is LoadState.Ready && subscriptions.isEmpty()) {
                        item { Spacer(Modifier.height(16.dp)); EmptyState(onAdd) }
                    } else if (loadState is LoadState.Ready) {
                        item { Spacer(Modifier.height(14.dp)); HeroCard(stats, today) }
                        item {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ListFilter.entries.forEach { f ->
                                    QuickFilterChip(
                                        f.label,
                                        quickFilter == f,
                                        Modifier.padding(end = 8.dp),
                                    ) { quickFilter = f }
                                }
                                Spacer(Modifier.weight(1f))
                                FilterButton(effectiveCriteria, filtered.size) { showFilter = true }
                            }
                        }
                    }

                    if (upcoming.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(24.dp))
                            SectionHeaderWithAction(
                                title = "近期到期",
                                action = if (upcomingAll.size > DashboardState.UPCOMING_LIMIT) {
                                    if (expandedUpcoming) "收起" else "查看全部 ${upcomingAll.size}"
                                } else null,
                                onAction = { expandedUpcoming = !expandedUpcoming },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        item { GlassCard(padding = PaddingValues(0.dp)) { Rows(upcoming, today, onOpen) } }
                    }

                    // 筛选后没有结果时给明确说明，否则用户会以为数据丢了
                    if (filtered.isEmpty() && subscriptions.isNotEmpty() && loadState is LoadState.Ready) {
                        item {
                            Spacer(Modifier.height(20.dp))
                            NoMatchCard(
                                hasAdvanced = effectiveCriteria.hasAdvancedFilter || query.isNotBlank(),
                                onReset = {
                                    onCriteriaChange(com.subtrack.domain.stats.SubscriptionQuery.Criteria())
                                    query = ""
                                    quickFilter = ListFilter.ALL
                                },
                            )
                        }
                    }

                    if (rest.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(24.dp))
                            SectionHeaderWithAction("全部订阅 · ${filtered.size}", null) {}
                            Spacer(Modifier.height(10.dp))
                        }
                        item { GlassCard(padding = PaddingValues(0.dp)) { Rows(rest, today, onOpen) } }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }

            BottomFade(Modifier.align(Alignment.BottomCenter))
            FloatingTabBar(
                selected = 0,
                onSelect = onSelectTab,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
            )
            // 筛选面板是模态层，必须画在 Tab 之上，否则底部选项被 Tab 吃掉
            if (showFilter) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    FilterSheet(
                        criteria = criteria,
                        onChange = onCriteriaChange,
                        onDismiss = { showFilter = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderWithAction(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = TextStyle(fontSize = 13.sp, color = InkSecondary, fontWeight = FontWeight.Medium))
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                modifier = Modifier.clickable(onClick = onAction),
                style = TextStyle(fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Medium),
            )
        }
    }
}
