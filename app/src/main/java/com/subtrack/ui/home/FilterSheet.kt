package com.subtrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.core.money.Money
import com.subtrack.domain.stats.SubscriptionQuery
import com.subtrack.ui.Categories
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.SectionHeader
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SubTrackTheme

/**
 * 高级筛选面板（v1.1）：金额档位 + 分类多选 + 排序。
 *
 * 为什么用"金额档位"而不是让用户输 min/max：
 * 手机上输两个数字要弹键盘、要处理小数，成本远大于收益。
 * 5 个档位覆盖了"找便宜货"和"找出大额订阅"这两个真实诉求。
 */
@Composable
fun FilterSheet(
    criteria: SubscriptionQuery.Criteria,
    onChange: (SubscriptionQuery.Criteria) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(onClick = onDismiss),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(SubTrackTheme.colors.sheetBg)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // 内容可滚动：小屏上这些档位加起来会超一屏
            val scroll = androidx.compose.foundation.rememberScrollState()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "筛选",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "重置",
                    modifier = Modifier
                        .clickable {
                            onChange(
                                criteria.copy(minMinor = null, maxMinor = null, categoryIds = emptySet()),
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = TextStyle(fontSize = 14.sp, color = InkSecondary),
                )
                Text(
                    "完成",
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(start = 12.dp, top = 4.dp, end = 0.dp, bottom = 4.dp),
                    style = TextStyle(fontSize = 14.sp, color = Accent, fontWeight = FontWeight.Medium),
                )
            }

            Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(18.dp))
            SectionHeader("金额")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                SubscriptionQuery.PRICE_PRESETS.take(3).forEach { (label, min, max) ->
                    PriceChip(label, criteria.minMinor == min && criteria.maxMinor == max) {
                        onChange(criteria.copy(minMinor = min, maxMinor = max))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                SubscriptionQuery.PRICE_PRESETS.drop(3).forEach { (label, min, max) ->
                    PriceChip(label, criteria.minMinor == min && criteria.maxMinor == max) {
                        onChange(criteria.copy(minMinor = min, maxMinor = max))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader("分类")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Categories.all.take(5).forEach { cat ->
                    CategoryChip(cat.name, cat.id in criteria.categoryIds) {
                        val next = if (cat.id in criteria.categoryIds) {
                            criteria.categoryIds - cat.id
                        } else {
                            criteria.categoryIds + cat.id
                        }
                        onChange(criteria.copy(categoryIds = next))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Categories.all.drop(5).forEach { cat ->
                    CategoryChip(cat.name, cat.id in criteria.categoryIds) {
                        val next = if (cat.id in criteria.categoryIds) {
                            criteria.categoryIds - cat.id
                        } else {
                            criteria.categoryIds + cat.id
                        }
                        onChange(criteria.copy(categoryIds = next))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader("排序")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                SubscriptionQuery.SortBy.entries.take(2).forEach { s ->
                    SortChip(s.label, criteria.sortBy == s) { onChange(criteria.copy(sortBy = s)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                SubscriptionQuery.SortBy.entries.drop(2).forEach { s ->
                    SortChip(s.label, criteria.sortBy == s) { onChange(criteria.copy(sortBy = s)) }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().clickable {
                    onChange(criteria.copy(includeEnded = !criteria.includeEnded))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "包含已取消 / 已过期",
                    style = TextStyle(fontSize = 14.sp, color = Ink),
                    modifier = Modifier.weight(1f),
                )
                IosToggle(criteria.includeEnded)
            }
            Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun PriceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Accent else SegmentBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 12.sp,
                color = if (selected) Color.White else Ink,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Accent else SegmentBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 11.sp,
                color = if (selected) Color.White else Ink,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Accent else SegmentBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 12.sp,
                color = if (selected) Color.White else Ink,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun IosToggle(on: Boolean) {
    Box(
        Modifier
            .width(44.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (on) Accent else SegmentBg),
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .width(22.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White)
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart),
        )
    }
}

/** 筛选入口按钮：有高级条件时高亮并显示条数。 */
@Composable
fun FilterButton(criteria: SubscriptionQuery.Criteria, resultCount: Int, onClick: () -> Unit) {
    val active = criteria.hasAdvancedFilter
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Accent else SegmentBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (active) "已筛选 · $resultCount" else "筛选",
            style = TextStyle(
                fontSize = 13.sp,
                color = if (active) Color.White else InkSecondary,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}
