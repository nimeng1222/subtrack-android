package com.subtrack.ui.settings

enum class BatteryDestination { OPTIMIZATION_LIST, APP_DETAILS, SYSTEM_SETTINGS }
data class BatterySettingsResult(val destination: BatteryDestination?, val message: String)

/** OEMs can omit an action or reject it; every path yields instructions, including total failure. */
fun routeBatterySettings(tryOpen: (BatteryDestination) -> Unit): BatterySettingsResult {
    for (destination in BatteryDestination.entries) {
        val opened = runCatching { tryOpen(destination) }.isSuccess
        if (opened) return BatterySettingsResult(destination, when (destination) {
            BatteryDestination.OPTIMIZATION_LIST -> "在“所有应用”中找到“订阅台账”，选择“不优化”。厂商的省电设置可能不同。"
            BatteryDestination.APP_DETAILS -> "此设备未提供电池优化列表，请在应用详情中查看“电池”或“后台耗电管理”。"
            BatteryDestination.SYSTEM_SETTINGS -> "请在系统设置中搜索“电池优化”，找到“订阅台账”并允许后台提醒。"
        })
    }
    return BatterySettingsResult(null, "无法打开系统电池设置。请手动进入系统设置，搜索“电池优化”并找到“订阅台账”。")
}
