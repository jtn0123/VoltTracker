package com.volttracker.obdpoc.data

import android.content.ContentValues
import java.util.Calendar
import java.util.Locale

/**
 * Vehicle identity derived from the VIN. Stores a privacy-preserving row: hash + redacted last-4,
 * never the raw VIN.
 */
class ObdStoreVehicles(
    private val helper: VoltTrackerDb,
    private val vinKeyHasher: VinKeyHasher,
) {
    fun upsertVehicleFromVin(vin: String?): Long {
        if (vin == null || vin.length != 17) {
            return 0L
        }
        val now = System.currentTimeMillis()
        val hash = vinKeyHasher.hash(vin)
        val candidateHashes = (vinKeyHasher.hashCandidates(vin) + VinKeyHasher.legacyHash(vin)).distinct()
        val last4 = vin.substring(13)
        val wmi = vin.substring(0, 3)
        val make = guessMakeFromWmi(wmi)
        val year = decodeModelYear(vin[9])
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val matches = ArrayList<Pair<Long, String>>()
            val placeholders = candidateHashes.joinToString(",") { "?" }
            db
                .rawQuery(
                    "SELECT _id, vehicle_key FROM ${VoltTrackerDb.TABLE_VEHICLES} " +
                        "WHERE vehicle_key IN ($placeholders)",
                    candidateHashes.toTypedArray(),
                ).use { cursor ->
                    while (cursor.moveToNext()) matches.add(cursor.getLong(0) to cursor.getString(1))
                }
            if (matches.isNotEmpty()) {
                // Prefer the row already keyed by this install. A merged backup can contain the
                // same VIN keyed with a donor secret; consolidate every dependent row before
                // deleting that duplicate and normalizing to the local primary key.
                val preferred = matches.firstOrNull { it.second == hash } ?: matches.first()
                for (duplicate in matches.filter { it.first != preferred.first }) {
                    remapVehicleReferences(db, duplicate.first, preferred.first)
                    db.delete(
                        VoltTrackerDb.TABLE_VEHICLES,
                        "_id = ?",
                        arrayOf(duplicate.first.toString()),
                    )
                }
                val update = ContentValues()
                update.put("vehicle_key", hash)
                update.put("vin_hash", hash)
                update.put("last_seen_ms", now)
                update.put("updated_at_ms", now)
                db.update(
                    VoltTrackerDb.TABLE_VEHICLES,
                    update,
                    "_id = ?",
                    arrayOf(preferred.first.toString()),
                )
                db.setTransactionSuccessful()
                return preferred.first
            }
            val values = ContentValues()
            values.put("vehicle_key", hash)
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
            val id = db.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, values)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
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
