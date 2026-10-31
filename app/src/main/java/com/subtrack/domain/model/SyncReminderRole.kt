package com.subtrack.domain.model

/**
 * N4 reminder-device role semantics. Only ONE device should schedule reminders to avoid duplicate notifications.
 * A null/blank designated device means "this device reminds" (single-device default). Offline old primary devices cannot
 * learn of a role switch instantly — that boundary is intentional and documented, not a strict global-once guarantee.
 */
object SyncReminderRole {
    fun isReminderDevice(localDeviceId: String?, designatedDeviceId: String?): Boolean =
        designatedDeviceId.isNullOrBlank() || designatedDeviceId == localDeviceId

    fun roleLabel(localDeviceId: String?, designatedDeviceId: String?): String = when {
        designatedDeviceId.isNullOrBlank() -> "本机提醒（未指定专门设备）"
        designatedDeviceId == localDeviceId -> "本机为提醒设备"
        else -> "另一台设备负责提醒"
    }
}
