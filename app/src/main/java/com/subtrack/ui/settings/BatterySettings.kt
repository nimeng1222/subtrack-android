package com.subtrack.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

fun batterySettingsIntent(context: Context, destination: BatteryDestination): Intent =
    when (destination) {
        BatteryDestination.OPTIMIZATION_LIST -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        BatteryDestination.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        BatteryDestination.SYSTEM_SETTINGS -> Intent(Settings.ACTION_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun openBatterySettings(context: Context, launch: (Intent) -> Unit = { context.startActivity(it) }): BatterySettingsResult {
    val result = routeBatterySettings { destination -> launch(batterySettingsIntent(context, destination)) }
    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
    return result
}
