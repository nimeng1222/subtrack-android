package com.subtrack.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.core.money.Money
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.state.SubscriptionDisplayState
import com.subtrack.ui.Categories
import com.subtrack.ui.components.AmountText
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.ChevronIcon
import com.subtrack.ui.components.InsetDivider
import java.time.LocalDate
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Warning

/** 订阅列表行。分隔线左缩进对齐文字，不通栏。 */
@Composable
internal fun Rows(
    list: List<Subscription>,
    today: LocalDate,
    onOpen: (Subscription) -> Unit,
) {
    Column {
        list.forEachIndexed { index, sub ->
            SubscriptionRow(sub, null, sub.nextChargeDate, today, onOpen)
            if (index != list.lastIndex) InsetDivider(56)
        }
    }
}

@Composable
internal fun SubscriptionRow(
    sub: Subscription,
    state: SubscriptionDisplayState?,
    next: LocalDate?,
    today: LocalDate,
    onOpen: (Subscription) -> Unit,
) {
    val category = Categories.of(sub.categoryId)
    val pending = state is SubscriptionDisplayState.PendingConfirm
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(sub) }
            .padding(start = 12.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(category.iconRes, category.color, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                sub.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 17.sp, lineHeight = 21.sp, color = Ink),
            )
            Text(
                "${cycleLabel(sub.cycle)} · ${relativeLabel(next, today)}",
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = if (pending) Warning else InkSecondary),
            )
        }
        Spacer(Modifier.width(8.dp))
        AmountText(Money.format(sub.priceMinor), size = 17, color = Ink)
        Spacer(Modifier.width(7.dp))
        ChevronIcon()
    }
}
