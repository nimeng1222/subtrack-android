package com.subtrack.settings

import com.subtrack.data.settings.*
import com.subtrack.domain.model.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class LoadedSettingsTest {
    @Test fun cold_start_and_background_consumers_wait_for_the_same_real_value() = runTest {
        val settings = LoadedSettings()
        var calls = 0
        val consumers = List(4) { async { settings.await().also { calls++ } } }
        runCurrent()
        assertEquals(0, calls)
        assertFalse(settings.state.value.permitsContent(false))
        val persisted = AppSettings(appLockEnabled = true, reminderTime = LocalTime.of(18, 0))
        settings.publish(persisted)
        assertTrue(consumers.awaitAll().all { it == persisted })
        assertEquals(4, calls)
        assertFalse(settings.state.value.permitsContent(false))
        assertTrue(settings.state.value.permitsContent(true))
        // Activity recreation attaches to the already loaded state, never to an unlocked default.
        assertEquals(SettingsState.Loaded(persisted), settings.state.value)
        assertFalse(settings.state.value.permitsContent(false))
    }
}
