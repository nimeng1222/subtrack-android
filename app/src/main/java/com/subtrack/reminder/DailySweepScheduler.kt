package com.subtrack.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 每日一次的提醒扫描。用不精确闹钟，避免为"提前 N 天"再要一个精确权限。
 *
 * 扫描时刻来自设置（裁决 D8）：用户把提醒时刻设为 18:00，扫描就该在 18:00 跑——
 * 固定 09:00 会让当天 09:00 之后的提前提醒拖到次日才被扫到。
 * 设置变更后由 SubTrackApp 用新的 reminderTime 重新调 [schedule]。
 */
object DailySweepScheduler {
    private const val REQUEST_CODE = 42

    fun schedule(context: Context, reminderTime: LocalTime = LocalTime.of(9, 0)) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        var next = LocalDate.now().atTime(reminderTime).atZone(zone).toInstant().toEpochMilli()
        if (next <= now) {
            next = LocalDate.now().plusDays(1).atTime(reminderTime).atZone(zone).toInstant().toEpochMilli()
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DailySweepReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }
}
