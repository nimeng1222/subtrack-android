package com.subtrack.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 小组件的 AppWidgetProvider。
 *
 * 刷新策略：数据一变就刷（在 AppContainer 里由订阅流驱动），
 * 另外在 [onUpdate] / [onAppWidgetOptionsChanged] 时也主动刷一次——用户刚拖到桌面时
 * 必须立刻看到正确数字，不能等下一次数据变更。
 *
 * 刻意**不**注册任何 WorkManager 定期任务：小组件读的是同一个库，
 * 而库的变化我们自己能感知，多一个后台任务只会多一处可能失配的地基。
 */
class SubTrackWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: androidx.glance.appwidget.GlanceAppWidget = SubTrackWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        refreshAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        refreshAll(context)
    }

    private fun refreshAll(context: Context) {
        val app = context.applicationContext ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                SubTrackWidget().updateAll(app)
            }
        }
    }
}
