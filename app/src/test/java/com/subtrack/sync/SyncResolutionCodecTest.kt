package com.subtrack.sync

import com.subtrack.data.backup.JsonParser
import com.subtrack.data.backup.JsonValue
import com.subtrack.data.backup.asObject
import com.subtrack.data.backup.render
import com.subtrack.data.db.SubscriptionEntity
import org.junit.Assert.*
import org.junit.Test

/** F1 protocol compatibility: `resolves` round-trips, v1 payloads still decode, invalid resolutions are rejected. */
class SyncResolutionCodecTest {
    private fun entity() = SubscriptionEntity(
        id = "sub_codec_0000000001", name = "Codec", categoryId = "other", billingCycleType = "MONTHLY",
        anchorDateEpochDay = 1, nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE",
        createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
    )

    private fun op(resolves: Set<String> = emptySet()) = BusinessOperation.SubscriptionUpsert(
        "operation_codec_0001", "device_a", mapOf("device_a" to 2L, "device_b" to 1L), false, entity(), resolves,
    )

    @Test fun resolves_round_trip_is_lossless() {
        val resolved = op(setOf("operation_other_0001", "operation_other_0002"))
        val decoded = BusinessCodec.decode(BusinessCodec.encode(resolved))
        assertEquals(resolved.resolves, decoded.resolves)
        assertEquals(resolved.operationId, decoded.operationId)
        assertEquals(resolved.clock, decoded.clock)
    }

    @Test fun legacy_v1_payload_without_resolves_still_decodes() {
        val bytes = BusinessCodec.encode(op())
        val root = JsonParser(bytes.toString(Charsets.UTF_8)).parseDocument().asObject("op")
        val fields = root.fields.toMutableMap().apply {
            remove("resolves")
            this["schema"] = JsonValue.Num("1")
        }
        val legacy = JsonValue.Obj(fields).render().toByteArray(Charsets.UTF_8)
        val decoded = BusinessCodec.decode(legacy)
        assertEquals(emptySet<String>(), decoded.resolves)
        assertEquals("operation_codec_0001", decoded.operationId)
    }

    @Test fun unsupported_schema_and_invalid_resolution_ids_are_rejected() {
        val bytes = BusinessCodec.encode(op())
        val root = JsonParser(bytes.toString(Charsets.UTF_8)).parseDocument().asObject("op")
        val schema3 = JsonValue.Obj(root.fields.toMutableMap().apply { this["schema"] = JsonValue.Num("3") })
            .render().toByteArray(Charsets.UTF_8)
        assertNotNull(runCatching { BusinessCodec.decode(schema3) }.exceptionOrNull())

        assertNotNull("非规范 id 仍被拒绝", runCatching { BusinessCodec.encode(op(setOf("short"))) }.exceptionOrNull())
        assertNotNull("不能把自身声明为已解决", runCatching { BusinessCodec.encode(op(setOf("operation_codec_0001"))) }.exceptionOrNull())
        // 重复 cite 在 Set 中自然折叠成一次，仍是合法载荷
        assertFalse(BusinessCodec.encode(op(setOf("operation_other_0001", "operation_other_0001"))).isEmpty())
    }
}
