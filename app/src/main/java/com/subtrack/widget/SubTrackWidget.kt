package com.subtrack.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.subtrack.MainActivity
import com.subtrack.SubTrackApp
import com.subtrack.core.money.Money
import com.subtrack.domain.stats.Annualizer
import com.subtrack.domain.stats.MonthlyCashflow
import com.subtrack.domain.model.Subscription
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.action.clickable
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * 4x2 桌面小组件：左「月均 + 年度」，右「最近 3 笔到期」。
 *
 * 关键决策（对齐主文档 §7.4）：**不做快照表**。
 * 快照会制造第二份事实来源——一旦与库不一致，桌面显示 1284、应用内显示 1290，
 * 正是最伤信任的那类 bug；而且小组件的定位是"通知失效时仍能看到"，
 * 若它自己也依赖每日任务刷新，等于把兜底层建在同一个不可靠地基上。
 * Glance 的 provideGlance 本身是 suspend，直接查库即可，零写侧任务。
 */
class SubTrackWidget : GlanceAppWidget() {

    /**
     * 尺寸策略：不声明 sizeMode，用默认的 Single。
     *
     * 试过 SizeMode.Responsive 声明 2x2/4x2 两个档位，实测会让桌面渲染成空白占位
     * （SessionWorker 成功但 RemoteViews 为空）——Responsive 要求每个声明尺寸都
     * 能被内容完整填充，而我们的布局在 110dp 宽下会溢出，Glance 就放弃了。
     *
     * 改为单套自适应布局 + widget_info 里的 resizeMode，用户拖边角就能在
     * 2x2 ↔ 4x2 之间切换，内容按可用宽度自然伸缩（maxLines 截断，不会崩）。
     */
    private companion object {
        /** 2x2 的尺寸常量保留给需要在布局里判断时用（当前未用到）。 */
        val SMALL_SQUARE = DpSize(110.dp, 110.dp)

        /** 4x2 ≈ 250×110dp。 */
        val WIDE_4X2 = DpSize(250.dp, 110.dp)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as? SubTrackApp)?.container
        if (container == null) return
        val inputs = container.widgetInputs
        // A storage/settings failure cannot leave Glance awaiting a default-less flow indefinitely.
        val initial = try { kotlinx.coroutines.withTimeout(10000) { inputs.first() } }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                provideContent {
                    androidx.glance.text.Text("暂时无法读取订阅，请打开应用重试",
                        modifier = androidx.glance.GlanceModifier.clickable(
                            androidx.glance.appwidget.action.actionStartActivity(Intent(context, MainActivity::class.java))))
                }
                return
            }
        provideContent {
            // A running Glance session must also observe changes; a pair of plain snapshots can stay stale.
            val input by inputs.collectAsState(initial)
            val (monthly, annual) = input.amounts()
            val today = container.clock.today()
            val upcoming = input.subscriptions.filter { it.isForecastIncluded }
                .mapNotNull { sub -> sub.nextChargeDate?.let { sub to it } }
                .filter { (_, day) -> !day.isBefore(today) }.sortedBy { (_, day) -> day }.take(3)
            WidgetContent(context, monthly, annual, upcoming, today)
        }
    }

    @Composable
    private fun WidgetContent(
        context: Context,
        monthly: Long,
        annual: Long,
        upcoming: List<Pair<Subscription, LocalDate>>,
        today: LocalDate,
    ) {
        val openApp = androidx.glance.appwidget.action.actionStartActivity(
            Intent(context, MainActivity::class.java),
        )

        GlanceTheme {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(Color(0xFFF7F9FC))
                    .clickable(openApp)
                    .padding(14.dp),
                verticalAlignment = Alignment.Vertical.Top,
            ) {
                // 左：金额
                Column(modifier = GlanceModifier.width(104.dp)) {
                    Text(
                        "预计月均",
                        style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color(0xFF86868B))),
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        Money.format(monthly),
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = ColorProvider(Color(0xFF0B0F14)),
                        ),
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        "年度 ${Money.format(annual)}",
                        maxLines = 1,
                        style = TextStyle(fontSize = 10.sp, color = ColorProvider(Color(0xFF86868B))),
                    )
                }

                Spacer(GlanceModifier.width(8.dp))

                // 右：最近 3 笔到期
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        "近期到期",
                        style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color(0xFF86868B))),
                    )
                    Spacer(GlanceModifier.height(5.dp))
                    if (upcoming.isEmpty()) {
                        Text(
                            "暂无待扣",
                            style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color(0xFF86868B))),
                        )
                    } else {
                        upcoming.forEach { (sub, date) ->
                            Row(
                                modifier = GlanceModifier.fillMaxWidth().height(20.dp),
                                verticalAlignment = Alignment.Vertical.CenterVertically,
                            ) {
                                Text(
                                    sub.name,
                                    maxLines = 1,
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        color = ColorProvider(Color(0xFF0B0F14)),
                                    ),
                                    modifier = GlanceModifier.defaultWeight(),
                                )
                                Text(
                                    shortDate(date, today),
                                    style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color(0xFF86868B))),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 7 天内显示相对日，更远显示 M月d日 —— 与列表行口径一致。 */
    private fun shortDate(date: LocalDate, today: LocalDate): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        return when {
            days == 0L -> "今天"
            days == 1L -> "明天"
            days in 2..7 -> "${days}天后"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
    }
}
