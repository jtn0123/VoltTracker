package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * The ADR-0009 (B8) vehicle-identity half of the donor merge: alias-aware key
 * registration and lineage folding, extracted from [DatabaseMerger] so the merger
 * stays under its LargeClass ratchet (the A4 decomposition direction).
 */
internal object VehicleIdentityMerge {
    class LiveVehicle(
        val id: Long,
        val key: String,
        val aliases: MutableSet<String>,
        var firstSeenMs: Long?,
        var lastSeenMs: Long?,
    )

    /**
     * ADR 0009 (B8): vehicle keys are HMACs under a per-install secret, so the same physical car
     * can arrive keyed differently than the live row. Donor and live rows are therefore matched on
     * the intersection of their `{vehicle_key} ∪ vehicle_key_aliases` sets — the aliases having
     * been recorded while the VIN was actually in hand (ObdStoreVehicles). Rows without aliases
     * (pre-v16 backups, no-VIN rows) degrade to the historical strict `vehicle_key` match.
     */
    fun copyVehicles(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        vehicleMap: MutableMap<Long, Long>,
        counts: IntArray,
        progress: DatabaseMerger.MergeProgress,
    ): Long {
        val liveById = HashMap<Long, VehicleIdentityMerge.LiveVehicle>()
        val idByKey = HashMap<String, Long>()
        target
            .rawQuery(
                "SELECT _id, vehicle_key, ${VehicleKeyAliases.COLUMN}, first_seen_ms, last_seen_ms" +
                    " FROM ${VoltTrackerDb.TABLE_VEHICLES}",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val live =
                        LiveVehicle(
                            c.getLong(0),
                            c.getString(1),
                            VehicleKeyAliases.parse(c.getString(2)).toMutableSet(),
                            if (c.isNull(3)) null else c.getLong(3),
                            if (c.isNull(4)) null else c.getLong(4),
                        )
                    liveById[live.id] = live
                    registerVehicleKeys(idByKey, live)
                }
            }
        var inserted = 0L
        DatabaseMerger.donorRows(donor, VoltTrackerDb.TABLE_VEHICLES).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging vehicles")
                val cv = DatabaseMerger.readRow(c)
                val oldId = cv.getAsLong("_id")
                val key = cv.getAsString("vehicle_key")
                if (oldId == null || key == null) {
                    continue
                }
                val donorKeys = VehicleKeyAliases.parse(cv.getAsString(VehicleKeyAliases.COLUMN)) + key
                val liveId = donorKeys.firstNotNullOfOrNull { idByKey[it] }
                if (liveId != null) {
                    vehicleMap[oldId] = liveId
                    counts[1]++
                    foldDonorIdentity(target, liveById.getValue(liveId), cv, donorKeys, idByKey)
                    continue
                }
                cv.remove("_id")
                val newId = target.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, cv)
                vehicleMap[oldId] = newId
                val live =
                    LiveVehicle(
                        newId,
                        key,
                        (donorKeys - key).toMutableSet(),
                        cv.getAsLong("first_seen_ms"),
                        cv.getAsLong("last_seen_ms"),
                    )
                liveById[newId] = live
                registerVehicleKeys(idByKey, live)
                counts[0]++
                inserted++
            }
        }
        return inserted
    }

    fun registerVehicleKeys(
        idByKey: MutableMap<String, Long>,
        live: LiveVehicle,
    ) {
        // First registration wins: an unlikely alias collision between two live rows keeps
        // matching deterministic instead of flip-flopping between them.
        // Map.putIfAbsent needs API 24 (minSdk is 23), so spell out the same first-wins check.
        if (live.key !in idByKey) idByKey[live.key] = live.id
        for (alias in live.aliases) {
            if (alias !in idByKey) idByKey[alias] = live.id
        }
    }

    /**
     * A donor row matched a live vehicle: union the identity lineage (the donor's key + aliases
     * become aliases of the live row, so future merges keep matching) and widen the seen window.
     * The live row's own key and descriptive fields stay authoritative.
     */
    fun foldDonorIdentity(
        target: SQLiteDatabase,
        live: LiveVehicle,
        donorCv: ContentValues,
        donorKeys: Set<String>,
        idByKey: MutableMap<String, Long>,
    ) {
        var changed = false
        for (donorKey in donorKeys) {
            if (donorKey != live.key && live.aliases.add(donorKey)) {
                if (donorKey !in idByKey) idByKey[donorKey] = live.id
                changed = true
            }
        }
        val donorFirst = donorCv.getAsLong("first_seen_ms")
        val liveFirst = live.firstSeenMs
        if (donorFirst != null && (liveFirst == null || donorFirst < liveFirst)) {
            live.firstSeenMs = donorFirst
            changed = true
        }
        val donorLast = donorCv.getAsLong("last_seen_ms")
        val liveLast = live.lastSeenMs
        if (donorLast != null && (liveLast == null || donorLast > liveLast)) {
            live.lastSeenMs = donorLast
            changed = true
        }
        if (!changed) {
            return
        }
        val update = ContentValues()
        update.put(VehicleKeyAliases.COLUMN, VehicleKeyAliases.serialize(live.aliases))
        live.firstSeenMs?.let { update.put("first_seen_ms", it) }
        live.lastSeenMs?.let { update.put("last_seen_ms", it) }
        target.update(VoltTrackerDb.TABLE_VEHICLES, update, "_id = ?", arrayOf(live.id.toString()))
    }
}
