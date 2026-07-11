package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONException

/**
 * Serialization for the `vehicles.vehicle_key_aliases` column (ADR 0009): a JSON array of the
 * keyed-HMAC vehicle keys this car is known by across installs. Recorded when the VIN is actually
 * in hand (a live OBD read) so [DatabaseMerger] can later recognize the same car in a backup keyed
 * by a different install secret — without the VIN ever being stored.
 *
 * Aliases must never contain the legacy unsalted `SHA-256(VIN)` hash; callers filter it out before
 * serializing (see `ObdStoreVehicles`).
 */
internal object VehicleKeyAliases {
    const val COLUMN = "vehicle_key_aliases"

    /** Bounds row growth: primary + up to 8 imported secrets leaves generous headroom. */
    internal const val MAX_ALIASES = 16

    fun parse(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(json)
            val out = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i, "")
                if (value.isNotBlank()) out.add(value)
            }
            out
        } catch (_: JSONException) {
            emptySet()
        }
    }

    /** Null when [keys] is empty, so untouched rows stay NULL. Deduplicates and caps the set. */
    fun serialize(keys: Collection<String>): String? {
        val distinct = keys.filter { it.isNotBlank() }.distinct().take(MAX_ALIASES)
        if (distinct.isEmpty()) return null
        return JSONArray(distinct).toString()
    }
}
