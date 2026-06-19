package com.darkmentor.data.database

import android.content.Context
import android.net.Uri
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.darkmentor.data.database.dao.AppleContactDao
import com.darkmentor.data.database.dao.DeviceDao
import com.darkmentor.data.database.dao.JournalDao
import com.darkmentor.data.database.dao.LocationDao
import com.darkmentor.data.database.entity.AppleContactEntity
import com.darkmentor.data.database.entity.DeviceEntity
import com.darkmentor.data.database.entity.DeviceToLocationEntity
import com.darkmentor.data.database.entity.JournalEntryEntity
import com.darkmentor.data.database.entity.LocationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@Database(
    entities = [
        DeviceEntity::class,
        AppleContactEntity::class,
        LocationEntity::class,
        DeviceToLocationEntity::class,
        JournalEntryEntity::class,
        com.darkmentor.data.database.entity.CapturedAdvertFingerprintEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
    ],
    exportSchema = true,
    version = 27,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao
    abstract fun appleContactDao(): AppleContactDao
    abstract fun locationDao(): LocationDao
    abstract fun journalDao(): JournalDao
    abstract fun capturedAdvertFingerprintDao(): com.darkmentor.data.database.dao.CapturedAdvertFingerprintDao

    suspend fun backupDatabase(toUri: Uri, context: Context) {
        Timber.i("Backup DB to file: ${toUri}")
        withContext(Dispatchers.IO) {
            val dbFile = File(context.getDatabasePath(openHelper.databaseName).toString())
            if (!dbFile.exists()) {
                throw IllegalStateException("The database file doesn't exist")
            }
            context.contentResolver.openOutputStream(toUri)?.use { outputStream ->
                dbFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw RuntimeException("Cannot create a backup file stream")
        }
    }

    suspend fun restoreDatabase(fromUri: Uri, context: Context) {
        withContext(Dispatchers.IO) {
            close()

            val contentResolver = context.contentResolver
            val tmpDatabaseName = openHelper.databaseName + "_tmp"
            val dbFile = File(context.getDatabasePath(openHelper.databaseName).toString())
            val tmpFile = File(context.getDatabasePath(tmpDatabaseName).toString())

            if (!tmpFile.exists()) {
                tmpFile.createNewFile()
            }

            contentResolver.openInputStream(fromUri).use { inputStream ->
                inputStream?.copyTo(tmpFile.outputStream()) ?: throw RuntimeException("Cannot open file")
            }

            try {
                testDatabase(tmpDatabaseName, context)
            } catch (e: Throwable) {
                tmpFile.delete()
                throw IllegalStateException("Cannot restore database from selected file")
            }

            tmpFile.renameTo(dbFile)
            tmpFile.delete()
        }
    }

    suspend fun getDatabaseSize(context: Context): Long {
        return withContext(Dispatchers.IO) {
            val dbFile = File(context.getDatabasePath(openHelper.databaseName).toString())
            dbFile.length()
        }
    }

    private fun testDatabase(name: String, context: Context) {
        val testDb = build(context, name)
        testDb.openHelper.writableDatabase.isDatabaseIntegrityOk
        testDb.close()
    }

    companion object {
        val loadDatabase = MutableStateFlow(false)

        fun build(context: Context, name: String): AppDatabase {
            loadDatabase.tryEmit(true)
            Timber.d("Build database: $name")
            val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_8_9,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                )
                .build()
            Timber.d("Database is ready!")
            loadDatabase.tryEmit(false)
            return database
        }

        private val MIGRATION_2_3 = migration(2, 3) {
            it.execSQL("ALTER TABLE device ADD COLUMN manufacturer_id INTEGER DEFAULT NULL;")
            it.execSQL("ALTER TABLE device ADD COLUMN manufacturer_name TEXT DEFAULT NULL;")
        }

        private val MIGRATION_3_4 = migration(3, 4) {
            it.execSQL(
                "CREATE TABLE `radar_profile` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`description` TEXT DEFAULT NULL, " +
                        "`is_active` INTEGER NOT NULL DEFAULT 1, " +
                        "`detect_filter` TEXT DEFAULT NULL, " +
                        "PRIMARY KEY(`id`));"
            )
        }

        private val MIGRATION_4_5 = migration(4, 5) {
            it.execSQL("DROP TABLE `radar_profile`;")
            it.execSQL(
                "CREATE TABLE `radar_profile` (" +
                        "`id` INTEGER, " +
                        "`name` TEXT NOT NULL, " +
                        "`description` TEXT DEFAULT NULL, " +
                        "`is_active` INTEGER NOT NULL DEFAULT 1, " +
                        "`detect_filter` TEXT DEFAULT NULL, " +
                        "PRIMARY KEY(`id`));"
            )
        }

        private val MIGRATION_5_6 = migration(5, 6) {
            it.execSQL(
                "CREATE TABLE `apple_contacts` (" +
                        "`sha_256` INTEGER NOT NULL, " +
                        "`associated_address` TEXT NOT NULL, " +
                        "`last_detect_time_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sha_256`));"
            )
        }

        private val MIGRATION_6_7 = migration(6, 7) {
            it.execSQL("DROP TABLE `apple_contacts`;")
            it.execSQL(
                "CREATE TABLE `apple_contacts` (" +
                        "`sha_256` INTEGER NOT NULL, " +
                        "`associated_address` TEXT NOT NULL, " +
                        "`first_detect_time_ms` INTEGER NOT NULL, " +
                        "`last_detect_time_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sha_256`));"
            )
        }

        private val MIGRATION_8_9 = migration(8, 9) {
            it.execSQL("ALTER TABLE device ADD COLUMN last_following_detection_ms INTEGER DEFAULT NULL;")
        }

        private val MIGRATION_12_13 = migration(12, 13) {
            it.execSQL("ALTER TABLE device ADD COLUMN system_address_type INTEGER DEFAULT NULL;")
            it.execSQL("ALTER TABLE device ADD COLUMN device_class INTEGER DEFAULT NULL;")
            it.execSQL("ALTER TABLE device ADD COLUMN is_paired INTEGER NOT NULL DEFAULT 0;")
        }

        private val MIGRATION_13_14 = migration(13, 14) {
            it.execSQL("ALTER TABLE device ADD COLUMN service_uuids TEXT NOT NULL DEFAULT '';")
        }

        private val MIGRATION_14_15 = migration(14, 15) {
            it.execSQL("ALTER TABLE device ADD COLUMN row_data_encoded TEXT DEFAULT NULL;")
        }

        val MIGRATION_15_16 = migration(15, 16) {
            it.execSQL("CREATE INDEX IF NOT EXISTS index_device_to_location ON device_to_location(device_address, location_time);")
            it.execSQL("CREATE INDEX IF NOT EXISTS index_location_time ON location(time);")
        }

        val MIGRATION_16_17 = migration(16, 17) {
            it.execSQL("ALTER TABLE device ADD COLUMN metadata TEXT DEFAULT NULL;")
        }

        val MIGRATION_17_18 = migration(17, 18) {
            it.execSQL("ALTER TABLE device ADD COLUMN is_connectable INTEGER NOT NULL DEFAULT 0;")
        }

        val MIGRATION_18_19 = migration(18, 19) {
            it.execSQL("ALTER TABLE radar_profile ADD COLUMN cooldown_ms INTEGER DEFAULT NULL;")
            it.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS profile_detect (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        profile_id INTEGER NOT NULL,
                        trigger_time INTEGER NOT NULL,
                        device_address TEXT NOT NULL
                    )
                """.trimIndent()
            )
            it.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_profile_detect_profile_id_trigger_time
                ON profile_detect(profile_id, trigger_time)
            """.trimIndent()
            )
        }

        val MIGRATION_19_20 = migration(19, 20) {
            it.execSQL("DROP INDEX IF EXISTS index_profile_detect_profile_id_trigger_time;")
            it.execSQL("DROP TABLE IF EXISTS profile_detect;")
            it.execSQL("DROP TABLE IF EXISTS radar_profile;")
        }

        // Indices on the hot columns that the per-batch path queries on every scan tick. With
        // M=200k+ devices these queries went from full table scans (multi-second freeze) to
        // index lookups bounded by the recently-seen window.
        val MIGRATION_20_21 = migration(20, 21) {
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_device_last_detect_time_ms` ON `device` (`last_detect_time_ms`);")
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_apple_contacts_associated_address` ON `apple_contacts` (`associated_address`);")
        }

        // Per-detection RSSI on device_to_location. Older rows have NULL (we never recorded it
        // before this migration); new rows record the RSSI of the strongest sample in the
        // batch. Used by: BTIDES export's "highest-RSSI lat/lng per device" enrichment and
        // the weighted-centroid best-fit marker.
        val MIGRATION_21_22 = migration(21, 22) {
            it.execSQL("ALTER TABLE device_to_location ADD COLUMN rssi INTEGER DEFAULT NULL;")
        }

        // Adds the columns that back BR/EDR (Bluetooth Classic) support. `transport` is the
        // ordinal of `domain.model.Transport`; in this version (v23) the encoding was
        // 0=UNKNOWN, 1=LE, 2=BREDR, 3=DUAL — migration 24→25 collapses out the UNKNOWN slot.
        // `sdp_uuids` mirrors the `service_uuids` storage shape (TEXT JSON list).
        val MIGRATION_22_23 = migration(22, 23) {
            it.execSQL("ALTER TABLE device ADD COLUMN transport INTEGER NOT NULL DEFAULT 0;")
            it.execSQL("ALTER TABLE device ADD COLUMN sdp_uuids TEXT NOT NULL DEFAULT '';")
        }

        // Drops the user-tagging feature (the `tag` lookup table and the `device.tags` column)
        // and the device-tracking feature (`device.last_following_detection_ms`). Recreate-the-
        // device-table dance because SQLite below 3.35 (older than Android 14's bundled SQLite)
        // doesn't support `ALTER TABLE … DROP COLUMN`.
        val MIGRATION_23_24 = migration(23, 24) {
            it.execSQL("DROP TABLE IF EXISTS `tag`;")
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `device_new` (" +
                    "`address` TEXT NOT NULL, " +
                    "`name` TEXT, " +
                    "`last_detect_time_ms` INTEGER NOT NULL, " +
                    "`first_detect_time_ms` INTEGER NOT NULL, " +
                    "`detect_count` INTEGER NOT NULL, " +
                    "`custom_name` TEXT, " +
                    "`favorite` INTEGER NOT NULL, " +
                    "`manufacturer_id` INTEGER, " +
                    "`manufacturer_name` TEXT, " +
                    "`last_seen_rssi` INTEGER, " +
                    "`system_address_type` INTEGER, " +
                    "`device_class` INTEGER, " +
                    "`is_paired` INTEGER NOT NULL, " +
                    "`service_uuids` TEXT NOT NULL DEFAULT '', " +
                    "`row_data_encoded` TEXT, " +
                    "`metadata` TEXT, " +
                    "`is_connectable` INTEGER NOT NULL, " +
                    "`transport` INTEGER NOT NULL DEFAULT 0, " +
                    "`sdp_uuids` TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(`address`));"
            )
            it.execSQL(
                "INSERT INTO `device_new` (" +
                    "address, name, last_detect_time_ms, first_detect_time_ms, detect_count, " +
                    "custom_name, favorite, manufacturer_id, manufacturer_name, " +
                    "last_seen_rssi, system_address_type, device_class, is_paired, " +
                    "service_uuids, row_data_encoded, metadata, is_connectable, transport, " +
                    "sdp_uuids) " +
                    "SELECT address, name, last_detect_time_ms, first_detect_time_ms, " +
                    "detect_count, custom_name, favorite, manufacturer_id, manufacturer_name, " +
                    "last_seen_rssi, system_address_type, device_class, is_paired, " +
                    "service_uuids, row_data_encoded, metadata, is_connectable, transport, " +
                    "sdp_uuids FROM `device`;"
            )
            it.execSQL("DROP TABLE `device`;")
            it.execSQL("ALTER TABLE `device_new` RENAME TO `device`;")
            // Recreate the index that DeviceEntity declares so query plans stay the same.
            it.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_device_last_detect_time_ms` " +
                    "ON `device` (`last_detect_time_ms`);"
            )
        }

        // Drops the [Transport.UNKNOWN] enum value. The old encoding (UNKNOWN=0, LE=1, BREDR=2,
        // DUAL=3) shifts down by one (LE=0, BREDR=1, DUAL=2) so callers can drop the
        // "what if it's UNKNOWN" branches. Existing rows with transport=0 (UNKNOWN) get
        // remapped to 0 (the new LE) since LE was the historical fallback for legacy LE-only
        // detections. Rows at 1/2/3 each shift down by one.
        val MIGRATION_24_25 = migration(24, 25) {
            it.execSQL(
                "UPDATE device SET transport = " +
                    "CASE WHEN transport = 0 THEN 0 ELSE transport - 1 END;"
            )
        }

        // Adds the GATT-derived manufacturer-name column. Populated via the GATT 0x2A29
        // (Manufacturer Name String) characteristic captured during Connect All — surfaces
        // as a last-resort fallback under the "Manufacturer" line on Device Details when no
        // MSD-derived name is available.
        val MIGRATION_25_26 = migration(25, 26) {
            it.execSQL("ALTER TABLE device ADD COLUMN gatt_manufacturer_name TEXT DEFAULT NULL;")
        }

        // captured_advert_fingerprint table. Backs the Connect All "skip same advert,
        // different BDADDR" optimisation — see CapturedAdvertFingerprintEntity for the
        // semantics. INSERT-OR-IGNORE on the PK ensures repeated captures of the same
        // fingerprint don't overwrite the diagnostic first_address / captured_time_ms.
        val MIGRATION_26_27 = migration(26, 27) {
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS captured_advert_fingerprint (" +
                    "fingerprint TEXT NOT NULL PRIMARY KEY, " +
                    "first_address TEXT NOT NULL, " +
                    "captured_time_ms INTEGER NOT NULL)"
            )
        }

        private fun migration(
            from: Int,
            to: Int,
            migrationFun: (database: SupportSQLiteDatabase) -> Unit
        ): Migration {
            return object : Migration(from, to) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    loadDatabase.value = true
                    migrationFun.invoke(database)
                    loadDatabase.value = false
                }
            }
        }
    }
}