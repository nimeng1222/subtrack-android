package com.subtrack

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SyncStorageDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_test"
    private val device = "device_test"
    private val id = "operation_0000000001"
    private fun sub(name: String) = SubscriptionEntity("sync-fixture", name, "other", billingCycleType = "MONTHLY",
        anchorDateEpochDay = 100, nextChargeDateEpochDay = 200, priceMinor = 1000, status = "ACTIVE",
        createdAtUtcMillis = 1, updatedAtUtcMillis = 1)

    @Test fun sync_outbox_and_ledger_commit_or_rollback_together() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.syncDao().putState(SyncStateEntity(vault, device, 1, 0, true))
            val key = SyncCrypto.parseRecoveryKey(SyncCrypto.generateRecoveryKey())
            val encrypted = SyncCrypto.encrypt(key, vault, id, 1, "fixture".toByteArray())
            val transactions = SyncTransactions(db)
            transactions.enqueue(vault, id, encrypted) { db.subscriptionDao().upsert(sub("first")) }
            assertEquals("first", db.subscriptionDao().findById("sync-fixture")!!.name)
            assertEquals(1, db.syncDao().pending(vault, 10).size)
            try { transactions.enqueue(vault, id, encrypted) { db.subscriptionDao().upsert(sub("must roll back")) }; fail() }
            catch (_: Exception) { }
            assertEquals("first", db.subscriptionDao().findById("sync-fixture")!!.name)
            assertEquals(1, db.syncDao().pending(vault, 10).size)
            transactions.acknowledge(vault, listOf(id)); transactions.acknowledge(vault, listOf(id))
            assertTrue(db.syncDao().pending(vault, 10).isEmpty())
        } finally { db.close() }
    }

    @Test fun sync_page_checkpoint_follows_atomic_application_and_rejects_wrong_vault() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.syncDao().putState(SyncStateEntity(vault, device, 1, 0, true))
            val key = SyncCrypto.parseRecoveryKey(SyncCrypto.generateRecoveryKey())
            val first = RemoteOperation(1, id, SyncCrypto.encrypt(key, vault, id, 1, byteArrayOf(1)))
            val id2 = "operation_0000000002"
            val second = RemoteOperation(2, id2, SyncCrypto.encrypt(key, vault, id2, 1, byteArrayOf(2)))
            val transactions = SyncTransactions(db)
            try { transactions.applyPage(vault, 0, listOf(first, second), { key.copyOf() }) { item, _ ->
                if (item.revision == 1L) db.subscriptionDao().upsert(sub("must roll back")) else error("interrupted")
            }; fail() } catch (_: IllegalStateException) { }
            assertNull(db.subscriptionDao().findById("sync-fixture"))
            assertNull(db.syncDao().received(vault, id))
            assertEquals(0L, db.syncDao().state(vault)!!.cursor)
            transactions.applyPage(vault, 0, listOf(first), { key.copyOf() }) { _, _ -> db.subscriptionDao().upsert(sub("applied")) }
            assertEquals(1L, db.syncDao().state(vault)!!.cursor)
            transactions.applyPage(vault, 1, listOf(second), { key.copyOf() }) { _, _ -> }
            assertEquals(2L, db.syncDao().state(vault)!!.cursor)
            assertNotNull(runCatching { transactions.applyPage("other_vault", 0, listOf(first), { key.copyOf() }) { _, _ -> } }.exceptionOrNull())
            assertEquals(2L, db.syncDao().state(vault)!!.cursor)
        } finally { db.close() }
    }

    @Test fun sync_key_storage_is_vault_and_epoch_bound() {
        val store = SyncKeyStore(context)
        val recovery = SyncCrypto.generateRecoveryKey()
        try {
            store.store(vault, 1, recovery)
            assertArrayEquals(SyncCrypto.parseRecoveryKey(recovery), store.load(vault, 1))
            assertNull(store.load("other_vault", 1)); assertNull(store.load(vault, 2))
            store.remove(vault, 1); assertNull(store.load(vault, 1))
        } finally { store.remove(vault, 1) }
    }

    @Test fun sync_bearer_credential_is_encrypted_and_bound_to_endpoint_and_tenant() {
        val store = SyncCredentialStore(context)
        val credential = SyncDeviceCredential(vault, device, "synthetic-token-for-tests-123456789012345")
        try {
            store.store("https://a.example", credential)
            assertEquals(credential, store.load("https://a.example", vault))
            assertNull(store.load("https://b.example", vault))
            assertNull(store.load("https://a.example", "another_vault"))
            assertFalse(credential.toString().contains(credential.token))
            store.remove(vault); assertNull(store.load("https://a.example", vault))
        } finally { store.remove(vault) }
    }

    @Test fun migration_v1_to_v2_preserves_nonempty_payments_and_tombstones() = runBlocking {
        val name = "sync-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val source = JSONObject(Round2Instrumentation.instance.context.assets.open("sync-v1-schema.json").bufferedReader().readText())
            .getJSONObject("database")
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = source.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = source.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL("INSERT INTO subscription(id,name,categoryId,billingCycleType,anchorDateEpochDay,nextChargeDateEpochDay,priceMinor,currency,status,autoRenew,createdAtUtcMillis,updatedAtUtcMillis,deletedAtUtcMillis) VALUES('deleted-fixture','deleted','other','MONTHLY',100,200,1500,'USD','ACTIVE',1,10,20,30)")
            sqlite.execSQL("INSERT INTO payment_record(id,subscriptionId,paidAtEpochDay,amountMinor,currency,source,createdAtUtcMillis) VALUES('payment-fixture','deleted-fixture',200,1500,'USD','USER_CONFIRMED',20)")
            sqlite.version = 1
        } finally { sqlite.close() }
        val db = Room.databaseBuilder(context, SubTrackDatabase::class.java, name).addMigrations(SubTrackDatabase.MIGRATION_1_2, SubTrackDatabase.MIGRATION_2_3, SubTrackDatabase.MIGRATION_3_4, SubTrackDatabase.MIGRATION_4_5, SubTrackDatabase.MIGRATION_5_6).build()
        try {
            val migrated = db.subscriptionDao().findById("deleted-fixture")!!
            assertEquals(30L, migrated.deletedAtUtcMillis)
            assertEquals("USD", migrated.currency); assertEquals(1500L, migrated.priceMinor)
            assertEquals(1500L, db.paymentRecordDao().bySubscription("deleted-fixture").single().amountMinor)
            assertTrue(db.syncDao().pending("test", 10).isEmpty())
            // v2→v3 adds the persisted causal-head table; v3→v4 adds the conflict table; existing rows untouched.
            assertEquals(0, db.syncHeadDao().countForVault("test"))
            assertEquals(0, db.syncConflictDao().countForVault("test"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
