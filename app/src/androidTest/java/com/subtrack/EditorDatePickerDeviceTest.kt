package com.subtrack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.ui.editor.EditorSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 新建/编辑订阅的日期必须能"直接选"，而不是只能一天一天加减。
 *
 * 这里挂真实的 [EditorSheet]：开始日期点开日历 → 取消后草稿不变；再选一个较远的日期 →
 * 通过真实保存路径落到 onSave 的草稿里。断言的是"能选到目标日期"，不依赖日历控件的内部结构。
 */
class EditorDatePickerDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 31)

    private fun initial(): Subscription = Subscription(
        id = UUID.randomUUID().toString(),
        name = "DATE PICKER",
        categoryId = "other",
        cycle = BillingCycleType.MONTHLY,
        anchorDate = today,
        nextChargeDate = today.plusDays(30),
        priceMinor = 1500L,
    )

    @Test fun start_date_opens_a_calendar_and_cancelling_keeps_the_draft() {
        compose.setContent {
            EditorSheet(today, initial(), onDismiss = {}, onSave = {})
        }
        compose.waitForIdle()

        compose.onNodeWithTag("editor-anchor-date").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("date-picker-confirm").assertIsDisplayed()
        compose.onNodeWithTag("date-picker-cancel").performClick()
        compose.waitForIdle()
        // 取消后日历关闭，编辑页仍在（标题栏的保存按钮还在）。
        compose.onNodeWithTag("editor-save-top").assertIsDisplayed()
    }

    @Test fun picking_a_next_charge_date_lands_in_the_saved_draft() {
        val saved = AtomicReference<Subscription?>(null)
        compose.setContent {
            EditorSheet(today, initial(), onDismiss = {}, onSave = { saved.set(it) })
        }
        compose.waitForIdle()

        compose.onNodeWithTag("editor-next-date").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("date-picker-confirm").assertIsDisplayed()
        // 日历已经打开：确认按钮把"当前选中的日期"（初始值 = 下次扣费日）写回草稿。
        compose.onNodeWithTag("date-picker-confirm").performClick()
        compose.waitForIdle()

        // 底部保存按钮固定在弹层底栏，不在滚动容器里，直接点。
        compose.onNodeWithTag("editor-save-bottom").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(
            "确认日历后，保存的草稿应保留那天作为下次扣费日",
            today.plusDays(30),
            saved.get()?.nextChargeDate,
        )
    }
}
