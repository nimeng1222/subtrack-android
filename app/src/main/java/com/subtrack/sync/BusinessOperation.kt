package com.subtrack.sync

import com.subtrack.data.backup.JsonParser
import com.subtrack.data.backup.JsonValue
import com.subtrack.data.backup.asBoolean
import com.subtrack.data.backup.asInt
import com.subtrack.data.backup.asLong
import com.subtrack.data.backup.asObject
import com.subtrack.data.backup.asString
import com.subtrack.data.backup.optional
import com.subtrack.data.backup.render
import com.subtrack.data.backup.required
import com.subtrack.data.db.CategoryEntity
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.domain.model.Limits
import com.subtrack.network.number
import com.subtrack.network.text

/**
 * Versioned plaintext payload of a sync business operation.
 *
 * This schema defines *what* a business mutation means across devices (subscription add/edit/pause/delete tombstone,
 * payment confirmation, category change). It is NEVER stored or transmitted in plaintext: the caller encrypts the
 * encoded bytes with [SyncCrypto] (AES-GCM, AAD binds vault + operationId + key epoch) before they enter the outbox or
 * the wire. Decoding therefore only ever happens after authenticated decryption.
 *
 * Every operation carries a causal clock so devices merge by causality rather than wall-clock time; `deleted` marks a
 * tombstone so an offline device cannot resurrect a delete. Identity (operationId) is stable so upload retries and the
 * server are idempotent.
 */
sealed interface BusinessOperation {
    val operationId: String
    val deviceId: String
    val clock: Map<String, Long>
    val deleted: Boolean
    val entityId: String
    val kind: String

    /**
     * 明确的冲突解决关系（F1）：非空表示这是一次**用户解决决定**，cite 了被它因果覆盖的旧 head id。
     * 普通业务编辑为空集合；接收端仅在 cite 的版本被本次操作因果覆盖（或本地尚不知道）时才接受。
     */
    val resolves: Set<String>

    data class SubscriptionUpsert(
        override val operationId: String,
        override val deviceId: String,
        override val clock: Map<String, Long>,
        override val deleted: Boolean,
        val entity: SubscriptionEntity,
        override val resolves: Set<String> = emptySet(),
    ) : BusinessOperation {
        override val entityId: String get() = entity.id
        override val kind: String get() = "subscription"
    }

    data class PaymentUpsert(
        override val operationId: String,
        override val deviceId: String,
        override val clock: Map<String, Long>,
        override val deleted: Boolean,
        val entity: PaymentRecordEntity,
        override val resolves: Set<String> = emptySet(),
    ) : BusinessOperation {
        override val entityId: String get() = entity.id
        override val kind: String get() = "payment"
    }

    data class CategoryUpsert(
        override val operationId: String,
        override val deviceId: String,
        override val clock: Map<String, Long>,
        override val deleted: Boolean,
        val entity: CategoryEntity,
        override val resolves: Set<String> = emptySet(),
    ) : BusinessOperation {
        override val entityId: String get() = entity.id
        override val kind: String get() = "category"
    }
}

/** Strict encoder/decoder for [BusinessOperation]. Round-trips losslessly and rejects malformed/oversized payloads. */
object BusinessCodec {
    /** v2 adds the optional explicit-resolution set (`resolves`). v1 payloads are still accepted (empty resolves). */
    const val SCHEMA_VERSION = 2
    val SUPPORTED_SCHEMAS = setOf(1L, 2L)
    const val MAX_PLAINTEXT_BYTES = 48 * 1024
    private val OPERATION_ID = Regex("[A-Za-z0-9_-]{16,128}")
    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val CURRENCY = Regex("[A-Z]{3}")

