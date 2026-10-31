package com.subtrack.sync

import android.content.Context
import com.subtrack.BuildConfig
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.network.NetworkTransport
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.sync.withLock

/** Non-secret sync endpoint storage (never keys/credentials); survives restarts, not backed up with the ledger. */
private const val PREFS = "subtrack-sync-product"
private const val KEY_ENDPOINT = "endpoint"
private const val KEY_TENANT = "tenant"
private const val KEY_ENABLED = "enabled"
private const val KEY_DESIGNATED_REMINDER = "designatedReminderDevice"
private const val KEY_DISABLED_AT = "disabledAtMillis"
private const val KEY_PENDING_ROTATION = "pendingRotationEpoch"  // legacy, unused after migration to per-session markers

/**
 * G1: the server-issued `recoveryCredential` is an AUTHENTICATION recovery secret (it proves account ownership to
 * `/v1/recover`); it is NOT and must never be the E2EE data key. The data key is generated on this device and only its
 * vault+epoch-bound recovery bundle is transferred out of band.
 */
data class VaultCreation(val vaultId: String, val dataKeyRecovery: String, val authRecovery: String)

data class SyncProductStatus(
    val active: Boolean,
    val vaultId: String?,
    val deviceId: String?,
    val keyEpoch: Int?,
    val pendingUploads: Int,
    val unresolvedConflicts: Int,
    val lastCursor: Long?,
    val deviceName: String?,
)

/**
 * F8 production facade for the sync product: create a vault, join an existing one via a pairing credential plus the
 * out-of-band recovery material, sync now, list/revoke devices, resolve conflicts (decisions are published), rotate keys,
 * and leave/disable. All ledger writes go through the same atomic [OutboxSyncWriteGateway] path as normal editing.
 *
 * Ordinary users can only reach this through the network settings section, which stays disabled by default until the
 * two-device matrix passes (see docs/FIX-REPORT-*).
 */
