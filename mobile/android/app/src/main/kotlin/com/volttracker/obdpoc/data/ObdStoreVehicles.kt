package com.volttracker.obdpoc.data

import android.content.ContentValues
import androidx.core.database.sqlite.transaction
import java.util.Calendar
import java.util.Locale

/**
 * Vehicle identity derived from the VIN. Stores a privacy-preserving row: keyed hash + redacted
 * last-4 + keyed-hash aliases under every known install secret (ADR 0009), never the raw VIN and
 * never the enumerable unsalted hash.
 */
class ObdStoreVehicles(
    private val helper: VoltTrackerDb,
    private val vinKeyHasher: VinKeyHasher,
) {
    private class VehicleRow(
        val id: Long,
        val key: String,
        val aliases: Set<String>,
        val firstSeenMs: Long,
    )

    fun upsertVehicleFromVin(vin: String?): Long {
        if (vin == null || vin.length != 17) {
            return 0L
        }
        val now = System.currentTimeMillis()
        val hash = vinKeyHasher.hash(vin)
        // Every keyed-HMAC form of this VIN under the secrets this install knows. These are what
        // gets recorded as aliases (ADR 0009) so a later merge can recognize the car without the
        // VIN. The legacy unsalted hash is a match candidate only — storing it would reintroduce
        // the enumerable hash the HMAC keying removed.
        val hmacCandidates = vinKeyHasher.hashCandidates(vin)
        val legacyHash = VinKeyHasher.legacyHash(vin)
        val candidateHashes = (hmacCandidates + legacyHash).toSet()
        val last4 = vin.substring(13)
        val wmi = vin.substring(0, 3)
        val make = guessMakeFromWmi(wmi)
        val year = decodeModelYear(vin[9])
        val db = helper.writableDatabase
        return db.transaction {
            // The table holds one row per physical car the user owns, so loading it whole is
            // cheap and lets matching cover recorded aliases, not just the primary key column.
            val matches =
                loadVehicleRows(db).filter { row ->
                    row.key in candidateHashes || row.aliases.any { it in candidateHashes }
                }
            if (matches.isNotEmpty()) {
                // Prefer the row already keyed by this install. A merged backup can contain the
                // same VIN keyed with a donor secret; consolidate every dependent row before
                // deleting that duplicate and normalizing to the local primary key.
                val preferred = matches.firstOrNull { it.key == hash } ?: matches.first()
                for (duplicate in matches.filter { it.id != preferred.id }) {
                    remapVehicleReferences(db, duplicate.id, preferred.id)
                    db.delete(
                        VoltTrackerDb.TABLE_VEHICLES,
                        "_id = ?",
                        arrayOf(duplicate.id.toString()),
                    )
                }
                // Fold the consolidated rows' identity lineage (their keys and recorded aliases)
                // into the surviving row so future merges of old backups match at merge time.
                val aliases =
                    (hmacCandidates + matches.flatMap { it.aliases } + matches.map { it.key })
                        .filter { it != legacyHash }
                val update = ContentValues()
                update.put("vehicle_key", hash)
                update.put("vin_hash", hash)
                update.put(VehicleKeyAliases.COLUMN, VehicleKeyAliases.serialize(aliases))
                update.put("first_seen_ms", matches.minOf { it.firstSeenMs })
                update.put("last_seen_ms", now)
                update.put("updated_at_ms", now)
                db.update(
                    VoltTrackerDb.TABLE_VEHICLES,
                    update,
                    "_id = ?",
                    arrayOf(preferred.id.toString()),
                )
                preferred.id
            } else {
                val values = ContentValues()
                values.put("vehicle_key", hash)
                values.put(VehicleKeyAliases.COLUMN, VehicleKeyAliases.serialize(hmacCandidates))
                values.put("vin_redacted", last4)
                values.put("vin_hash", hash)
                values.put("vin_source", "obd_0902")
                if (make != null) {
                    values.put("make", make)
                    values.put("display_name", make)
                }
                if (year != null) {
                    values.put("model_year", year)
                }
                values.put("first_seen_ms", now)
                values.put("last_seen_ms", now)
                values.put("created_at_ms", now)
                values.put("updated_at_ms", now)
                db.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, values)
            }
        }
    }

    private fun loadVehicleRows(db: android.database.sqlite.SQLiteDatabase): List<VehicleRow> {
        val rows = ArrayList<VehicleRow>()
        db
            .rawQuery(
                "SELECT _id, vehicle_key, ${VehicleKeyAliases.COLUMN}, first_seen_ms" +
                    " FROM ${VoltTrackerDb.TABLE_VEHICLES}",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(
                        VehicleRow(
                            cursor.getLong(0),
                            cursor.getString(1),
                            VehicleKeyAliases.parse(cursor.getString(2)),
                            cursor.getLong(3),
                        ),
                    )
                }
            }
        return rows
    }

    private fun remapVehicleReferences(
        db: android.database.sqlite.SQLiteDatabase,
        fromVehicleId: Long,
        toVehicleId: Long,
    ) {
        val values = ContentValues().apply { put("vehicle_id", toVehicleId) }
        for (table in VEHICLE_REFERENCE_TABLES) {
            db.update(table, values, "vehicle_id = ?", arrayOf(fromVehicleId.toString()))
        }
    }

    private fun guessMakeFromWmi(wmi: String?): String? {
        if (wmi == null || wmi.length < 3) {
            return null
        }
        val upper = wmi.uppercase(Locale.US)
        if (upper.startsWith("1G1") ||
            upper.startsWith("1G6") ||
            upper.startsWith("1GC") ||
            upper.startsWith("1GT") ||
            upper.startsWith("2G1") ||
            upper.startsWith("3G1")
        ) {
            return "Chevrolet"
        }
        if (upper.startsWith("1FT") || upper.startsWith("1FA") || upper.startsWith("3FA")) {
            return "Ford"
        }
        if (upper.startsWith("1HG") || upper.startsWith("2HG") || upper.startsWith("JHM")) {
            return "Honda"
        }
        if (upper.startsWith("4T1") || upper.startsWith("JT2") || upper.startsWith("5TD")) {
            return "Toyota"
        }
        return null
    }

    private fun decodeModelYear(code: Char): Int? {
        val alphabet = "ABCDEFGHJKLMNPRSTVWXY123456789"
        val index = alphabet.indexOf(code.uppercaseChar())
        if (index < 0) {
            return null
        }
        var baseYear = 1980 + index
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        while (baseYear + 30 <= currentYear + 1) {
            baseYear += 30
        }
        return baseYear
    }

    private companion object {
        val VEHICLE_REFERENCE_TABLES =
            arrayOf(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                VoltTrackerDb.TABLE_TRIP_SEGMENTS,
                VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                VoltTrackerDb.TABLE_EXPORTS,
            )
    }
}
