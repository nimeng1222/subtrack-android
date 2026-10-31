package com.subtrack.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.core.money.Money
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.ui.Categories
import com.subtrack.ui.components.AmountText
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.InsetDivider
import kotlin.math.min
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary

@Composable
internal fun CategoryCard(stats: StatsEngine.Snapshot, total: Long) {
    GlassCard(corner = 24.dp, padding = PaddingValues(20.dp), alpha = 0.74f) {
        if (stats.categories.isEmpty()) {
            Text("暂无生效中的订阅", style = TextStyle(fontSize = 15.sp, color = InkSecondary))
            Spacer(Modifier.height(4.dp))
            Text("录入第一项订阅后，这里会显示分类占比。", style = TextStyle(fontSize = 12.sp, color = InkSecondary))
            return@GlassCard
        }

        // AC-38：只有一个分类时不画环形图（单扇形无意义），改成占比条
        if (stats.categories.size == 1) {
            SingleCategoryBar(stats.categories.first(), total)
            return@GlassCard
        }

        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 20.dp.toPx()
                val diameter = min(size.width, size.height) - stroke
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                drawArc(
                    color = Color.White.copy(alpha = 0.62f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(width = stroke),
                )
                var start = -90f
                stats.categories.forEach { slice ->
                    val sweep = 360f * slice.basisPoints(total) / 10_000f
                    drawArc(
                        color = Categories.of(slice.categoryId).color,
                        startAngle = start + 2f,
                        sweepAngle = (sweep - 4f).coerceAtLeast(1f),
                        useCenter = false, topLeft = topLeft, size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AmountText(Money.format(stats.forecastAnnualMinor), size = 20, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("年度合计", style = TextStyle(fontSize = 12.sp, color = InkSecondary))
            }
        }

        Spacer(Modifier.height(14.dp))
        // AC-37：金额 + 占比（一位小数）+ 条目数
        stats.categories.forEachIndexed { index, slice ->
            val category = Categories.of(slice.categoryId)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(category.color))
                Spacer(Modifier.width(10.dp))
                Text(category.name, style = TextStyle(fontSize = 14.sp, color = Ink), modifier = Modifier.weight(1f))
                Text(
                    "${slice.count} 项",
                    style = TextStyle(fontSize = 12.sp, color = InkSecondary),
                    modifier = Modifier.width(44.dp),
                )
                AmountText(Money.format(slice.annualMinor), size = 14, color = Ink)
                Spacer(Modifier.width(10.dp))
                Text(
                    "${"%.1f".format(slice.basisPoints(total) / 100.0)}%",
                    style = TextStyle(fontSize = 12.sp, color = InkSecondary),
                    modifier = Modifier.width(46.dp),
                )
            }
            if (index != stats.categories.lastIndex) InsetDivider(18)
        }
    }
}

@Composable
private fun SingleCategoryBar(slice: StatsEngine.CategorySlice, total: Long) {
    val category = Categories.of(slice.categoryId)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(category.color))
            Spacer(Modifier.width(10.dp))
            Text(category.name, style = TextStyle(fontSize = 15.sp, color = Ink), modifier = Modifier.weight(1f))
            AmountText(Money.format(slice.annualMinor), size = 17, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(category.color),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "占比 100.0% · ${slice.count} 项（只有一个分类时不画环形图）",
            style = TextStyle(fontSize = 12.sp, color = InkSecondary),
        )
    }
}
