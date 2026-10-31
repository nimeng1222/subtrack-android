package com.subtrack.ui.settings

import com.subtrack.domain.model.NetworkSettings
import com.subtrack.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId

/**
 * 设置首页的状态摘要必须来自真实设置，并且把"未开启 / 未配置 / 连接失败 / 有待处理冲突"
 * 当成四个不同的事实。这些断言锁住的就是这件事——任何一个塌回"未启用"，测试就会红。
 */
class SettingsSummariesTest {

    private fun millisAt(y: Int, m: Int, d: Int, h: Int = 10): Long =
        java.time.LocalDateTime.of(y, m, d, h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val now = millisAt(2026, 10, 30)

    @Test fun reminder_line_reports_time_and_missing_permission() {
        val time = LocalTime.of(9, 0)
        assertEquals("每天 09:00", SettingsSummaries.reminderLine(time, listOf(3, 0), notificationsEnabled = true))
        assertEquals(
            "每天 09:00 · 通知未开启",
            SettingsSummaries.reminderLine(time, listOf(3, 0), notificationsEnabled = false),
        )
    }

    @Test fun reminder_line_distinguishes_disabled_schedule() {
        assertEquals(
            "提醒已关闭 · 21:05",
            SettingsSummaries.reminderLine(LocalTime.of(21, 5), emptyList(), notificationsEnabled = true),
        )
    }

    @Test fun budget_line_separates_unset_from_set() {
        assertEquals("月度预算未设置", SettingsSummaries.budgetLine(null))
        assertTrue(SettingsSummaries.budgetLine(30_000L).startsWith("月度预算 "))
        assertFalse(SettingsSummaries.budgetLine(30_000L).contains("未设置"))
    }

    @Test fun currency_line_covers_every_state() {
        assertEquals("多币种未开启", SettingsSummaries.currencyLine(false, 2, true, now - 1000, now))
        assertEquals(
            "多币种已开启 · 汇率未设置",
            SettingsSummaries.currencyLine(true, 0, onlineEnabled = false, fetchedAtMillis = 0L, nowMillis = now),
        )
        assertEquals(
            "多币种已开启 · 汇率未获取",
            SettingsSummaries.currencyLine(true, 0, onlineEnabled = true, fetchedAtMillis = 0L, nowMillis = now),
        )
        assertEquals(
            "多币种已开启 · 手动汇率 2 种",
            SettingsSummaries.currencyLine(true, 2, onlineEnabled = false, fetchedAtMillis = 0L, nowMillis = now),
        )
        assertEquals(
            "多币种已开启 · 今日更新",
            SettingsSummaries.currencyLine(true, 0, onlineEnabled = true, fetchedAtMillis = now - 3_600_000, nowMillis = now),
        )
    }

    @Test fun relative_labels_are_human_readable() {
        assertEquals("今日更新", SettingsSummaries.relativeDayLabel(now - 1_000, now))
        assertEquals("昨日更新", SettingsSummaries.relativeDayLabel(millisAt(2026, 10, 29), now))
        assertEquals("3 天前更新", SettingsSummaries.relativeDayLabel(millisAt(2026, 10, 27), now))
        assertEquals("未获取", SettingsSummaries.relativeDayLabel(0L, now))
    }

    @Test fun stale_uses_the_same_48_hour_rule_as_stats() {
        assertFalse(SettingsSummaries.fxStale(now - 47 * 3_600_000L, now))
        assertTrue(SettingsSummaries.fxStale(now - 49 * 3_600_000L, now))
        assertFalse(SettingsSummaries.fxStale(0L, now))
    }

    @Test fun theme_line_matches_each_mode() {
        assertEquals("跟随系统", SettingsSummaries.themeLine(ThemeMode.SYSTEM))
        assertEquals("浅色", SettingsSummaries.themeLine(ThemeMode.LIGHT))
        assertEquals("深色", SettingsSummaries.themeLine(ThemeMode.DARK))
    }

    @Test fun sync_line_never_merges_conflict_failure_and_off() {
        assertEquals("仅本机 · 未配置", SettingsSummaries.syncLine(false, 0, 0, null, configured = false))
        assertEquals("已停用 · 仅本机", SettingsSummaries.syncLine(false, 0, 0, null, configured = true))
        assertEquals("连接失败 · boom", SettingsSummaries.syncLine(true, 0, 0, "boom", configured = true))
        assertEquals("已连接 · 有待处理冲突 2", SettingsSummaries.syncLine(true, 0, 2, null, configured = true))
        assertEquals("已连接 · 待上传 5", SettingsSummaries.syncLine(true, 5, 0, null, configured = true))
        assertEquals("已连接", SettingsSummaries.syncLine(true, 0, 0, null, configured = true))
    }

    @Test fun network_line_only_tracks_the_ai_connection() {
        // 在线模板已移除：这一行只反映 AI 模型连接是否可用。
        assertEquals("未配置", SettingsSummaries.networkLine(NetworkSettings()))
        assertEquals("AI 未填写服务地址", SettingsSummaries.networkLine(NetworkSettings(aiEnabled = true)))
        assertEquals(
            "AI 已启用",
            SettingsSummaries.networkLine(NetworkSettings(aiEnabled = true, aiBaseUrl = "https://example.com/v1")),
        )
    }

    @Test fun security_and_about_lines_are_plain_facts() {
        assertEquals("应用锁未开启", SettingsSummaries.securityLine(false))
        assertEquals("应用锁已开启", SettingsSummaries.securityLine(true))
        assertEquals("v1.9.0", SettingsSummaries.aboutLine("1.9.0"))
    }

    @Test fun offsets_label_keeps_the_existing_two_slot_semantics() {
        assertEquals("提前 3 天 + 当天", offsetsLabel(listOf(3, 0)))
        assertEquals("提前 5 天", offsetsLabel(listOf(5)))
        assertEquals("到期当天", offsetsLabel(listOf(0)))
        assertEquals("已关闭", offsetsLabel(emptyList()))
    }

    @Test fun online_rate_is_shown_in_yuan_not_nano_minor() {
        // 1 USD = 7.1234 元：1e9 定点是 7_123_400_000，以前被当成"分"打印成 71234000.00
        assertEquals("7.1234", SettingsSummaries.preciseRateText(7_123_400_000L))
        assertEquals("0.0478", SettingsSummaries.preciseRateText(47_800_000L))
        assertEquals("—", SettingsSummaries.preciseRateText(0L))
    }

    @Test fun manual_rate_wins_and_online_is_labelled() {
        assertEquals("手动 7.20", SettingsSummaries.rateRowValue(720L, 7_123_400_000L))
        assertEquals("在线参考 7.1234", SettingsSummaries.rateRowValue(null, 7_123_400_000L))
        assertEquals("未设置", SettingsSummaries.rateRowValue(null, null))
    }
}