    fun encode(op: BusinessOperation): ByteArray {
        validate(op)
        val entity = when (op) {
            is BusinessOperation.SubscriptionUpsert -> subscriptionToJson(op.entity)
            is BusinessOperation.PaymentUpsert -> paymentToJson(op.entity)
            is BusinessOperation.CategoryUpsert -> categoryToJson(op.entity)
        }
        val root = JsonValue.Obj(
            linkedMapOf(
                "schema" to num(SCHEMA_VERSION.toLong()),
                "kind" to JsonValue.Str(op.kind),
                "operationId" to JsonValue.Str(op.operationId),
                "deviceId" to JsonValue.Str(op.deviceId),
                "deleted" to JsonValue.Bool(op.deleted),
                "entityId" to JsonValue.Str(op.entityId),
                "clock" to clockToJson(op.clock),
                "resolves" to JsonValue.Arr(op.resolves.sorted().map { JsonValue.Str(it) }),
                "entity" to entity,
            ),
        )
        val bytes = root.render().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "Sync operation payload too large" }
        return bytes
    }

    fun decode(bytes: ByteArray): BusinessOperation {
        require(bytes.size in 1..MAX_PLAINTEXT_BYTES) { "Sync operation payload size invalid" }
        val root = JsonParser(bytes.toString(Charsets.UTF_8)).parseDocument().asObject("sync operation")
        val schema = root.number("schema") ?: error("missing schema")
        require(schema in SUPPORTED_SCHEMAS) { "Unsupported sync operation schema" }
        require(root.fields.keys == expectedKeys(schema)) { "Unexpected sync operation fields" }
        val resolves = if (schema == 2L) decodeResolves(root) else emptySet()
        val operationId = root.text("operationId") ?: error("missing operationId")
        val deviceId = root.text("deviceId") ?: error("missing deviceId")
        val deleted = root.required("deleted", "sync operation").asBoolean("deleted")
        val entityId = root.text("entityId") ?: error("missing entityId")
        val clock = clockFromJson(root.required("clock", "sync operation").asObject("clock"))
        val entity = root.required("entity", "sync operation").asObject("entity")
        val op = when (root.text("kind")) {
            "subscription" -> BusinessOperation.SubscriptionUpsert(operationId, deviceId, clock, deleted, subscriptionFromJson(entity), resolves)
            "payment" -> BusinessOperation.PaymentUpsert(operationId, deviceId, clock, deleted, paymentFromJson(entity), resolves)
            "category" -> BusinessOperation.CategoryUpsert(operationId, deviceId, clock, deleted, categoryFromJson(entity), resolves)
            else -> error("Unknown sync operation kind")
        }
        require(op.entityId == entityId) { "entityId does not match entity" }
        validate(op)
        return op
    }

    private val EXPECTED_KEYS_V1 = setOf("schema", "kind", "operationId", "deviceId", "deleted", "entityId", "clock", "entity")
    private val EXPECTED_KEYS_V2 = EXPECTED_KEYS_V1 + "resolves"
    private fun expectedKeys(schema: Long) = if (schema == 2L) EXPECTED_KEYS_V2 else EXPECTED_KEYS_V1

    private fun decodeResolves(root: JsonValue.Obj): Set<String> {
        val raw = root.required("resolves", "sync operation") as? JsonValue.Arr
            ?: error("resolves must be an array")
        require(raw.items.size <= 128)
        return raw.items.map { it.asString("resolves[]") }.toSet()
    }

    private fun validate(op: BusinessOperation) {
        require(OPERATION_ID.matches(op.operationId)) { "Invalid operationId" }
        require(DEVICE_ID.matches(op.deviceId)) { "Invalid deviceId" }
        require(op.clock.size in 1..128 && op.clock.all { (d, n) -> DEVICE_ID.matches(d) && n in 1..Long.MAX_VALUE - 1 }) {
            "Invalid causal clock"
        }
        require(op.resolves.size <= 128 && op.resolves.all { OPERATION_ID.matches(it) } && op.resolves.none { it == op.operationId }) {
            "Invalid resolution set"
        }
        when (op) {
            is BusinessOperation.SubscriptionUpsert -> with(op.entity) {
                require(id == op.entityId && id.isNotBlank())
                require(name.length in 1..200)
                require(CURRENCY.matches(currency))
                require(Limits.isValidPriceMinor(priceMinor))
                require(customCycleDays == null || Limits.isValidCustomCycleDays(customCycleDays))
                require(billingCycleType in com.subtrack.domain.model.BillingCycleType.entries.map { it.name })
                if (op.deleted) require(deletedAtUtcMillis != null) { "Tombstone must carry deletedAtUtcMillis" }
            }
            is BusinessOperation.PaymentUpsert -> with(op.entity) {
                require(id == op.entityId && id.isNotBlank())
                require(CURRENCY.matches(currency))
                require(Limits.isValidPaymentMinor(amountMinor))
                require(subscriptionId.isNotBlank())
            }
            is BusinessOperation.CategoryUpsert -> with(op.entity) {
                require(id == op.entityId && id.isNotBlank())
                require(name.length in 1..80 && iconKey.isNotBlank())
            }
        }
    }

    // ---- JSON field helpers -------------------------------------------------
    private fun num(value: Long) = JsonValue.Num(value.toString())
    private fun str(value: String) = JsonValue.Str(value)
    private fun bool(value: Boolean) = JsonValue.Bool(value)
    private fun nullableStr(value: String?) = value?.let { JsonValue.Str(it) } ?: JsonValue.Null
    private fun nullableNum(value: Long?) = value?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null
    private fun nullableInt(value: Int?) = value?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null

    private fun clockToJson(clock: Map<String, Long>) =
        JsonValue.Obj(clock.entries.sortedBy { it.key }.associate { it.key to num(it.value) })

    private fun clockFromJson(obj: JsonValue.Obj): Map<String, Long> {
        require(obj.fields.size in 1..128)
        return obj.fields.mapValues { (k, v) -> v.asLong("clock.$k") }.toSortedMap()
    }

    private fun subscriptionToJson(e: SubscriptionEntity) = JsonValue.Obj(linkedMapOf(
        "id" to str(e.id), "name" to str(e.name), "categoryId" to str(e.categoryId),
        "paymentMethodId" to nullableStr(e.paymentMethodId), "billingCycleType" to str(e.billingCycleType),
        "customCycleDays" to nullableInt(e.customCycleDays), "anchorDateEpochDay" to num(e.anchorDateEpochDay.toLong()),
        "nextChargeDateEpochDay" to nullableInt(e.nextChargeDateEpochDay), "priceMinor" to num(e.priceMinor),
        "currency" to str(e.currency), "status" to str(e.status), "autoRenew" to bool(e.autoRenew),
        "trialEndEpochDay" to nullableInt(e.trialEndEpochDay), "canceledAtEpochDay" to nullableInt(e.canceledAtEpochDay),
        "reminderOffsetsCsv" to nullableStr(e.reminderOffsetsCsv),
        "reminderMutedUntilEpochDay" to nullableInt(e.reminderMutedUntilEpochDay), "note" to nullableStr(e.note),
        "createdAtUtcMillis" to num(e.createdAtUtcMillis), "updatedAtUtcMillis" to num(e.updatedAtUtcMillis),
        "deletedAtUtcMillis" to nullableNum(e.deletedAtUtcMillis),
    ))

    private fun subscriptionFromJson(o: JsonValue.Obj) = SubscriptionEntity(
        id = o.text("id") ?: error("missing id"),
        name = o.text("name") ?: error("missing name"),
        categoryId = o.text("categoryId") ?: error("missing categoryId"),
        paymentMethodId = o.text("paymentMethodId"),
        billingCycleType = o.text("billingCycleType") ?: error("missing billingCycleType"),
        customCycleDays = o.optional("customCycleDays")?.asInt("customCycleDays"),
        anchorDateEpochDay = o.required("anchorDateEpochDay", "subscription").asInt("anchorDateEpochDay"),
        nextChargeDateEpochDay = o.optional("nextChargeDateEpochDay")?.asInt("nextChargeDateEpochDay"),
        priceMinor = o.required("priceMinor", "subscription").asLong("priceMinor"),
        currency = o.text("currency") ?: error("missing currency"),
        status = o.text("status") ?: error("missing status"),
        autoRenew = o.required("autoRenew", "subscription").asBoolean("autoRenew"),
        trialEndEpochDay = o.optional("trialEndEpochDay")?.asInt("trialEndEpochDay"),
        canceledAtEpochDay = o.optional("canceledAtEpochDay")?.asInt("canceledAtEpochDay"),
        reminderOffsetsCsv = o.text("reminderOffsetsCsv"),
        reminderMutedUntilEpochDay = o.optional("reminderMutedUntilEpochDay")?.asInt("reminderMutedUntilEpochDay"),
        note = o.text("note"),
        createdAtUtcMillis = o.required("createdAtUtcMillis", "subscription").asLong("createdAtUtcMillis"),
        updatedAtUtcMillis = o.required("updatedAtUtcMillis", "subscription").asLong("updatedAtUtcMillis"),
        deletedAtUtcMillis = o.optional("deletedAtUtcMillis")?.asLong("deletedAtUtcMillis"),
    )

    private fun paymentToJson(e: PaymentRecordEntity) = JsonValue.Obj(linkedMapOf(
        "id" to str(e.id), "subscriptionId" to str(e.subscriptionId), "paidAtEpochDay" to num(e.paidAtEpochDay.toLong()),
        "amountMinor" to num(e.amountMinor), "currency" to str(e.currency), "source" to str(e.source),
        "createdAtUtcMillis" to num(e.createdAtUtcMillis), "note" to nullableStr(e.note),
    ))

    private fun paymentFromJson(o: JsonValue.Obj) = PaymentRecordEntity(
        id = o.text("id") ?: error("missing id"),
        subscriptionId = o.text("subscriptionId") ?: error("missing subscriptionId"),
        paidAtEpochDay = o.required("paidAtEpochDay", "payment").asInt("paidAtEpochDay"),
        amountMinor = o.required("amountMinor", "payment").asLong("amountMinor"),
        currency = o.text("currency") ?: error("missing currency"),
        source = o.text("source") ?: error("missing source"),
        createdAtUtcMillis = o.required("createdAtUtcMillis", "payment").asLong("createdAtUtcMillis"),
        note = o.text("note"),
    )

    private fun categoryToJson(e: CategoryEntity) = JsonValue.Obj(linkedMapOf(
        "id" to str(e.id), "name" to str(e.name), "iconKey" to str(e.iconKey), "colorArgb" to num(e.colorArgb.toLong()),
        "isBuiltIn" to bool(e.isBuiltIn), "sortOrder" to num(e.sortOrder.toLong()),
    ))

    private fun categoryFromJson(o: JsonValue.Obj) = CategoryEntity(
        id = o.text("id") ?: error("missing id"),
        name = o.text("name") ?: error("missing name"),
        iconKey = o.text("iconKey") ?: error("missing iconKey"),
        colorArgb = o.required("colorArgb", "category").asInt("colorArgb"),
        isBuiltIn = o.required("isBuiltIn", "category").asBoolean("isBuiltIn"),
        sortOrder = o.required("sortOrder", "category").asInt("sortOrder"),
    )
}
