package com.subtrack

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.subtrack.data.db.SubTrackDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * S-migration: real non-empty schema-4 and schema-5 databases are materialized from the ACCEPTANCE-VERIFIED schema
 * fixtures (assets copied from app/schemas), then upgraded through Room's production migrations. Ledger, outbox, heads,
 * conflicts, cursor and sync_state must survive; the upgraded DB must be usable.
 */
class SchemaFixtureMigrationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun schemaJson(version: Int): JSONObject {
        val asset = InstrumentationRegistry.getInstrumentation().context.assets.open("com.subtrack.data.db.SubTrackDatabase/$version.json")
        return JSONObject(asset.bufferedReader().readText())
    }

    /** Fills unspecified NOT NULL columns with type-appropriate defaults so the fixture row is valid for its schema. */
    private fun insertRow(db: SQLiteDatabase, table: String, overrides: Map<String, Any?>) {
        val columns = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val type = cursor.getString(2) ?: ""
                val notNull = cursor.getInt(3) != 0
                val pk = cursor.getInt(5) != 0
                if (pk && type.uppercase().contains("INT")) continue   // AUTOINCREMENT primary key
                columns += name
                values += if (overrides.containsKey(name)) overrides[name]
                else when {
                    !notNull -> null
                    type.contains("INT", ignoreCase = true) -> 1
                    type.contains("REAL", ignoreCase = true) -> 1.0
                    type.contains("BLOB", ignoreCase = true) -> ByteArray(1)
                    else -> "x"
                }
            }
        }
        val placeholders = columns.joinToString(",") { "?" }
        db.execSQL("INSERT INTO `$table` (${columns.joinToString(",")}) VALUES ($placeholders)", values.toTypedArray())
    }

    private fun materialize(version: Int): String {
        val name = "fixture-v$version-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).absolutePath
        context.getDatabasePath(name).parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        val schema = schemaJson(version).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        insertRow(db, "subscription", mapOf("id" to "fixture_sub", "name" to "Fixture", "categoryId" to "other",
            "billingCycleType" to "MONTHLY", "anchorDateEpochDay" to 1, "priceMinor" to 1000, "status" to "ACTIVE",
            "createdAtUtcMillis" to 1, "updatedAtUtcMillis" to 2000))
        insertRow(db, "sync_state", mapOf("vaultId" to "fixture_vault", "deviceId" to "dev", "keyEpoch" to 1,
            "cursor" to 17, "enabled" to 0))
        insertRow(db, "sync_outbox", mapOf("vaultId" to "fixture_vault", "operationId" to "op_fixture_000000001",
            "keyEpoch" to 1, "createdOrder" to 1))
        insertRow(db, "sync_entity_head", mapOf("vaultId" to "fixture_vault", "entityType" to "subscription",
            "entityId" to "fixture_sub", "headId" to "op_fixture_000000001", "clockCsv" to "dev:1"))
        insertRow(db, "sync_conflict", mapOf("vaultId" to "fixture_vault", "operationId" to "op_fixture_000000002",
            "entityType" to "subscription", "entityId" to "fixture_sub", "payload" to ByteArray(1)))
        db.version = version
        db.close()
        return name
    }

    private fun migrateAndAssert(version: Int) = runBlocking {
        val name = materialize(version)
        val upgraded = Room.databaseBuilder(context, SubTrackDatabase::class.java, name)
            .addMigrations(*SubTrackDatabase.ALL_MIGRATIONS)
            .build()
        try {
            assertEquals("Fixture", upgraded.subscriptionDao().findById("fixture_sub")!!.name)
            val state = upgraded.syncDao().state("fixture_vault")!!
            assertEquals(17, state.cursor)
            assertFalse(state.enabled)
            assertEquals(1, upgraded.syncDao().pending("fixture_vault", 10).size)
            assertEquals(1, upgraded.syncHeadDao().heads("fixture_vault", "subscription", "fixture_sub").size)
            assertEquals(1, upgraded.syncConflictDao().countForVault("fixture_vault"))
            // The upgraded schema must accept new writes (e.g. the version-6 confirmation column).
            upgraded.syncDao().putState(state.copy(keyEpoch = 2))
            assertEquals(2, upgraded.syncDao().state("fixture_vault")!!.keyEpoch)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun schema4_fixture_upgrades_to_current_with_data_preserved() = runBlocking<Unit> {
        migrateAndAssert(4)
        Unit
    }

    @Test fun schema5_fixture_upgrades_to_current_with_data_preserved() = runBlocking<Unit> {
        migrateAndAssert(5)
        Unit
    }
}
