package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end coverage for ADR 0009 (audit item B8): vehicle identity must survive a reinstall or a
 * second-phone restore even though `vehicle_key` is an HMAC under a per-install secret. Drives the
 * real components — [VinKeyHasher] secrets, [ObdStoreVehicles] upserts, [DatabaseMerger] merges,
 * and [BackupMigrator] for a pre-fix schema fixture — across two simulated installs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehicleIdentityPortabilityTest {
    private val openHelpers = ArrayList<SQLiteOpenHelper>()
    private val databaseNames = ArrayList<String>()

    @After
    fun tearDown() {
        val context = RuntimeEnvironment.getApplication()
        for (helper in openHelpers) helper.close()
        for (name in databaseNames) context.deleteDatabase(name)
    }

    /**
     * Reinstall scenario: the old install backed up its database, the app was reinstalled (fresh
     * random hasher secret), and the backup is restored into the EMPTY new install. History must
     * stay continuous with exactly ONE vehicle row, normalized to the new install's key on the
     * next VIN read.
     */
    @Test
    fun reinstallThenRestoreToEmptyInstallKeepsOneVehicleWithContinuousHistory() {
        val context = RuntimeEnvironment.getApplication()

        // Old install: secret A. The car was seen live, so its row carries recorded aliases.
        VinKeyHasher.replaceSecretsForTest(context, listOf(encode(SECRET_A)))
        val oldHelper = trackedHelper(context, "portability-old.db")
        val oldDb = oldHelper.writableDatabase
        val oldVehicleId = ObdStoreVehicles(oldHelper, VinKeyHasher(context)).upsertVehicleFromVin(VIN)
        assertTrue(oldVehicleId > 0)
        val oldKey = singleVehicleKey(oldDb)
        val oldSession = insertSession(oldDb, 1_000L)
        insertTripSegment(oldDb, oldSession, oldVehicleId, 1_000L)
        // What the backup settings manifest transports alongside the database file.
        val manifestSecrets = VinKeyHasher.exportSecrets(context)
        checkpoint(oldDb)

        // Reinstall: a fresh random secret B, empty database. Restore = merge into empty + apply
        // the manifest (which imports the donor identity secrets), mirroring BackupController.
        VinKeyHasher.replaceSecretsForTest(context, listOf(encode(SECRET_B)))
        val newHelper = trackedHelper(context, "portability-new.db")
        val newDb = newHelper.writableDatabase
        val mergeResult = mergeFromFile(context, newDb, "portability-old.db")
        assertTrue(mergeResult.ok)
        assertEquals(1, mergeResult.vehiclesAdded)
        VinKeyHasher.importSecrets(context, manifestSecrets)

        // Next drive reads the VIN: the restored row is recognized and claimed — not duplicated.
        val newHasher = VinKeyHasher(context)
        val vehicleId = ObdStoreVehicles(newHelper, newHasher).upsertVehicleFromVin(VIN)

        assertEquals(1, count(newDb, VoltTrackerDb.TABLE_VEHICLES))
        assertEquals("row is normalized to the new install's key", newHasher.hash(VIN), singleVehicleKey(newDb))
        assertEquals(
            "restored trip history stays attached to the surviving vehicle",
            1,
            count(newDb, "${VoltTrackerDb.TABLE_TRIP_SEGMENTS} WHERE vehicle_id = $vehicleId"),
        )
        assertTrue(
            "the old install's key stays in the recorded lineage",
            singleVehicleAliases(newDb).contains(oldKey),
        )
    }

    /**
     * Second-phone sync: phone B restored phone A's backup and reconnected once (recording aliases
     * under both secrets). Merging B's backup into phone A must now consolidate AT MERGE TIME —
     * no reconnect needed, no duplicate row, and A learns B's key for future syncs.
     */
    @Test
    fun secondPhoneRoundTripConsolidatesAtMergeTimeWithoutReconnect() {
        val context = RuntimeEnvironment.getApplication()

        // Phone A: secret A, car seen live.
        VinKeyHasher.replaceSecretsForTest(context, listOf(encode(SECRET_A)))
        val helperA = trackedHelper(context, "portability-phone-a.db")
        val dbA = helperA.writableDatabase
        ObdStoreVehicles(helperA, VinKeyHasher(context)).upsertVehicleFromVin(VIN)
        val keyA = singleVehicleKey(dbA)
        val manifestSecretsA = VinKeyHasher.exportSecrets(context)
        checkpoint(dbA)

        // Phone B: secret B, restores A's backup, imports A's manifest secrets, reconnects once.
        VinKeyHasher.replaceSecretsForTest(context, listOf(encode(SECRET_B)))
        val helperB = trackedHelper(context, "portability-phone-b.db")
        val dbB = helperB.writableDatabase
        assertTrue(mergeFromFile(context, dbB, "portability-phone-a.db").ok)
        VinKeyHasher.importSecrets(context, manifestSecretsA)
        ObdStoreVehicles(helperB, VinKeyHasher(context)).upsertVehicleFromVin(VIN)
        val keyB = singleVehicleKey(dbB)
        checkpoint(dbB)

        // Back on phone A (secret A), merge phone B's backup: the donor row is keyed under B's
        // secret, but its recorded aliases carry A's key — merge-time consolidation, no fork.
        VinKeyHasher.replaceSecretsForTest(context, listOf(encode(SECRET_A)))
        val result = mergeFromFile(context, dbA, "portability-phone-b.db")

        assertTrue(result.ok)
        assertEquals(0, result.vehiclesAdded)
        assertEquals(1, result.vehiclesMerged)
        assertEquals(1, count(dbA, VoltTrackerDb.TABLE_VEHICLES))
        assertEquals("phone A keeps its own keying", keyA, singleVehicleKey(dbA))
        assertTrue(
            "phone A learns phone B's key for future merge-time matching",
            singleVehicleAliases(dbA).contains(keyB),
        )
    }

    /**
     * Backward compatibility: a pre-fix (schema v15) backup has no alias column at all. It must
     * still stage through [BackupMigrator] and merge with the historical behavior — a strict-key
     * fork that the connect-time healer then consolidates once the VIN is read live.
     */
    @Test
    fun preFixV15BackupStillRestoresAndHealsOnNextVinRead() {
        val context = RuntimeEnvironment.getApplication()
        val donorKey = hmacHex(SECRET_A, VIN)
        val v15File = createV15BackupFixture(context, "portability-prefix-v15.db", donorKey)

        assertEquals(BackupMigrator.Result.MIGRATED, BackupMigrator.migrateToCurrentVersion(context, v15File))

        // Live install under secret B already knows the car (connected before restoring).
        VinKeyHasher.replaceSecretsForTest(context, listOf(encode(SECRET_B)))
        val helper = trackedHelper(context, "portability-prefix-live.db")
        val db = helper.writableDatabase
        val store = ObdStoreVehicles(helper, VinKeyHasher(context))
        val localId = store.upsertVehicleFromVin(VIN)

        val donor = SQLiteDatabase.openDatabase(v15File.path, null, SQLiteDatabase.OPEN_READONLY)
        val result = donor.use { DatabaseMerger.merge(db, it) }
        assertTrue(result.ok)
        assertEquals("no alias data: pre-fix donors fork exactly as before", 1, result.vehiclesAdded)
        assertEquals(2, count(db, VoltTrackerDb.TABLE_VEHICLES))

        // The restore flow then imports the manifest secrets; the next VIN read heals the fork.
        VinKeyHasher.importSecrets(context, listOf(encode(SECRET_A)))
        assertEquals(localId, store.upsertVehicleFromVin(VIN))
        assertEquals(1, count(db, VoltTrackerDb.TABLE_VEHICLES))
        assertTrue(
            "the healed row records the donor key so the same backup re-merges cleanly",
            singleVehicleAliases(db).contains(donorKey),
        )
    }

    private fun trackedHelper(
        context: Context,
        name: String,
    ): VoltTrackerDb {
        context.deleteDatabase(name)
        databaseNames.add(name)
        val helper = VoltTrackerDb(context, name)
        openHelpers.add(helper)
        return helper
    }

    private fun mergeFromFile(
        context: Context,
        target: SQLiteDatabase,
        donorName: String,
    ): DatabaseMerger.MergeResult {
        val donorFile = context.getDatabasePath(donorName)
        val donor = SQLiteDatabase.openDatabase(donorFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return donor.use { DatabaseMerger.merge(target, it) }
    }

    private fun checkpoint(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    private fun singleVehicleKey(db: SQLiteDatabase): String =
        db.rawQuery("SELECT vehicle_key FROM ${VoltTrackerDb.TABLE_VEHICLES}", null).use { cursor ->
            assertTrue("expected exactly one vehicle row", cursor.moveToFirst())
            val key = cursor.getString(0)
            assertTrue(cursor.isLast)
            key
        }

    private fun singleVehicleAliases(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT ${VehicleKeyAliases.COLUMN} FROM ${VoltTrackerDb.TABLE_VEHICLES}", null).use { cursor ->
            assertTrue("expected exactly one vehicle row", cursor.moveToFirst())
            VehicleKeyAliases.parse(cursor.getString(0))
        }

    private fun count(
        db: SQLiteDatabase,
        tableAndWhere: String,
    ): Int =
        db.rawQuery("SELECT COUNT(*) FROM $tableAndWhere", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun insertSession(
        db: SQLiteDatabase,
        startedAtMs: Long,
    ): Long {
        val cv = ContentValues()
        cv.put("mode", "obd")
        cv.put("started_at_ms", startedAtMs)
        cv.put("status", "complete")
        cv.put("sample_count", 0)
        cv.put("created_at_ms", startedAtMs)
        return db.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, cv)
    }

    private fun insertTripSegment(
        db: SQLiteDatabase,
        sessionId: Long,
        vehicleId: Long,
        startedAtMs: Long,
    ) {
        val cv = ContentValues()
        cv.put("session_id", sessionId)
        cv.put("vehicle_id", vehicleId)
        cv.put("started_at_ms", startedAtMs)
        cv.put("route_available", 0)
        cv.put("created_at_ms", startedAtMs)
        db.insertOrThrow(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, cv)
    }

    /** A schema-v15 backup file: vehicles table without the alias column, one donor-keyed row. */
    private fun createV15BackupFixture(
        context: Context,
        name: String,
        donorKey: String,
    ): java.io.File {
        context.deleteDatabase(name)
        databaseNames.add(name)
        val helper =
            object : SQLiteOpenHelper(context.applicationContext, name, null, 15) {
                override fun onCreate(db: SQLiteDatabase) {
                    // The v15-era vehicles DDL (no alias column) must be created BEFORE
                    // createRoadmapTables — its CREATE TABLE IF NOT EXISTS then skips vehicles,
                    // while still providing every other table the merger reads from a donor.
                    db.execSQL(
                        "CREATE TABLE " +
                            VoltTrackerDb.TABLE_VEHICLES +
                            " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            " vehicle_key TEXT NOT NULL UNIQUE," +
                            " display_name TEXT, make TEXT, model TEXT, model_year INTEGER," +
                            " vin_redacted TEXT, vin_hash TEXT, vin_source TEXT," +
                            " first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL," +
                            " created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL," +
                            " metadata_json TEXT)",
                    )
                    VoltTrackerSchema.createBaseTables(db)
                    VoltTrackerSchema.createObservationTables(db)
                    VoltTrackerSchema.createRoadmapTables(db)
                    VoltTrackerSchema.createMaintenanceLog(db)
                    val cv = ContentValues()
                    cv.put("vehicle_key", donorKey)
                    cv.put("vin_hash", donorKey)
                    cv.put("first_seen_ms", 1_000L)
                    cv.put("last_seen_ms", 2_000L)
                    cv.put("created_at_ms", 1_000L)
                    cv.put("updated_at_ms", 2_000L)
                    db.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, cv)
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    // unused
                }
            }
        val db = helper.writableDatabase
        checkpoint(db)
        helper.close()
        return context.getDatabasePath(name)
    }

    private companion object {
        private const val VIN = "1G1ZD5ST8JF" + "202020"
        private val SECRET_A = ByteArray(32) { (it + 10).toByte() }
        private val SECRET_B = ByteArray(32) { (it + 77).toByte() }

        private fun encode(secret: ByteArray): String = Base64.encodeToString(secret, Base64.NO_WRAP)

        private fun hmacHex(
            secret: ByteArray,
            value: String,
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            return mac
                .doFinal(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