class SyncProductService(
    private val context: Context,
    private val db: SubTrackDatabase,
    private val runtime: SwitchableSyncWriteGateway,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Diagnostic for tests/settings: why the outbox binding could not be restored on process start (null = fine). */
    @Volatile var initError: String? = null
        private set

    /**
     * F8: restore the production write path on process start. Without this, an edit made in a fresh process would
     * silently bypass the outbox (direct pass-through) even though the vault is active.
     */
    fun restoreBinding(): Boolean {
        initError = null
        return runCatching {
            val vault = prefs.getString(KEY_TENANT, null)
            val base = prefs.getString(KEY_ENDPOINT, null)
            if (vault == null || base == null) return false
            if (!prefs.getBoolean(KEY_ENABLED, true)) {
                // Disabled after a restart: keep recording disabled-period edits (they stay local until confirmed).
                val gen = bumpGeneration()
                runtime.bindOffline(OfflineSyncWriteGateway(db, vault, admission = { bindingGeneration == gen }))
                return true
            }
            val credential = credentialStore().load(base, vault) ?: run {
                initError = "saved credential unavailable for vault $vault"
                return false
            }
            bindSession(base, credential)
            !runtime.isDirect
        }.getOrElse {
            initError = it.stackTraceToString().take(300)
            false
        }
    }
    private fun wire(endpoint: String) = SyncWire(endpoint, NetworkTransport(BuildConfig.DEBUG))
    private fun keyStore() = SyncKeyStore(context)
    private fun credentialStore() = SyncCredentialStore(context)

    val endpoint: String? get() = prefs.getString(KEY_ENDPOINT, null)

    /** Non-secret service address. Changing it is a session switch, not a plain preference edit (N2). */
    suspend fun setEndpoint(url: String) { switchEndpoint(url) }

    /**
     * N2: switching the service address invalidates the current session immediately (old tasks can no longer commit),
     * keeps old outbox rows under their original vault, and only rebinds if this device already holds a credential for
     * the SAME vault on the NEW endpoint. Otherwise the vault is explicitly marked disabled (no silent direct-write
     * loss); re-joining/importing a recovery bundle restores it.
     */
    suspend fun switchEndpoint(url: String): Boolean {
        val target = url.trim()
        require(target.startsWith("https://") || (BuildConfig.DEBUG && target.startsWith("http://"))) { "同步服务地址必须为 HTTPS" }
        val current = prefs.getString(KEY_ENDPOINT, null)
        if (current == target) return !runtime.isDirect
        val vault = activeVault
        val newCredential = vault?.let { credentialStore().load(target, it) }
        // Invalidate first: any in-flight task from the old endpoint will fail admission at its next commit.
        sessionIdentity = null
        bumpGeneration()
        runtime.reset()
        prefs.edit().putString(KEY_ENDPOINT, target).putBoolean(KEY_ENABLED, newCredential != null).commit()
        // No active vault → nothing can be bound; report "not bound" rather than a misleading success.
        if (vault == null) return false
        if (newCredential != null) {
            db.syncDao().setEnabled(vault, true)
            bindSession(target, newCredential)
            return true
        }
        db.syncDao().setEnabled(vault, false)
        val gen = bumpGeneration()
        runtime.bindOffline(OfflineSyncWriteGateway(db, vault, admission = { bindingGeneration == gen }))
        return false
    }
    val activeVault: String? get() = prefs.getString(KEY_TENANT, null)

    // A function (not a val lambda) so the init block below can never observe an uninitialised property.
    private suspend fun keyForEpoch(epoch: Int): ByteArray {
        val vault = requireNotNull(activeVault) { "同步未启用" }
        return keyStore().load(vault, epoch) ?: throw MissingEpochKeyException(epoch)
    }

    /** B/G5: builds an immutable session; its key provider is bound to THIS vault, never to a mutable activeVault. */
    private fun sessionFor(base: String, credential: SyncDeviceCredential): SyncSession = SyncSession(
        endpoint = base,
        vaultId = credential.tenantId,
        deviceId = credential.deviceId,
        token = credential.token,
        keyProvider = { epoch -> keyStore().load(credential.tenantId, epoch) ?: throw MissingEpochKeyException(epoch) },
    )

    private fun bindSession(base: String, credential: SyncDeviceCredential): SyncSession {
        val session = sessionFor(base, credential)
        val gateway = OutboxSyncWriteGateway(
            session.vaultId, session.deviceId, SyncHeadStore(db.syncHeadDao()),
            SyncEngine(db, wire(base)) { runtime.isCurrent(session) },
            session.keyProvider,
            admission = { runtime.isCurrent(session) },
            isOwnedTransaction = {
                val owner = currentTransactionOwner()
                owner != null && owner.database === db && owner.vaultId == session.vaultId
            },
        )
        bumpGeneration()
        runtime.bind(session, gateway)
        sessionIdentity = session
        return session
    }

    private suspend fun credentialOrNull(): SyncDeviceCredential? {
        val vault = activeVault ?: return null
        val base = endpoint ?: return null
        return credentialStore().load(base, vault)
    }

    /**
     * Create a new vault. The E2EE data key is generated HERE (client-side); the server never sees it. The server's
     * authentication recovery credential is returned separately for account recovery and is never used as the data key.
     */
    suspend fun createVault(setupToken: String, deviceName: String): VaultCreation {
        require(setupToken.length in 32..200) { "请填写配对/安装令牌" }
        val base = requireNotNull(endpoint) { "请先填写同步服务地址" }
        val registration = wire(base).register(setupToken, deviceName.ifBlank { "device" })
        val dataKey = SyncCrypto.generateRecoveryKey()
        require(dataKey != registration.recoveryCredential) { "数据密钥必须由客户端独立生成" }
        persist(base, registration.device, SyncRecoveryBundle.create(registration.device.tenantId, 1, dataKey))
        return VaultCreation(
            registration.device.tenantId,
            SyncRecoveryBundle.create(registration.device.tenantId, 1, dataKey),
            registration.recoveryCredential,
        )
    }

    /**
     * Join an existing vault: real `/v1/pair` with a single-use pairing credential, plus the recovery material that the
     * first device shows out-of-band (the product has no silent key transport).
     */
    /**
     * Join via the real pairing credential + a **vault+epoch-bound** recovery bundle. The bundle must target the same
     * vault the server returned; a mismatched vault/epoch/key is rejected before anything is stored or enabled.
     */
    suspend fun joinVault(pairingCredential: String, recoveryBundle: String, deviceName: String): String {
        val base = requireNotNull(endpoint) { "请先填写同步服务地址" }
        require(pairingCredential.length in 32..200) { "配对凭据无效" }
        val parsed = SyncRecoveryBundle.parse(recoveryBundle)
        val credential = wire(base).pair(pairingCredential, deviceName.ifBlank { "device" })
        require(parsed.vaultId == credential.tenantId) { "恢复包属于其它账本，拒绝加入" }
        persist(base, credential, recoveryBundle)
        return credential.tenantId
    }

    /**
     * G4/H2: import a recovery bundle for an EXISTING session (e.g. a rotated epoch delivered out of band). Keys for older
     * epochs are retained; cursor and the active vault are preserved (never reset by re-joining).
     */
    suspend fun importRecoveryBundle(bundle: String): Int {
        val base = requireNotNull(endpoint) { "同步未启用" }
        val credential = requireNotNull(credentialOrNull()) { "同步凭据缺失" }
        val parsed = SyncRecoveryBundle.parse(bundle)
        require(parsed.vaultId == credential.tenantId) { "恢复包属于其它账本，拒绝导入" }
        val state = requireNotNull(db.syncDao().state(credential.tenantId)) { "同步状态缺失" }
        // J1: key file + Room are NOT one transaction. If a previous attempt committed the key file and died before the
        // state write, a retry of the same bundle must COMPLETE the epoch state rather than skip it. storeKeyChecked is
        // idempotent for the same key and rejects a different key before any mutation.
        vaultMutex(credential.tenantId).withLock {
            storeKeyChecked(credential.tenantId, parsed.epoch, parsed.keyMaterial)
            reconcileImportedEpoch(credential.tenantId, parsed.epoch)
        }
        // J2: importing key material is NOT "restore sync". enabled/cursor/credentials/runtime binding are untouched;
        // a disabled session stays disabled, and a new epoch never silently re-enables it.
        return parsed.epoch
    }

    /**
     * Advances the persisted key epoch to cover an imported bundle, never backwards and never touching cursor/enabled.
     * Re-running it after a crash (key file committed, state behind) is safe and completes the interrupted import.
     */
    internal suspend fun reconcileImportedEpoch(vault: String, epoch: Int) {
        // Atomic monotonic advance: never rewrites cursor/enabled and never clobbers a concurrent writer's update.
        db.syncDao().advanceEpoch(vault, epoch)
    }

    /**
     * Stores the CLIENT data key for this vault+epoch. `recoveryMaterial` must be a data-key recovery bundle generated by
     * [SyncCrypto.generateRecoveryKey] (or a later epoch's bundle); server auth recovery credentials must never reach it.
     */
    private val keyLock = Any()

    /**
     * Q1: the frozen session identity for the CURRENT vault. It survives disable() (disabled-period edits/publish need
     * it) but is replaced by reenable/create/join and cleared by an endpoint switch, so a task that captured the old
     * identity can never commit afterwards.
     */
    @Volatile
    private var sessionIdentity: SyncSession? = null

    /**
     * R1: monotonically increasing binding generation. Every session change (bind, disable, endpoint switch) bumps it, so
     * an offline/recording write that started under an older binding can detect the change even within the same vault.
     */
    private val initializationGate = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile
    var initializationError: String? = null
        private set

    /** S2: production startup boundary — preview/publish wait for migration + interrupted-confirmation recovery. */
    suspend fun ensureInitialized() = initializationGate.await()

    internal fun markInitialized() { if (!initializationGate.isCompleted) initializationGate.complete(Unit) }

    internal fun recordInitializationError(error: Throwable) {
        initializationError = error.stackTraceToString().take(300)
        markInitialized()
    }

    @Volatile
    private var bindingGeneration = 0L
    private fun bumpGeneration(): Long { bindingGeneration += 1; return bindingGeneration }
    internal fun currentBindingGeneration(): Long = bindingGeneration

    /**
     * I1: a key for (vault, epoch) may be written only once. Existing same key = idempotent success; existing different
     * key = hard rejection BEFORE any state/credential/cursor/enabled change. Check + write are serialized in-process and
     * the key file itself is an AtomicFile, so a crash leaves either the old or the new key, never a partial file.
     */
    // Same lock table used by syncNow / conflict publishing / writes, so import and rotation serialize with them.
    private fun vaultMutex(vault: String) = SyncVaultLocks.forVault(vault)

    /**
     * L1: allocates the next epoch key inside the per-vault lock. A concurrent rotation either gets a strictly higher
     * epoch or fails without side effects — it can never return success for the same epoch with a different key (the
     * previous bug wrote directly to the KeyStore, bypassing storeKeyChecked).
     */
    internal suspend fun allocateNextEpochKey(vault: String): Pair<Int, String> = vaultMutex(vault).withLock {
        repeat(8) {
            val state = db.syncDao().state(vault) ?: error("同步状态缺失")
            val candidate = state.keyEpoch + 1
            // M2 recovery: the key file for this epoch may already be committed by an interrupted rotation (state write
            // never happened). NEVER delete or replace it — adopt the committed key and complete the state advance.
            keyStore().load(vault, candidate)?.let { existing ->
                db.syncDao().advanceEpoch(vault, candidate)
                return@withLock candidate to java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(existing)
            }
            val material = SyncCrypto.generateRecoveryKey()
            val written = runCatching { storeKeyChecked(vault, candidate, material) }
                .getOrElse { if (it is IllegalArgumentException) return@repeat else throw it }
            if (written) {
                db.syncDao().advanceEpoch(vault, candidate)
                return@withLock candidate to material
            }
        }
        error("并发轮换代次分配失败，请重试")
    }

    internal suspend fun storeKeyChecked(vault: String, epoch: Int, material: String): Boolean {
        synchronized(keyLock) {
            val existing = keyStore().load(vault, epoch)
            if (existing != null) {
                require(existing.contentEquals(SyncCrypto.parseRecoveryKey(material))) {
                    "该账本代次已有不同密钥，拒绝覆盖"
                }
                return false
            }
            keyStore().store(vault, epoch, material)
            return true
        }
    }

    private suspend fun persist(base: String, credential: SyncDeviceCredential, recoveryBundle: String) {
        val parsed = SyncRecoveryBundle.parse(recoveryBundle)
        require(parsed.vaultId == credential.tenantId) { "恢复包与账本不匹配" }
        storeKeyChecked(credential.tenantId, parsed.epoch, parsed.keyMaterial)
        credentialStore().store(base, credential)
        val previousCursor = db.syncDao().state(credential.tenantId)?.cursor ?: 0L
        db.syncDao().putState(SyncStateEntity(credential.tenantId, credential.deviceId, parsed.epoch, previousCursor, enabled = true))
        prefs.edit().putString(KEY_ENDPOINT, base).putString(KEY_TENANT, credential.tenantId)
            .putBoolean(KEY_ENABLED, true).commit()
        bindSession(base, credential)
    }

    /** The single-use pairing credential another device can use to join this vault (shown by the UI, never logged). */
    suspend fun pairingCredential(): String {
        val base = requireNotNull(endpoint) { "同步未启用" }
        val credential = requireNotNull(credentialOrNull()) { "同步凭据缺失" }
        return wire(base).pairingToken(credential.token)
    }

    suspend fun status(): SyncProductStatus {
        val credential = credentialOrNull()
        val state = activeVault?.let { db.syncDao().state(it) }
        if (credential == null || state == null) {
            return SyncProductStatus(false, null, null, null, 0, 0, null, null)
        }
        return SyncProductStatus(
            active = state.enabled,
            vaultId = credential.tenantId,
            deviceId = credential.deviceId,
            keyEpoch = state.keyEpoch,
            pendingUploads = db.syncDao().pendingCount(credential.tenantId),
            unresolvedConflicts = SyncStatusRepository(db).snapshot(credential.tenantId)?.conflicts ?: 0,
            lastCursor = state.cursor,
            deviceName = credential.deviceId.take(6),
        )
    }

    /** One reconcile: upload outbox (bounded retry) then apply downloaded pages through the business applier. */
    suspend fun syncNow(): SyncCoordinator.Result {
        val credential = requireNotNull(credentialOrNull()) { "同步未启用" }
        val vault = credential.tenantId
        // P2: finish an interrupted rotation before the first reconcile after a restart.
        runCatching { finalizePendingRotation() }
        // Freeze the session for the whole reconcile: a switch/disable during the run invalidates it, and every
        // engine entry point re-checks admission before committing.
        val session = runtime.activeSession?.takeIf { it.vaultId == vault } ?: sessionFor(endpoint!!, credential)
        if (!runtime.isCurrent(session)) throw SyncSessionLostException()
        val engine = SyncEngine(db, wire(session.endpoint)) { runtime.isCurrent(session) }
        val applier = SyncBusinessApplier(
            db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()),
            { System.currentTimeMillis() }, db.syncConflictDao(),
        )
        return SyncVaultLocks.forVault(vault).withLock {
            if (!runtime.isCurrent(session)) throw SyncSessionLostException()
            engine.coordinator(session.keyProvider) { _, bytes -> applier.apply(vault, BusinessCodec.decode(bytes)) }
                .syncOnce(vault, credential.token)
        }.also {
            // P5/G7: apply the tenant role first (it decides whether this device schedules), then re-plan reminders.
            runCatching { syncRoleFromServer() }
            runCatching { afterRemoteApply?.invoke() }
        }
    }

    fun conflictsRepository(): SyncConflictRepository = SyncConflictRepository(
        db, db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(),
        publisher = { v, operation, mutate ->
            val session = runtime.activeSession?.takeIf { it.vaultId == v }
                ?: throw SyncSessionLostException("没有与该账本匹配的活动同步会话")
            SyncVaultLocks.forVault(v).withLock {
                if (!runtime.isCurrent(session)) throw SyncSessionLostException()
                // Keys come from the frozen session, not from whatever vault happens to be active right now (G5).
                SyncEngine(db, wire(session.endpoint)) { runtime.isCurrent(session) }
                    .enqueueBusiness(v, operation, session.keyProvider, mutate)
            }
        },
        afterApply = { runCatching { afterRemoteApply?.invoke() } },
    )

    suspend fun devices(): List<SyncRemoteDevice> {
        val base = requireNotNull(endpoint)
        val credential = requireNotNull(credentialOrNull())
        return wire(base).devices(credential.token)
    }

    suspend fun revoke(deviceId: String) {
        val base = requireNotNull(endpoint)
        val credential = requireNotNull(credentialOrNull())
        wire(base).revoke(credential.token, deviceId)
    }

    /** Rotate to the next key epoch, keeping the previous epoch decryptable for already-received operations. */
    private fun rotationMarkerKey(vault: String, endpoint: String): String = "pendingRotation:$vault:$endpoint"

    /**
     * Q5/P2: an interrupted rotation is bound to (vault, endpoint, epoch). Finalizing only ever advances to a key that was
     * actually committed for THIS session, so a historical/foreign key can never be mistaken for the interrupted rotation.
     */
    internal suspend fun finalizePendingRotation(): Int? {
        val vault = activeVault ?: return null
        val base = endpoint ?: return null
        val marker = rotationMarkerKey(vault, base)
        val pending = prefs.getString(marker, null)?.toIntOrNull() ?: return null
        val state = db.syncDao().state(vault)
        if (state != null && state.keyEpoch >= pending) {
            prefs.edit().remove(marker).commit()
            return null
        }
        val key = keyStore().load(vault, pending) ?: run {
            prefs.edit().remove(marker).commit()
            return null
        }
        require(key.isNotEmpty())
        db.syncDao().advanceEpoch(vault, pending)
        prefs.edit().remove(marker).commit()
        return pending
    }

    /**
     * C/G4 + Q5: rotates to a server-allocated epoch above the caller's committed epoch. If the allocated epoch already
     * has a key file (historical or foreign), the attempt is skipped with a short backoff and another epoch is requested
     * — never reported as success and never overwriting that key.
     */
    suspend fun rotateKey(): Pair<Int, String> {
        val vault = requireNotNull(activeVault) { "同步未启用" }
        val credential = requireNotNull(credentialOrNull()) { "同步凭据缺失" }
        val base = requireNotNull(endpoint) { "缺少同步服务地址" }
        finalizePendingRotation()
        val wire = wire(base)
        val marker = rotationMarkerKey(vault, base)
        return vaultMutex(vault).withLock {
            repeat(5) {
                val state = requireNotNull(db.syncDao().state(vault)) { "同步状态缺失" }
                val epoch = wire.nextEpoch(credential.token, state.keyEpoch)
                if (keyStore().load(vault, epoch) != null) {
                    // Historical/foreign key at this epoch: not a rotation. Back off briefly, then ask for the next epoch.
                    kotlinx.coroutines.delay(150)
                    return@repeat
                }
                prefs.edit().putString(marker, epoch.toString()).commit()
                val material = SyncCrypto.generateRecoveryKey()
                if (runCatching { storeKeyChecked(vault, epoch, material) }.getOrDefault(false)) {
                    db.syncDao().advanceEpoch(vault, epoch)
                    prefs.edit().remove(marker).commit()
                    return@withLock epoch to SyncRecoveryBundle.create(vault, epoch, material)
                }
                prefs.edit().remove(marker).commit()
            }
            error("轮换代次分配失败，请重试")
        }
    }

    /** Stop syncing on this device: new writes stop going to the outbox; local state/keys are kept for re-enable. */
    suspend fun disable() {
        // P3: re-disabling never resets a baseline; pending edit rows are the persistent truth and stay untouched.
        prefs.edit().putLong(KEY_DISABLED_AT, System.currentTimeMillis()).commit()
        val vault = activeVault
        if (vault != null) {
            val gen = bumpGeneration()
            runtime.bindOffline(OfflineSyncWriteGateway(db, vault, admission = { bindingGeneration == gen }))
        } else {
            bumpGeneration()
            runtime.reset()
        }
        prefs.edit().putBoolean(KEY_ENABLED, false).commit()
        activeVault?.let { vault -> db.syncDao().setEnabled(vault, false) }
    }

    suspend fun reenable() {
        val credential = credentialOrNull() ?: return
        val vault = credential.tenantId
        db.syncDao().setEnabled(vault, true)
        prefs.edit().putBoolean(KEY_ENABLED, true).commit()
        bindSession(requireNotNull(endpoint) { "缺少同步服务地址" }, credential)
    }

    /**
     * F8 initial merge: what would be uploaded from the existing local ledger when this device joins a vault. The UI
     * shows these counts for explicit confirmation before anything leaves the device.
     */
    suspend fun initialMergePreview(): Map<String, Int> = mapOf(
        "subscriptions" to db.subscriptionDao().count(),
        "payments" to db.paymentRecordDao().all().size,
        "categories" to db.categoryDao().count(),
    )

    /** Explicitly enqueue the existing local ledger as business operations (initial merge). Requires an active vault. */
    suspend fun publishInitialSnapshot(): Map<String, Int> {
        val vault = requireNotNull(activeVault) { "同步未启用" }
        val credential = requireNotNull(credentialOrNull()) { "同步凭据缺失" }
        val session = runtime.activeSession?.takeIf { it.vaultId == vault } ?: sessionFor(endpoint!!, credential)
        val gateway = OutboxSyncWriteGateway(
            vault, credential.deviceId, SyncHeadStore(db.syncHeadDao()),
            SyncEngine(db, wire(session.endpoint)) { runtime.isCurrent(session) },
            session.keyProvider, admission = { runtime.isCurrent(session) },
        )
        var subscriptions = 0
        for (id in db.subscriptionDao().allIds()) {
            val entity = db.subscriptionDao().findById(id) ?: continue
            gateway.subscription(entity, entity.deletedAtUtcMillis != null) { }
            subscriptions++
        }
        var payments = 0
        for (entity in db.paymentRecordDao().all()) {
            gateway.payment(entity) { }
            payments++
        }
        var categories = 0
        for (entity in db.categoryDao().all()) {
            gateway.category(entity) { }
            categories++
        }
        return mapOf("subscriptions" to subscriptions, "payments" to payments, "categories" to categories)
    }

    data class DisabledPreview(val counts: Map<String, Int>, val token: String, val queued: Int)

    private fun disabledEdits() = db.disabledEditDao()
    private fun nowMillis() = System.currentTimeMillis()

    private suspend fun allPendingRows(vault: String): List<DisabledEditEntity> = disabledEdits().pendingRows(vault)

    private suspend fun pendingConfirmRows(vault: String): List<DisabledEditEntity> = allPendingRows(vault)
        .filter { it.queuedAt == null && it.confirmedAt == null }
        .groupBy { it.entityType to it.entityId }
        .map { (_, rows) -> rows.maxBy { it.seq } }
        .sortedBy { it.seq }

    private suspend fun pendingRecoveryRows(vault: String): List<DisabledEditEntity> =
        allPendingRows(vault).filter { it.publishedAt == null && it.queuedAt != null }

    /**
     * S4: digest is the hash of the REAL serialized operation payload with canonical metadata (fixed ids/clock), so it
     * covers exactly the fields BusinessCodec transmits (note, payment method, custom cycle, dates, category fields …)
     * and cannot suffer from delimiter-collision. No dependency on updatedAt.
     */
    private suspend fun entityDigest(type: String, id: String): String {
        val canonicalDevice = "canonical_device"
        val canonicalOp = "op_canonical_0000000001"
        val canonicalClock = mapOf(canonicalDevice to 1L)
        val operation = when (type) {
            "subscription" -> db.subscriptionDao().findById(id)?.let {
                BusinessOperation.SubscriptionUpsert(canonicalOp, canonicalDevice, canonicalClock,
                    it.deletedAtUtcMillis != null, it)
            }
            "payment" -> db.paymentRecordDao().findById(id)?.let {
                BusinessOperation.PaymentUpsert(canonicalOp, canonicalDevice, canonicalClock, false, it)
            }
            "category" -> db.categoryDao().findById(id)?.let {
                BusinessOperation.CategoryUpsert(canonicalOp, canonicalDevice, canonicalClock, false, it)
            }
            else -> null
        } ?: return "missing"
        val bytes = runCatching { BusinessCodec.encode(operation) }.getOrElse { return "invalid" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun batchToken(rows: List<DisabledEditEntity>): String {
        val parts = ArrayList<String>(rows.size)
        for (row in rows) {
            parts += "${row.seq}:${row.opId}:${row.entityType}:${row.entityId}:${entityDigest(row.entityType, row.entityId)}"
        }
        val material = parts.joinToString(",")
        return java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    /** R3: preview counts + token come from the SAME consistent snapshot. */
    suspend fun previewDisabledBatch(): DisabledPreview {
        val vault = activeVault ?: return DisabledPreview(emptyMap(), "", 0)
        // S4: rows, content digests, token and queued count all come from ONE Room snapshot.
        return db.withTransaction {
            val confirm = pendingConfirmRows(vault)
            DisabledPreview(
                counts = confirm.groupingBy { it.entityType }.eachCount(),
                token = batchToken(confirm),
                queued = pendingRecoveryRows(vault).size,
            )
        }
    }

    suspend fun disabledEditPreview(): Map<String, Int> = previewDisabledBatch().counts
    suspend fun disabledPreviewBatchToken(): String = previewDisabledBatch().token
    suspend fun queuedDisabledEditCount(): Int = previewDisabledBatch().queued

    /**
     * R2: startup recovery. A frozen confirmation exists only in memory, so any row marked confirmed but never queued is
     * safely returned to "awaiting confirmation" (the user re-confirms exactly what is still there). Rows queued but not
     * marked published complete their bookkeeping without a new envelope.
     */
    suspend fun recoverInterruptedPublish() {
        val vault = activeVault ?: return
        val rows = allPendingRows(vault)
        for (row in rows.filter { it.confirmedAt != null && it.queuedAt == null && it.publishedAt == null }) {
            disabledEdits().clearConfirmed(listOf(row.seq))
        }
        for (row in rows.filter { it.publishedAt == null && it.queuedAt != null }) {
            disabledEdits().markPublished(row.seq, nowMillis())
        }
    }

    private class FrozenEdit(
        val seq: Long, val opId: String, val type: String, val entityId: String,
        val operation: BusinessOperation, val deleted: Boolean,
    )

    /**
     * R1/R2/R3: publish the confirmed batch. Token validation, content capture and confirmation marking happen INSIDE the
     * vault lock in one Room transaction; actual enqueue/ledger/head/marker commits happen in per-row transactions whose
     * admission is validated inside those transactions. Cancellation or failure returns unqueued rows to "pending".
     */
    suspend fun publishDisabledEdits(expectedBatchToken: String? = null): Map<String, Int> {
        val vault = requireNotNull(activeVault) { "同步未启用" }
        val credential = requireNotNull(credentialOrNull()) { "同步凭据缺失" }
        val base = requireNotNull(endpoint) { "缺少同步服务地址" }
        if (sessionIdentity == null) sessionIdentity = sessionFor(base, credential)
        val session = sessionIdentity ?: throw SyncSessionLostException("没有可用的同步会话")
        suspend fun admission(): Boolean = sessionIdentity === session
        if (!admission()) throw SyncSessionLostException("同步会话已切换，无法确认发布")
        val engine = SyncEngine(db, wire(base)) { admission() }
        val heads = SyncHeadStore(db.syncHeadDao())
        return SyncVaultLocks.forVault(vault).withLock {
            if (!admission()) throw SyncSessionLostException("等待发布锁期间会话已切换，本次确认未提交")
            val frozen: List<FrozenEdit> = db.withTransaction {
                if (!admission()) throw SyncSessionLostException()
                val rows = pendingConfirmRows(vault)
                val token = batchToken(rows)
                if (expectedBatchToken != null && token != expectedBatchToken) {
                    throw IllegalStateException("待确认内容已变化，请重新预览并确认")
                }
                val captured = rows.mapNotNull { row ->
                    when (row.entityType) {
                        "category" -> db.categoryDao().findById(row.entityId)?.let {
                            FrozenEdit(row.seq, row.opId, row.entityType, row.entityId,
                                BusinessOperation.CategoryUpsert(row.opId, session.deviceId, emptyMap(), false, it), false)
                        }
                        "subscription" -> db.subscriptionDao().findById(row.entityId)?.let {
                            FrozenEdit(row.seq, row.opId, row.entityType, row.entityId,
                                BusinessOperation.SubscriptionUpsert(row.opId, session.deviceId, emptyMap(),
                                    it.deletedAtUtcMillis != null, it), it.deletedAtUtcMillis != null)
                        }
                        "payment" -> db.paymentRecordDao().findById(row.entityId)?.let {
                            FrozenEdit(row.seq, row.opId, row.entityType, row.entityId,
                                BusinessOperation.PaymentUpsert(row.opId, session.deviceId, emptyMap(), false, it), false)
                        }
                        else -> null
                    }
                }
                require(captured.size == rows.size) { "部分确认内容已不存在，请重新预览确认" }
                captured.forEach { disabledEdits().markConfirmed(it.seq, nowMillis()) }
                captured
            }
            val queued = mutableSetOf<Long>()
            try {
                val counts = mutableMapOf<String, Int>()
                for (item in frozen) {
                    if (!admission()) throw SyncSessionLostException()
                    val state = requireNotNull(db.syncDao().state(vault)) { "同步状态缺失" }
                    val clock = heads.nextClock(vault, item.type, item.entityId, session.deviceId)
                    val operation = when (item.operation) {
                        is BusinessOperation.CategoryUpsert -> item.operation.copy(clock = clock)
                        is BusinessOperation.SubscriptionUpsert -> item.operation.copy(clock = clock)
                        is BusinessOperation.PaymentUpsert -> item.operation.copy(clock = clock)
                        else -> throw IllegalStateException("unsupported operation")
                    }
                    val key = session.keyProvider(state.keyEpoch)
                    val envelope = try {
                        SyncCrypto.encrypt(key, vault, item.opId, state.keyEpoch, BusinessCodec.encode(operation))
                    } finally {
                        key.fill(0)
                    }
                    if (db.syncDao().outboxHas(item.opId) > 0) {
                        disabledEdits().markQueued(item.seq, nowMillis())
                        disabledEdits().markPublished(item.seq, nowMillis())
                        queued += item.seq
                        continue
                    }
                    engine.enqueueEncrypted(vault, item.opId, envelope, allowDisabled = true) {
                        heads.integrate(vault, item.type, item.entityId, CausalVersion(item.opId, clock, item.deleted))
                        disabledEdits().markQueued(item.seq, nowMillis())
                    }
                    queued += item.seq
                    disabledEdits().markPublished(item.seq, nowMillis())
                    counts[item.type] = (counts[item.type] ?: 0) + 1
                }
                for (row in pendingRecoveryRows(vault)) {
                    if (admission()) disabledEdits().markPublished(row.seq, nowMillis())
                }
                counts
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    disabledEdits().clearConfirmed(frozen.map { it.seq }.filter { it !in queued })
                }
                throw e
            } catch (e: Exception) {
                disabledEdits().clearConfirmed(frozen.map { it.seq }.filter { it !in queued })
                throw e
            }
        }
    }

    private suspend fun categorySnapshotJson(): String =
        db.categoryDao().all().sortedBy { it.id }
            .joinToString("\n") { "${it.id}|${it.name}|${it.iconKey}|${it.colorArgb}|${it.isBuiltIn}|${it.sortOrder}" }

    /**
     * Q4: the previous schema-4 app identified unpublished disabled edits through prefs (publishFloorMillis /
     * categorySnapshot). On the first start after upgrade, translate them into the persistent edit log exactly once.
     * Idempotent (existing pending rows are reused), never uploads anything by itself.
     */
    suspend fun migrateLegacyDisabledState() {
        if (prefs.getBoolean("legacyDisabledEditMigrated", false)) return
        val vault = activeVault ?: return
        val floor = prefs.getLong("publishFloorMillis", 0L)
        if (floor <= 0L) {
            prefs.edit().putBoolean("legacyDisabledEditMigrated", true).commit()
            return
        }
        val candidates = mutableListOf<Triple<String, String, Long>>()
        for (id in db.subscriptionDao().allIds()) {
            val row = db.subscriptionDao().findById(id) ?: continue
            val stamp = maxOf(row.updatedAtUtcMillis, row.deletedAtUtcMillis ?: 0L)
            if (stamp >= floor) candidates += Triple("subscription", id, stamp)
        }
        for (payment in db.paymentRecordDao().all()) {
            if (payment.createdAtUtcMillis >= floor) candidates += Triple("payment", payment.id, payment.createdAtUtcMillis)
        }
        val snapshot = prefs.getString("categorySnapshot", null)
        if (snapshot != null && snapshot != categorySnapshotJson()) {
            db.categoryDao().all().forEach { candidates += Triple("category", it.id, 0L) }
        }
        db.withTransaction {
            for ((type, id, stamp) in candidates.distinctBy { it.first to it.second }) {
                if (disabledEdits().pendingFor(vault, type, id).isEmpty()) {
                    disabledEdits().insert(
                        DisabledEditEntity(
                            vaultId = vault, entityType = type, entityId = id,
                            opId = newDisabledEditOpId(), createdAtMillis = maxOf(stamp, floor),
                        ),
                    )
                }
            }
        }
        prefs.edit().putBoolean("legacyDisabledEditMigrated", true).commit()
    }

    // P5: the role lives in tenant-scoped metadata and is propagated through the normal authenticated sync.
    private fun roleKey(vault: String, name: String) = "role-$name-$vault"
    private fun readRoleDevice(vault: String): String? = prefs.getString(roleKey(vault, "device"), null)?.takeIf { it.isNotBlank() }
    private fun readRoleVersion(vault: String): Int = prefs.getInt(roleKey(vault, "version"), 0)

    val designatedReminderDeviceId: String? get() = activeVault?.let { readRoleDevice(it) }

    fun reminderRoleVersion(): Int = activeVault?.let { readRoleVersion(it) } ?: 0

    /** "已同步" / "待同步"（本地决定尚未被服务端确认）/ "冲突"（服务端已有更高版本，已按服务端收敛）。 */
    fun reminderRoleSyncState(): String {
        val vault = activeVault ?: return "未启用同步"
        val state = prefs.getString(roleKey(vault, "state"), "synced") ?: "synced"
        return when (state) {
            "pending" -> "待同步"
            "conflict" -> "已按服务端版本收敛（存在并发决定）"
            else -> "已同步"
        }
    }

    fun reminderRole(): String =
        com.subtrack.domain.model.SyncReminderRole.roleLabel(runtime.activeSession?.deviceId, designatedReminderDeviceId)

    fun shouldScheduleReminders(): Boolean =
        com.subtrack.domain.model.SyncReminderRole.isReminderDevice(runtime.activeSession?.deviceId, designatedReminderDeviceId)

    var afterReminderRoleChanged: (suspend () -> Unit)? = null
    var afterRemoteApply: (suspend () -> Unit)? = null

    private fun applyRoleLocally(vault: String, deviceId: String?, version: Int, state: String) {
        prefs.edit()
            .putString(roleKey(vault, "device"), deviceId)
            .putInt(roleKey(vault, "version"), version)
            .putString(roleKey(vault, "state"), state)
            .commit()
    }

    /**
     * P5: the single product entry point for designating the reminder device. It applies locally, then publishes the
     * decision as versioned tenant metadata. On a version conflict the newer server value wins and is applied locally;
     * on a network failure the decision stays local and is shown as "待同步" (the next sync retries the push).
     */
    suspend fun setDesignatedReminderDevice(deviceId: String?) {
        val vault = activeVault
        if (vault == null) {
            prefs.edit().putString(KEY_DESIGNATED_REMINDER, deviceId?.trim()?.takeIf { it.isNotEmpty() }).commit()
            afterReminderRoleChanged?.invoke()
            return
        }
        val localVersion = readRoleVersion(vault)
        applyRoleLocally(vault, deviceId, localVersion, "pending")
        afterReminderRoleChanged?.invoke()
        runCatching { pushRole(vault, deviceId) }
    }

    private suspend fun pushRole(vault: String, deviceId: String?): Boolean {
        val base = requireNotNull(endpoint) { "缺少同步服务地址" }
        val credential = requireNotNull(credentialOrNull()) { "同步凭据缺失" }
        return try {
            val remote = wire(base).setRole(credential.token, deviceId, readRoleVersion(vault))
            applyRoleLocally(vault, remote.deviceId, remote.version, "synced")
            afterReminderRoleChanged?.invoke()
            true
        } catch (e: Exception) {
            if (e.message?.contains("409") == true) {
                val remote = wire(base).role(credential.token)
                applyRoleLocally(vault, remote.deviceId, remote.version, "conflict")
                afterReminderRoleChanged?.invoke()
                false
            } else {
                applyRoleLocally(vault, deviceId, readRoleVersion(vault), "pending")
                false
            }
        }
    }

    /** P5: pull the tenant role during normal sync; a newer version overrides the local view and re-plans reminders. */
    suspend fun syncRoleFromServer() {
        val vault = activeVault ?: return
        val base = requireNotNull(endpoint) { "缺少同步服务地址" }
        val credential = requireNotNull(credentialOrNull()) { "同步凭据缺失" }
        val local = readRoleVersion(vault)
        val state = prefs.getString(roleKey(vault, "state"), "synced")
        val remote = wire(base).role(credential.token)
        if (remote.version > local) {
            applyRoleLocally(vault, remote.deviceId, remote.version, "synced")
            afterReminderRoleChanged?.invoke()
        } else if (state == "pending" && remote.version == local) {
            // Retry a pending local decision (same base version, no concurrent winner yet).
            pushRole(vault, designatedReminderDeviceId)
        }
    }

    /** P3: the product category-write entry point; goes through the active/offline gateway so disabled edits are recorded. */
    suspend fun upsertCategory(entity: com.subtrack.data.db.CategoryEntity) {
        runtime.category(entity) { db.categoryDao().upsert(entity) }
    }

    internal fun newOperationId(): String = "op_" + UUID.randomUUID().toString().replace("-", "")

    /**
     * S3: construction hook lives at the END of the class so every field initializer above has run before the binding is
     * restored — otherwise the late `sessionIdentity = null` / `bindingGeneration = 0` initializers would silently wipe
     * the identity created during construction.
     */
    init {
        restoreBinding()
    }
}
