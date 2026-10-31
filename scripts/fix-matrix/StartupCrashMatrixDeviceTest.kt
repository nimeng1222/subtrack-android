package com.subtrack

import androidx.test.platform.app.InstrumentationRegistry
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.sync.SyncProductService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * S2 real force-stop startup matrix (isolated; injected for the phase run): the host kills the process between the two
 * phases, so phase 2 runs through the REAL AppContainer startup chain (migration + interrupted-confirmation recovery).
 */
class StartupCrashMatrixDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val subId = "sub_startup_crash_0001"
    private val setupToken = com.subtrack.SyncE2EDeviceTest.setupTokenForMatrix()
    private val stateFile get() = File(context.filesDir, "startup-crash-state.json")
    private fun phase() = requireNotNull(InstrumentationRegistry.getArguments().getString("startupCrashPhase"))

    private suspend fun container(): AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        error("container not initialised")
    }

    private fun sub(name: String) = Subscription(
        id = subId, name = name, categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.of(2026, 9, 29), nextChargeDate = LocalDate.of(2026, 11, 1), priceMinor = 1000L,
    )

    @Test fun run_phase(): Unit = runBlocking {
        val app = container()
        val service: SyncProductService = app.syncService
        val db = SubTrackDatabase.get(context)
        when (phase()) {
            "prepare" -> {
                service.setEndpoint("http://127.0.0.1:18450")
                service.createVault(setupToken, "device-startup-crash")
                service.disable()
                val vault = requireNotNull(service.activeVault)
                app.repository.upsert(sub("CONFIRMED-ON-DISK"))
                val row = db.disabledEditDao().pendingRows(vault).first { it.entityId == subId }
                db.disabledEditDao().markConfirmed(row.seq, System.currentTimeMillis())   // interrupted before enqueue
                stateFile.writeText(vault)
                Unit
            }
            "verify" -> {
                // The process was killed by the host; this phase only observes the normal startup result.
                assertTrue("the startup readiness gate must be released", withTimeoutOrNull(15_000) {
                    app.awaitSyncReady(); true
                } == true)
                val preview = service.disabledEditPreview()
                assertEquals("confirmed-before-enqueue must be confirmable again after restart",
                    1, preview["subscription"] ?: 0)
                assertEquals("nothing may be uploaded by recovery", 0, service.status().pendingUploads)
                assertNull("no startup error expected", service.initializationError)
                Unit
            }
            else -> error("unknown phase")
        }
    }

    private suspend fun <T> withTimeoutOrNull(ms: Long, block: suspend () -> T): T? =
        kotlinx.coroutines.withTimeoutOrNull(ms) { block() }
}
