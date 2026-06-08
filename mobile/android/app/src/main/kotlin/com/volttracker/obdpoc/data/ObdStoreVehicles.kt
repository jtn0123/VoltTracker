package com.volttracker.obdpoc.data

import android.content.ContentValues
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Calendar
import java.util.Locale

/**
 * Vehicle identity derived from the VIN. Stores a privacy-preserving row: hash + redacted last-4,
 * never the raw VIN.
 */
class ObdStoreVehicles(
    private val helper: VoltTrackerDb,
) {
    fun upsertVehicleFromVin(vin: String?): Long {
        if (vin == null || vin.length != 17) {
            return 0L
        }
        val now = System.currentTimeMillis()
        val hash = sha256Hex(vin)
        val last4 = vin.substring(13)
        val wmi = vin.substring(0, 3)
        val make = guessMakeFromWmi(wmi)
        val year = decodeModelYear(vin[9])
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db
                .rawQuery(
                    "SELECT _id FROM ${VoltTrackerDb.TABLE_VEHICLES} WHERE vehicle_key = ?",
                    arrayOf(hash),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val existingId = cursor.getLong(0)
                        val update = ContentValues()
                        update.put("last_seen_ms", now)
                        update.put("updated_at_ms", now)
                        db.update(
                            VoltTrackerDb.TABLE_VEHICLES,
                            update,
                            "_id = ?",
                            arrayOf(existingId.toString()),
                        )
                        db.setTransactionSuccessful()
                        return existingId
                    }
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

    private fun sha256Hex(value: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashed = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
            val hex = StringBuilder(hashed.size * 2)
            for (byte in hashed) {
                hex.append(String.format("%02x", byte))
            }
            return hex.toString()
        } catch (ex: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 unavailable", ex)
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
}
