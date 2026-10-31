package com.subtrack.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.subtrack.sync.DisabledEditDao
import com.subtrack.sync.DisabledEditEntity
import com.subtrack.sync.SyncConflictDao
import com.subtrack.sync.SyncConflictEntity
import com.subtrack.sync.SyncDao
import com.subtrack.sync.SyncHeadDao
import com.subtrack.sync.SyncHeadEntity
import com.subtrack.sync.SyncOutboxEntity
import com.subtrack.sync.SyncReceivedEntity
import com.subtrack.sync.SyncStateEntity

@Database(
    entities = [
        SubscriptionEntity::class,
        CategoryEntity::class,
        PaymentRecordEntity::class,
        ScheduledReminderEntity::class,
        PriceHistoryEntity::class,
        MetricsEventEntity::class,
        SyncStateEntity::class,
        SyncOutboxEntity::class,
        SyncReceivedEntity::class,
        SyncHeadEntity::class,
        SyncConflictEntity::class,
        DisabledEditEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class SubTrackDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun paymentRecordDao(): PaymentRecordDao
    abstract fun reminderDao(): ReminderDao
    abstract fun syncDao(): SyncDao
    abstract fun disabledEditDao(): DisabledEditDao
    abstract fun syncHeadDao(): SyncHeadDao
    abstract fun syncConflictDao(): SyncConflictDao

    companion object {
        /** All migrations in order, shared by production and the schema-fixture migration tests. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_state` (`vaultId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `keyEpoch` INTEGER NOT NULL, `cursor` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`vaultId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`vaultId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `keyEpoch` INTEGER NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, `createdOrder` INTEGER NOT NULL, PRIMARY KEY(`vaultId`, `operationId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_vaultId_createdOrder` ON `sync_outbox` (`vaultId`, `createdOrder`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_received` (`vaultId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `digest` TEXT NOT NULL, `serverRevision` INTEGER NOT NULL, PRIMARY KEY(`vaultId`, `operationId`))")
            }
        }

        /** v3: persisted causal heads so concurrent-version conflicts survive process death. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_entity_head` (`vaultId` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `headId` TEXT NOT NULL, `clockCsv` TEXT NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`vaultId`, `entityType`, `entityId`, `headId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_entity_head_vaultId_entityType_entityId` ON `sync_entity_head` (`vaultId`, `entityType`, `entityId`)")
            }
        }
        /** v4: keep un-applied concurrent remote operations so users can resolve conflicts by keeping the remote side. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_disabled_edit ADD COLUMN confirmedAt INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // P1/P3/P4: disabled-period edit log. Additive only: ledger/outbox/heads/conflicts are untouched.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_disabled_edit (" +
                        "seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, vaultId TEXT NOT NULL, " +
                        "entityType TEXT NOT NULL, entityId TEXT NOT NULL, opId TEXT NOT NULL, " +
                        "createdAtMillis INTEGER NOT NULL, queuedAt INTEGER, publishedAt INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_disabled_edit_vaultId ON sync_disabled_edit(vaultId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_disabled_edit_opId ON sync_disabled_edit(opId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_conflict` (`vaultId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `payload` BLOB NOT NULL, PRIMARY KEY(`vaultId`, `operationId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_conflict_vaultId_entityType_entityId` ON `sync_conflict` (`vaultId`, `entityType`, `entityId`)")
            }
        }
        /** All migrations in order, shared by production and the schema-fixture migration tests. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        @Volatile
        private var instance: SubTrackDatabase? = null

        fun get(context: Context): SubTrackDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SubTrackDatabase::class.java,
                "subtrack.db",
            ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
        }
    }
}
