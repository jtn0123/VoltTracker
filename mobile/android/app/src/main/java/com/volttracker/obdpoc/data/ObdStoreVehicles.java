package com.volttracker.obdpoc.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Vehicle identity derived from the VIN — a self-contained concern split out of {@link
 * ObdStoreWriter}. Stores a privacy-preserving vehicle row (hash + redacted last-4, never the raw
 * VIN) and decodes make/model-year from the VIN structure. Stateless apart from the DB handle.
 */
final class ObdStoreVehicles {

    private final VoltTrackerDb helper;

    ObdStoreVehicles(VoltTrackerDb helper) {
        this.helper = helper;
    }

    /**
     * Upserts a vehicle row keyed by the SHA-256 hash of the VIN. We deliberately do not store the
     * raw VIN — only {@code vin_redacted} (last 4 chars, the most useful for "is this the same
     * car?" without being PII) and the hash for stable lookup across sessions. {@code make} is
     * derived from the WMI (world manufacturer identifier, first 3 chars of the VIN), {@code
     * model_year} from position 10 per ISO-3779.
     *
     * <p>Returns the vehicle row id, or 0 if the VIN was rejected (wrong length, unrecognized
     * format). Idempotent on the {@code vin_hash} unique key — repeated calls update {@code
     * last_seen_ms} only.
     */
    long upsertVehicleFromVin(String vin) {
        if (vin == null || vin.length() != 17) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        String hash = sha256Hex(vin);
        String last4 = vin.substring(13);
        String wmi = vin.substring(0, 3);
        String make = guessMakeFromWmi(wmi);
        Integer year = decodeModelYear(vin.charAt(9));
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT _id FROM "
                                    + VoltTrackerDb.TABLE_VEHICLES
                                    + " WHERE vehicle_key = ?",
                            new String[] {hash})) {
                if (cursor.moveToFirst()) {
                    long existingId = cursor.getLong(0);
                    ContentValues update = new ContentValues();
                    update.put("last_seen_ms", now);
                    update.put("updated_at_ms", now);
                    db.update(
                            VoltTrackerDb.TABLE_VEHICLES,
                            update,
                            "_id = ?",
                            new String[] {String.valueOf(existingId)});
                    db.setTransactionSuccessful();
                    return existingId;
                }
            }
            ContentValues values = new ContentValues();
            values.put("vehicle_key", hash);
            values.put("vin_redacted", last4);
            values.put("vin_hash", hash);
            values.put("vin_source", "obd_0902");
            if (make != null) {
                values.put("make", make);
                values.put("display_name", make);
            }
            if (year != null) {
                values.put("model_year", year);
            }
            values.put("first_seen_ms", now);
            values.put("last_seen_ms", now);
            values.put("created_at_ms", now);
            values.put("updated_at_ms", now);
            long id = db.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, values);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            // SHA-256 is mandatory on every Android version we support; falling back to the
            // raw value would defeat the redaction so we deliberately let this fail loudly.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Maps a 3-char WMI to a coarse manufacturer name. We only carry entries the app is likely to
     * actually encounter — primarily GM's Chevy Volt prefixes — and fall back to {@code null} for
     * anything else so the column stays unset rather than wrong.
     */
    private static String guessMakeFromWmi(String wmi) {
        if (wmi == null || wmi.length() < 3) {
            return null;
        }
        String upper = wmi.toUpperCase(java.util.Locale.US);
        if (upper.startsWith("1G1")
                || upper.startsWith("1G6")
                || upper.startsWith("1GC")
                || upper.startsWith("1GT")
                || upper.startsWith("2G1")
                || upper.startsWith("3G1")) {
            return "Chevrolet";
        }
        if (upper.startsWith("1FT") || upper.startsWith("1FA") || upper.startsWith("3FA")) {
            return "Ford";
        }
        if (upper.startsWith("1HG") || upper.startsWith("2HG") || upper.startsWith("JHM")) {
            return "Honda";
        }
        if (upper.startsWith("4T1") || upper.startsWith("JT2") || upper.startsWith("5TD")) {
            return "Toyota";
        }
        return null;
    }

    /**
     * ISO-3779 model-year code in VIN position 10. Returns null for the ambiguous {@code
     * I/O/Q/U/Z/0} positions or unsupported codes. The 30-year cycle wrapped in 2010 (the same code
     * maps to 1980 and 2010); we resolve it by snapping into the current 30-year window centred on
     * today.
     */
    private static Integer decodeModelYear(char code) {
        String alphabet = "ABCDEFGHJKLMNPRSTVWXY123456789";
        int index = alphabet.indexOf(Character.toUpperCase(code));
        if (index < 0) {
            return null;
        }
        int baseYear = 1980 + index; // A=1980, B=1981 ...
        int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        // Snap forward by 30 years until the candidate is within 30 years of today.
        while (baseYear + 30 <= currentYear + 1) {
            baseYear += 30;
        }
        return baseYear;
    }
}
