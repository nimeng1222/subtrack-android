package com.subtrack.sync

import com.subtrack.data.backup.*
import com.subtrack.network.*
import java.util.Base64

data class SyncDeviceCredential(val tenantId: String, val deviceId: String, val token: String) {
    override fun toString(): String = "SyncDeviceCredential(tenantId=$tenantId, deviceId=$deviceId, token=<redacted>)"
}
data class SyncPage(val operations: List<RemoteOperation>, val cursor: Long, val hasMore: Boolean)
data class SyncRemoteDevice(val id: String, val name: String, val revoked: Boolean)
data class SyncRoleState(val deviceId: String?, val version: Int)
data class SyncRegistration(val device: SyncDeviceCredential, val recoveryCredential: String) {
    override fun toString(): String = "SyncRegistration(device=$device, recoveryCredential=<redacted>)"
}

/** Wire layer for the self-hosted opaque-operation service. No ledger plaintext is sent here. */
class SyncWire(private val baseUrl: String, private val transport: NetworkTransport) {
    private fun endpoint(path: String): String {
        val base = baseUrl.trimEnd('/')
        val uri = java.net.URI(base)
        require(uri.rawQuery == null && uri.fragment == null && uri.userInfo == null && uri.host != null)
        return "$base$path"
    }
    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(pairs.toMap())
    private fun str(s: String) = JsonValue.Str(s)
    private fun encoded(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(raw: String): ByteArray = Base64.getDecoder().decode(raw)

    suspend fun register(setupToken: String, name: String): SyncRegistration {
        require(name.length in 1..80)
        val response = transport.request(endpoint("/v1/register"), obj("name" to str(name)).render(), setupToken = setupToken)
        val result = parseObject(response.toString(Charsets.UTF_8))
        val tenant = result.text("tenantId") ?: error("missing tenant")
        val device = result.text("deviceId") ?: error("missing device")
        val token = result.text("accessToken") ?: error("missing token")
        val recovery = result.text("recoveryCredential") ?: error("missing recovery credential")
        require(tenant.matches(Regex("[A-Za-z0-9_-]{1,128}")) && device.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            token.length in 32..200 && recovery.length in 32..200)
        return SyncRegistration(SyncDeviceCredential(tenant, device, token), recovery)
    }

    suspend fun pair(credential: String, name: String, recover: Boolean = false): SyncDeviceCredential {
        require(credential.length in 32..200 && name.length in 1..80)
        val body = obj("credential" to str(credential), "name" to str(name)).render()
        val response = transport.request(endpoint(if (recover) "/v1/recover" else "/v1/pair"), body)
        val result = parseObject(response.toString(Charsets.UTF_8))
        return SyncDeviceCredential(result.text("tenantId") ?: error("missing tenant"),
            result.text("deviceId") ?: error("missing device"), result.text("accessToken") ?: error("missing token"))
            .also { require(it.tenantId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                it.deviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && it.token.length in 32..200) }
    }

    private fun requireToken(token: String) = require(token.length in 32..200 && token.none { it.isISOControl() })

    /** C/G4: server-side single allocator for key epochs, so two devices can never rotate into the same epoch. */
    suspend fun nextEpoch(token: String, minEpoch: Int = 0): Int {
        requireToken(token)
        require(minEpoch in 0..1_000_000) { "invalid minEpoch" }
        val budget = "{\"minEpoch\":$minEpoch}"
        // Q5: epoch allocation is an idempotent coordination call; the generic 2s per-URL throttle would otherwise turn
        // an immediate "epoch already has a historical key, ask again" retry into a client-side rate-limit failure.
        val result = parseObject(
            transport.request(endpoint("/v1/epoch"), budget, token, throttle = false).toString(Charsets.UTF_8),
        )
        val epoch = result.number("epoch") ?: error("missing epoch")
        require(epoch in 2..1_000_000) { "server returned an invalid epoch" }
        return epoch.toInt()
    }

    /** P5: tenant-scoped reminder-device role, read via normal authenticated sync. */
    suspend fun role(token: String): SyncRoleState {
        requireToken(token)
        return parseRole(parseObject(transport.request(endpoint("/v1/role"), null, token).toString(Charsets.UTF_8)))
    }

    /** P5: compare-and-set publish of a role decision; the server versions it so racing devices converge. */
    suspend fun setRole(token: String, deviceId: String?, expectedVersion: Int): SyncRoleState {
        requireToken(token)
        require(expectedVersion >= 0)
        require(deviceId == null || (deviceId.length in 1..128 && deviceId.none { it.isISOControl() }))
        val body = if (deviceId == null) "{\"deviceId\":null,\"expectedVersion\":$expectedVersion}"
        else "{\"deviceId\":${str(deviceId).render()},\"expectedVersion\":$expectedVersion}"
        return parseRole(parseObject(transport.request(endpoint("/v1/role"), body, token).toString(Charsets.UTF_8)))
    }

    private fun parseRole(result: JsonValue.Obj): SyncRoleState {
        val version = (result.number("version") ?: 0L).toInt()
        val raw = result.text("deviceId")
        return SyncRoleState(deviceId = raw?.takeIf { it.isNotBlank() }, version = version)
    }

    suspend fun pairingToken(token: String): String {
        requireToken(token)
        return parseObject(transport.request(endpoint("/v1/pairing"), "{}", token)
        .toString(Charsets.UTF_8)).text("pairingCredential")?.also { require(it.length in 32..200) }
        ?: error("missing pairing credential")
    }

    suspend fun devices(token: String): List<SyncRemoteDevice> {
        requireToken(token)
        val response = parseObject(transport.request(endpoint("/v1/devices"), key = token).toString(Charsets.UTF_8))
        val devices = response.items("devices")
        require(devices.size <= 1000)
        return devices.map { item ->
            val o = item.asObject("device")
            val id = o.text("id") ?: error("missing device ID")
            val name = o.text("name") ?: error("missing device name")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && name.length <= 80)
            val revoked = o.required("revoked", "device").asLong("revoked")
            require(revoked in 0..1)
            SyncRemoteDevice(id, name, revoked == 1L)
        }
    }

