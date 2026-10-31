package com.subtrack.sync

import com.subtrack.data.db.CategoryEntity
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.data.db.SubscriptionEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessOperationTest {
    private val vault = "vault_12345678"
    private val clock = mapOf("device_a" to 3L, "device_b" to 1L)

    private fun subscription(
        id: String = "sub_0000000000000001",
        deleted: Boolean = false,
    ) = SubscriptionEntity(
        id = id, name = "视频会员", categoryId = "video", paymentMethodId = null,
        billingCycleType = "MONTHLY", customCycleDays = null, anchorDateEpochDay = 20000,
        nextChargeDateEpochDay = 20030, priceMinor = 2500, currency = "CNY", status = "ACTIVE",
        autoRenew = true, trialEndEpochDay = null, canceledAtEpochDay = null,
        reminderOffsetsCsv = "3,0", reminderMutedUntilEpochDay = null, note = "家庭共享",
        createdAtUtcMillis = 1_700_000_000_000, updatedAtUtcMillis = 1_700_000_100_000,
        deletedAtUtcMillis = if (deleted) 1_700_000_200_000 else null,
    )

    private fun payment() = PaymentRecordEntity(
        id = "pay_0000000000000001", subscriptionId = "sub_0000000000000001", paidAtEpochDay = 20030,
        amountMinor = 2500, currency = "CNY", source = "USER_CONFIRMED", createdAtUtcMillis = 1_700_000_300_000, note = null,
    )

    private fun category() = CategoryEntity(
        id = "cat_0000000000000001", name = "影音", iconKey = "play", colorArgb = 0xFF007AFF.toInt(),
        isBuiltIn = false, sortOrder = 9,
    )

    private fun subOp(entity: SubscriptionEntity = subscription(), deleted: Boolean = false) =
        BusinessOperation.SubscriptionUpsert("operation_0000000001", "device_a", clock, deleted, entity)

    private fun roundTrip(op: BusinessOperation): BusinessOperation =
        BusinessCodec.decode(BusinessCodec.encode(op))

    @Test fun subscription_round_trips_losslessly() {
        val op = subOp()
        assertEquals(op, roundTrip(op))
    }

    @Test fun payment_and_category_round_trip_losslessly() {
        val p = BusinessOperation.PaymentUpsert("operation_0000000002", "device_b", clock, false, payment())
        assertEquals(p, roundTrip(p))
        val c = BusinessOperation.CategoryUpsert("operation_0000000003", "device_a", clock, false, category())
        assertEquals(c, roundTrip(c))
    }

    @Test fun delete_tombstone_round_trips_and_carries_deleted_timestamp() {
        val tombstone = subOp(subscription(deleted = true), deleted = true)
        val decoded = roundTrip(tombstone) as BusinessOperation.SubscriptionUpsert
        assertTrue(decoded.deleted)
        assertNotNull(decoded.entity.deletedAtUtcMillis)
        assertEquals(tombstone, decoded)
    }

    @Test fun encrypted_envelope_round_trips_through_crypto() {
        val key = SyncCrypto.parseRecoveryKey(SyncCrypto.generateRecoveryKey())
        val op = subOp()
        val envelope = SyncCrypto.encrypt(key, vault, op.operationId, 1, BusinessCodec.encode(op))
        val plaintext = SyncCrypto.decrypt(key, vault, op.operationId, envelope)
        assertEquals(op, BusinessCodec.decode(plaintext))
        // A tampered ciphertext must not decrypt into a valid operation.
        val changed = envelope.copy(ciphertext = envelope.ciphertext.clone().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertNotNull(runCatching { SyncCrypto.decrypt(key, vault, op.operationId, changed) }.exceptionOrNull())
    }

    @Test fun clock_order_and_ids_are_preserved() {
        val op = roundTrip(subOp()) as BusinessOperation.SubscriptionUpsert
        assertEquals(listOf("device_a", "device_b"), op.clock.keys.toList())
        assertEquals(3L, op.clock["device_a"])
    }

    @Test fun malformed_payloads_are_rejected() {
        fun rejects(json: String) = assertNotNull(runCatching { BusinessCodec.decode(json.toByteArray()) }.exceptionOrNull())
        rejects("{}")
        rejects("not json")
        rejects("""{"schema":2,"kind":"subscription","operationId":"operation_0000000001","deviceId":"device_a","deleted":false,"entityId":"sub_0000000000000001","clock":{"device_a":1},"entity":{}}""")
        rejects("""{"schema":1,"kind":"unknown","operationId":"operation_0000000001","deviceId":"device_a","deleted":false,"entityId":"x","clock":{"device_a":1},"entity":{}}""")
        rejects("""{"schema":1,"kind":"subscription","operationId":"bad id","deviceId":"device_a","deleted":false,"entityId":"x","clock":{"device_a":1},"entity":{}}""")
        rejects("""{"schema":1,"kind":"subscription","operationId":"operation_0000000001","deviceId":"device_a","deleted":false,"entityId":"x","clock":{},"entity":{}}""")
        // Unknown extra field (forward-compat guard): must be rejected rather than silently dropped.
        rejects("""{"schema":1,"kind":"subscription","operationId":"operation_0000000001","deviceId":"device_a","deleted":false,"entityId":"sub_0000000000000001","clock":{"device_a":1},"entity":{},"extra":1}""")
    }

    @Test fun semantic_validation_rejects_bad_price_currency_and_tombstone() {
        fun rejects(op: BusinessOperation) = assertNotNull(runCatching { BusinessCodec.encode(op) }.exceptionOrNull())
        rejects(subOp(subscription().copy(currency = "cny")))
        rejects(subOp(subscription().copy(priceMinor = Long.MAX_VALUE)))
        // A tombstone without deletedAtUtcMillis is inconsistent and must be refused.
        rejects(subOp(subscription(deleted = false), deleted = true))
    }

    @Test fun oversized_payload_is_rejected() {
        val huge = subscription().copy(name = "x".repeat(60 * 1024))
        assertNotNull(runCatching { BusinessCodec.encode(subOp(huge)) }.exceptionOrNull())
    }
}
