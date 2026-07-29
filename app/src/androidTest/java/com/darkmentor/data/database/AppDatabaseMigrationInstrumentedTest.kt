package com.darkmentor.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun removeTestDatabase() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrate28To29_removesOnlyRetiredDeviceColumns_andPreservesAllTableData() {
        createVersion28Database()

        val room = AppDatabase.build(context, TEST_DATABASE)
        try {
            val database = room.openHelper.writableDatabase
            assertMigratedData(database)
        } finally {
            room.close()
        }
    }

    /**
     * Build a schema-valid v29 database, then synthesize v28 by restoring the only two columns
     * that differ and its version/identity. Opening it through [AppDatabase.build] below runs the
     * exact migration and Room's production schema validator without depending on Room's
     * separate schema-JSON parser in the instrumentation APK.
     */
    private fun createVersion28Database() {
        val currentDatabase = AppDatabase.build(context, TEST_DATABASE)
        try {
            currentDatabase.openHelper.writableDatabase
        } finally {
            currentDatabase.close()
        }
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(
                "ALTER TABLE device ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL("ALTER TABLE device ADD COLUMN metadata TEXT")
            database.execSQL("PRAGMA user_version = 28")
            database.execSQL(
                "UPDATE room_master_table SET identity_hash = ? WHERE id = 42",
                arrayOf(VERSION_28_IDENTITY_HASH),
            )
            insertVersion28Sentinels(database)
        }
    }

    private fun assertMigratedData(database: SupportSQLiteDatabase) {
        val deviceColumns = database.query("PRAGMA table_info(`device`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertFalse(deviceColumns.contains("favorite"))
        assertFalse(deviceColumns.contains("metadata"))
        assertEquals(EXPECTED_DEVICE_COLUMNS, deviceColumns)

        database.query("SELECT * FROM `device` WHERE address = ?", arrayOf(DEVICE_ADDRESS))
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Sentinel", cursor.string("name"))
                assertEquals(222L, cursor.long("last_detect_time_ms"))
                assertEquals(111L, cursor.long("first_detect_time_ms"))
                assertEquals(7, cursor.int("detect_count"))
                assertEquals("Custom", cursor.string("custom_name"))
                assertEquals(76, cursor.int("manufacturer_id"))
                assertEquals("Apple", cursor.string("manufacturer_name"))
                assertEquals(-48, cursor.int("last_seen_rssi"))
                assertEquals(1, cursor.int("system_address_type"))
                assertEquals(1024, cursor.int("device_class"))
                assertEquals(1, cursor.int("is_paired"))
                assertEquals("uuid-a,uuid-b", cursor.string("service_uuids"))
                assertEquals("raw-row", cursor.string("row_data_encoded"))
                assertEquals(1, cursor.int("is_connectable"))
                assertEquals(2, cursor.int("transport"))
                assertEquals("sdp-a", cursor.string("sdp_uuids"))
                assertEquals("Gatt Maker", cursor.string("gatt_manufacturer_name"))
                assertFalse(cursor.moveToNext())
            }

        assertEquals(
            listOf("index_device_last_detect_time_ms"),
            database.query("PRAGMA index_list(`device`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.startsWith("sqlite_autoindex_")) add(name)
                    }
                }
            },
        )

        assertEquals(1, database.rowCount("apple_contacts"))
        assertEquals(1, database.rowCount("location"))
        assertEquals(1, database.rowCount("device_to_location"))
        assertEquals(1, database.rowCount("journal"))
        assertEquals(1, database.rowCount("captured_advert_fingerprint"))
        assertEquals(1, database.rowCount("btidalpool_upload_outbox"))
        assertEquals(
            "payload-path",
            database.query("SELECT payload_path FROM btidalpool_upload_outbox")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                },
        )
    }

    private fun insertVersion28Sentinels(database: SQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO device (
                address, name, last_detect_time_ms, first_detect_time_ms, detect_count,
                custom_name, favorite, manufacturer_id, manufacturer_name, last_seen_rssi,
                system_address_type, device_class, is_paired, service_uuids, row_data_encoded,
                metadata, is_connectable, transport, sdp_uuids, gatt_manufacturer_name
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                DEVICE_ADDRESS, "Sentinel", 222L, 111L, 7, "Custom", 1, 76, "Apple", -48,
                1, 1024, 1, "uuid-a,uuid-b", "raw-row", "retired-metadata", 1, 2, "sdp-a",
                "Gatt Maker",
            ),
        )
        database.execSQL(
            "INSERT INTO apple_contacts VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(1234L, DEVICE_ADDRESS, 111L, 222L),
        )
        database.execSQL(
            "INSERT INTO location VALUES (?, ?, ?)",
            arrayOf<Any?>(111L, 40.0, -74.0),
        )
        database.execSQL(
            "INSERT INTO device_to_location (device_address, location_time, rssi) VALUES (?, ?, ?)",
            arrayOf<Any?>(DEVICE_ADDRESS, 111L, -48),
        )
        database.execSQL(
            "INSERT INTO journal (timestamp, reportJson) VALUES (?, ?)",
            arrayOf<Any?>(111L, """{"sentinel":true}"""),
        )
        database.execSQL(
            "INSERT INTO captured_advert_fingerprint VALUES (?, ?, ?)",
            arrayOf<Any?>("fingerprint", DEVICE_ADDRESS, 111L),
        )
        database.execSQL(
            """
            INSERT INTO btidalpool_upload_outbox (
                id, batch_id, source_log_name, source_sha256, chunk_index, chunk_count,
                chunk_sha256, destination, account_key, payload_path, payload_bytes,
                device_count, state, attempt_count, next_attempt_at_ms, last_error,
                created_at_ms, updated_at_ms, uploaded_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "outbox-id", "batch-id", "source.btides", "source-sha", 0, 1, "chunk-sha",
                "btidalpool", "account", "payload-path", 42L, 3, "PENDING", 0, 0L, null,
                111L, 222L, null,
            ),
        )
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun android.database.Cursor.string(column: String): String =
        getString(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.long(column: String): Long =
        getLong(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.int(column: String): Int =
        getInt(getColumnIndexOrThrow(column))

    companion object {
        private const val TEST_DATABASE = "migration-28-29-test"
        private const val DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"
        private const val VERSION_28_IDENTITY_HASH = "33d885fc7128776ea674fe4f3f5380f6"

        private val EXPECTED_DEVICE_COLUMNS = setOf(
            "address",
            "name",
            "last_detect_time_ms",
            "first_detect_time_ms",
            "detect_count",
            "custom_name",
            "manufacturer_id",
            "manufacturer_name",
            "last_seen_rssi",
            "system_address_type",
            "device_class",
            "is_paired",
            "service_uuids",
            "row_data_encoded",
            "is_connectable",
            "transport",
            "sdp_uuids",
            "gatt_manufacturer_name",
        )
    }
}