    suspend fun revoke(token: String, deviceId: String) {
        requireToken(token)
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val body = obj("deviceId" to str(deviceId)).render()
        val response = parseObject(transport.request(endpoint("/v1/revoke"), body, token).toString(Charsets.UTF_8))
        require(response.text("revoked") == deviceId && response.required("keyRotationRequired", "revoke").asBoolean("keyRotationRequired"))
    }

    /** Acknowledgement means uploaded, never an applied-download checkpoint. */
    suspend fun upload(token: String, item: SyncOutboxEntity): Long {
        requireToken(token)
        require(item.operationId.matches(Regex("[A-Za-z0-9_-]{16,128}")) && item.nonce.size == 12 &&
            item.ciphertext.size in 16..49168 && item.keyEpoch > 0)
        val envelope = obj("version" to JsonValue.Num("1"), "keyEpoch" to JsonValue.Num(item.keyEpoch.toString()),
            "nonce" to str(encoded(item.nonce)), "ciphertext" to str(encoded(item.ciphertext)))
        val body = obj("ops" to JsonValue.Arr(listOf(obj("opId" to str(item.operationId), "envelope" to envelope)))).render()
        val result = parseObject(transport.request(endpoint("/v1/ops"), body, token, maxRequestBytes = 131_072).toString(Charsets.UTF_8))
        val accepted = result.items("accepted").single().asObject("accepted")
        require(accepted.text("opId") == item.operationId)
        return (accepted.number("seq") ?: error("missing server revision")).also { require(it > 0) }
    }

    suspend fun changes(token: String, after: Long, limit: Int = 10): SyncPage {
        requireToken(token)
        require(after >= 0 && limit in 1..10)
        val result = parseObject(transport.request(endpoint("/v1/changes?after=$after&limit=$limit"), key = token)
            .toString(Charsets.UTF_8))
        var previous = after
        val ops = result.items("changes").map { value ->
            val o = value.asObject("change"); val seq = o.number("seq") ?: error("missing sequence")
            require(seq > previous); previous = seq
            val id = o.text("opId") ?: error("missing operation ID")
            require(id.matches(Regex("[A-Za-z0-9_-]{16,128}")))
            val e = o.required("envelope", "change").asObject("envelope")
            require(e.number("version") == 1L)
            val epoch = e.number("keyEpoch") ?: error("missing epoch")
            require(epoch in 1..Int.MAX_VALUE.toLong())
            val nonce = decode(e.text("nonce") ?: error("missing nonce"))
            val ciphertext = decode(e.text("ciphertext") ?: error("missing ciphertext"))
            require(nonce.size == 12 && ciphertext.size in 16..49168)
            RemoteOperation(seq, id, SyncCrypto.Envelope(keyEpoch = epoch.toInt(), nonce = nonce, ciphertext = ciphertext))
        }
        val cursor = result.number("cursor") ?: error("missing cursor")
        require(cursor == previous)
        return SyncPage(ops, cursor, result.required("hasMore", "changes").asBoolean("hasMore"))
    }
}
