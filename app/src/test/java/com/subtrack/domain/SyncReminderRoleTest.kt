package com.subtrack.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncReminderRoleTest {
    @Test fun only_designated_device_reminds_default_single_device() {
        assertTrue(SyncReminderRole.isReminderDevice(null, null))
        assertTrue(SyncReminderRole.isReminderDevice("A", null))
        assertTrue(SyncReminderRole.isReminderDevice("A", ""))
        assertTrue(SyncReminderRole.isReminderDevice("A", "A"))
        assertFalse(SyncReminderRole.isReminderDevice("A", "B"))
        assertFalse(SyncReminderRole.isReminderDevice(null, "B"))
    }

    @Test fun role_labels_are_explainable() {
        assertEquals("本机提醒（未指定专门设备）", SyncReminderRole.roleLabel("A", null))
        assertEquals("本机为提醒设备", SyncReminderRole.roleLabel("A", "A"))
        assertEquals("另一台设备负责提醒", SyncReminderRole.roleLabel("A", "B"))
    }
}
