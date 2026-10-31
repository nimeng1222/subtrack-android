package com.subtrack

import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.sync.SyncKeyStore
import com.subtrack.sync.SyncProductService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Q5: an immediately served epoch that already has a historical key must be skipped, not rate-limited. */
class RotationRetryDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext

    private suspend fun awaitContainer(): AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            delay(50)
        }
        error("container not initialised")
    }

    @Test fun historical_key_at_allocated_epoch_is_skipped_and_rotation_completes() = runBlocking {
        val app = awaitContainer()
        val service: SyncProductService = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(com.subtrack.SyncE2EDeviceTest.setupTokenForMatrix(), "device-rotate-q5")
        val vault = requireNotNull(service.activeVault)
        val store = SyncKeyStore(context)
        // A historical epoch-2 key exists locally; the server (immediate responses) will allocate 2 first, then 3.
        val historical = com.subtrack.sync.SyncCrypto.generateRecoveryKey()
        assertTrue(service.storeKeyChecked(vault, 2, historical))
        val (epoch, bundle) = service.rotateKey()
        assertTrue("rotation must skip the historical epoch and finish above it: $epoch", epoch >= 3)
        assertArrayEquals("the historical key must stay byte-identical",
            com.subtrack.sync.SyncCrypto.parseRecoveryKey(historical), store.load(vault, 2))
        assertNotNull("the newly allocated epoch must have a key", store.load(vault, epoch))
        assertNotEquals("the new key must differ from the historical one",
            com.subtrack.sync.SyncCrypto.parseRecoveryKey(historical).toList(), store.load(vault, epoch)!!.toList())
        assertEquals(epoch, com.subtrack.sync.SyncRecoveryBundle.parse(bundle).epoch)
        assertTrue(service.status().keyEpoch!! >= epoch)
        assertTrue(SubTrackDatabase.get(context).syncDao().state(vault)!!.keyEpoch >= epoch)
    }
}
