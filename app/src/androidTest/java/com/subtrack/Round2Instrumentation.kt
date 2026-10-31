package com.subtrack

import android.app.Activity
import androidx.test.runner.MonitoringInstrumentation
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/** Device gate: scripts/device-ci.py checks this complete protocol, not adb's process exit code. */
class Round2Instrumentation : MonitoringInstrumentation() {
    private var fixture: String? = null
    private var composeSmokeOnly = false
    private var composeSettingsOnly = false
    private var syncConsistencyOnly = false
    private var disabledPublishOnly = false
    private var qOnly = false
    private var priceOnly = false
    private var settingsUiOnly = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); instance = this; fixture = arguments?.getString("fixture"); composeSmokeOnly = arguments?.getString("composeSmoke") == "1"; composeSettingsOnly = arguments?.getString("composeSettings") == "1"; syncConsistencyOnly = arguments?.getString("syncConsistency") == "1"; disabledPublishOnly = arguments?.getString("disabledPublish") != null; qOnly = arguments?.getString("qOnly") == "1"; priceOnly = arguments?.getString("priceOnly") == "1"; settingsUiOnly = arguments?.getString("settingsUi") == "1"; start() }
    override fun onStart() {
        fixture?.let { ReadinessFixture.run(this, it); return }
        val junit = JUnitCore()
        val failures = mutableMapOf<Description, String>()
        val skipped = mutableSetOf<Description>()
        val started = mutableMapOf<Description, Long>()
        fun emit(d: Description, outcome: String) {
            val record = JSONObject().put("class", d.className).put("method", d.methodName)
                .put("outcome", outcome).put("trace", failures[d].orEmpty())
                .put("seconds", (SystemClock.elapsedRealtime() - (started[d] ?: SystemClock.elapsedRealtime())) / 1000.0)
            sendStatus(if (outcome == "PASS") 0 else -2, Bundle().apply {
                putString("class", d.className); putString("test", d.methodName)
                putString("subtrack_test", record.toString())
                putString("stream", "$outcome ${d.className}#${d.methodName}\n")
            })
        }
        junit.addListener(object : RunListener() {
            override fun testStarted(description: Description) { started[description] = SystemClock.elapsedRealtime() }
            override fun testFailure(failure: Failure) { failures[failure.description] = failure.trace }
            override fun testAssumptionFailure(failure: Failure) { skipped += failure.description; failures[failure.description] = failure.trace }
            override fun testIgnored(description: Description) { skipped += description; emit(description, "SKIP") }
            override fun testFinished(description: Description) {
                emit(description, when { description in skipped -> "SKIP"; description in failures -> "FAIL"; else -> "PASS" })
            }
        })
        val suites = if (qOnly) arrayOf(DisabledPublishLifecycleDeviceTest::class.java, RotationRetryDeviceTest::class.java, PublishProtocolDeviceTest::class.java, StartupDeadlockDeviceTest::class.java, SchemaFixtureMigrationDeviceTest::class.java, NestedTransactionDeviceTest::class.java) else if (disabledPublishOnly) arrayOf(DisabledPublishDeviceTest::class.java) else if (composeSmokeOnly) arrayOf(ComposeRuleSmokeTest::class.java) else if (composeSettingsOnly) arrayOf(NetworkSettingsComposeTest::class.java) else if (settingsUiOnly) arrayOf(SettingsIaDeviceTest::class.java, SyncSettingsUiDeviceTest::class.java, NetworkSettingsComposeTest::class.java) else if (priceOnly) arrayOf(PriceRetrievalDeviceTest::class.java) else if (syncConsistencyOnly) arrayOf(
            SyncResolutionPropagationDeviceTest::class.java, SyncLedgerConsistencyDeviceTest::class.java,
        ) else arrayOf(
            Round2DeviceTest::class.java, Round3DeviceTest::class.java, ReadinessDeviceTest::class.java,
            NetworkDeviceTest::class.java, EStageRegressionTest::class.java, ComposeRuleSmokeTest::class.java,
            TemplateCatalogDeviceTest::class.java, SyncStorageDeviceTest::class.java, NetworkSettingsComposeTest::class.java,
            EditorValidationComposeTest::class.java, EditorDatePickerDeviceTest::class.java, SettingsIaDeviceTest::class.java, SyncSettingsUiDeviceTest::class.java, SyncWriteDeviceTest::class.java, SyncApplyDeviceTest::class.java,
            TemplateAdoptDeviceTest::class.java, TemplateCacheSameSourceDeviceTest::class.java, FxSettingsDeviceTest::class.java,
            SyncBatchWriteDeviceTest::class.java, TemplateLateSourceDeviceTest::class.java, PriceRetrievalDeviceTest::class.java,
            SyncStatusDeviceTest::class.java, SyncConflictResolveDeviceTest::class.java,
            SyncPaymentIdempotencyDeviceTest::class.java, SyncE2EDeviceTest::class.java,
            ApkSignerPolicyDeviceTest::class.java, SyncKeyRotationDeviceTest::class.java,
            ReminderRoleGateDeviceTest::class.java, SyncConflictPanelDeviceTest::class.java,
            SyncSettingsPanelDeviceTest::class.java, UpdateInstallerGuardDeviceTest::class.java,
            SyncResolutionPropagationDeviceTest::class.java, SyncLedgerConsistencyDeviceTest::class.java,
            SyncResolutionCausalityDeviceTest::class.java, SyncRecoveryBundleDeviceTest::class.java, SyncKeyOverwriteDeviceTest::class.java, SyncImportRecoveryDeviceTest::class.java, SyncSessionIsolationDeviceTest::class.java, SyncSessionAdmissionDeviceTest::class.java, SyncLifecycleDeviceTest::class.java, DisabledPublishDeviceTest::class.java, ReminderSweepDeviceTest::class.java,
            SyncAuditGapsDeviceTest::class.java, ReminderResolveReconcileDeviceTest::class.java, DisabledPublishLifecycleDeviceTest::class.java, RotationRetryDeviceTest::class.java,
            PublishProtocolDeviceTest::class.java, StartupDeadlockDeviceTest::class.java, SchemaFixtureMigrationDeviceTest::class.java, NestedTransactionDeviceTest::class.java)
        val result = junit.run(*suites)
        val summary = JSONObject().put("protocol", 1).put("completed", true).put("tests", result.runCount)
            .put("failures", result.failureCount).put("ignored", result.ignoreCount + skipped.count { it in started })
        finish(if (result.wasSuccessful() && skipped.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("subtrack_result", summary.toString())
            putString("stream", "Tests: ${result.runCount}, failures: ${result.failureCount}, ignored: ${summary.getInt("ignored")}\n")
        })
    }
    companion object { lateinit var instance: Round2Instrumentation }
}
