package com.subtrack.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日期选择器的两个易错点：
 * ① UTC 毫秒与 LocalDate 的换算不能差一天（东八区用本地时区换算会把 5 号变成 4 号）；
 * ② "开始日期不能晚于今天"必须体现在可选范围里，而不是让用户选完再被保存校验拦下。
 */
class EditorDatePickerTest {

    private val today = LocalDate.of(2026, 10, 31)

    @Test fun utc_conversion_is_stable_for_any_local_time_zone() {
        val date = LocalDate.of(2026, 10, 31)
        val millis = editorDateToUtcMillis(date)
        assertEquals(date, utcMillisToEditorDate(millis))
        // 与本地时区无关：同一日期在任何时区都应换算成同一个 UTC 零点。
        assertEquals(0L, java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).hour.toLong())
        for (offset in listOf("Asia/Shanghai", "UTC", "America/Los_Angeles", "Pacific/Kiritimati")) {
            val original = java.util.TimeZone.getDefault()
            try {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(offset))
                assertEquals("offset=$offset", date, utcMillisToEditorDate(editorDateToUtcMillis(date)))
            } finally {
                java.util.TimeZone.setDefault(original)
            }
        }
    }

    @Test fun start_date_cannot_be_in_the_future() {
        assertTrue(isEditorDateSelectable(editorDateToUtcMillis(today), today, allowFuture = false))
        assertTrue(isEditorDateSelectable(editorDateToUtcMillis(today.minusYears(3)), today, allowFuture = false))
        assertFalse(isEditorDateSelectable(editorDateToUtcMillis(today.plusDays(1)), today, allowFuture = false))
    }

    @Test fun next_charge_and_trial_end_allow_future_dates() {
        assertTrue(isEditorDateSelectable(editorDateToUtcMillis(today.plusDays(90)), today, allowFuture = true))
        assertTrue(isEditorDateSelectable(editorDateToUtcMillis(today), today, allowFuture = true))
    }
}
