package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure serializers that turn a trip's route projection (the same `{ session, points, powerTrack, … }`
 * payload [ObdStoreRouteProjection.routeForSession] builds) into a single-trip GPX 1.1 track or a CSV
 * sample log.
 *
 * Kept in the data layer with `org.json`-only dependencies (no Android `Context`, FileProvider, or
 * Intent), so the file/share plumbing lives in the top-level `TripExportShareIntent` while this stays
 * unit-testable on the JVM and clean under [com.volttracker.obdpoc.ArchitectureBoundaryTest].
 *
 * Each route point is the projection's per-sample object: `lat`, `lng`, optional `atMs` (epoch ms),
 * `speedMps`, `bearingDeg`, `altM`, `accuracyM`, and either `soc` (telemetry source) or nothing.
 */
object TripTrackFormatter {
    const val GPX_MIME = "application/gpx+xml"
    const val CSV_MIME = "text/csv"

    /** CSV header. Kept in sync with [csvRow] — one column per emitted field. */
    const val CSV_HEADER =
        "index,timestamp_iso,epoch_ms,latitude,longitude," +
            "speed_mps,bearing_deg,altitude_m,accuracy_m,soc_pct"

    /**
     * Header for the bulk all-trips CSV (M6): the single-trip [CSV_HEADER] prefixed with a
     * trip-id/label pair, so every row is attributable to its drive. Kept in sync with [toAllTripsCsv].
     */
    const val ALL_TRIPS_CSV_HEADER = "trip_id,trip_label,$CSV_HEADER"

    /**
     * Renders the route as a GPX 1.1 document: one `<trk>` with a single `<trkseg>` whose `<trkpt>`s
     * carry `lat`/`lon` plus `<time>` (when the sample has `atMs`), `<ele>` (altitude), and the speed
     * under a GPX extension. Returns a valid (empty-track) document when there are no points.
     */
    fun toGpx(route: JSONObject): String {
        val points = route.optJSONArray("points") ?: JSONArray()
        val name = trackName(route)
        val builder = StringBuilder(GPX_INITIAL_CAPACITY)
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder
            .append("<gpx version=\"1.1\" creator=\"")
            .append(escapeXml(GPX_CREATOR))
            .append("\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            .append(
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
                    "http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
            )
        builder.append("  <trk>\n    <name>").append(escapeXml(name)).append("</name>\n    <trkseg>\n")
        for (i in 0 until points.length()) {
            appendTrkpt(builder, points.optJSONObject(i) ?: continue)
        }
        builder.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return builder.toString()
    }

    private fun appendTrkpt(
        builder: StringBuilder,
        point: JSONObject,
    ) {
        val lat = point.optDouble("lat", Double.NaN)
        val lng = point.optDouble("lng", Double.NaN)
        // Reject non-finite (NaN and ±Infinity): coord() -> toBigDecimal() throws on infinities.
        if (!lat.isFinite() || !lng.isFinite()) {
            return
        }
        builder
            .append("      <trkpt lat=\"")
            .append(coord(lat))
            .append("\" lon=\"")
            .append(coord(lng))
            .append("\">\n")
        val altitude = optDoubleOrNull(point, "altM")
        if (altitude != null) {
            builder.append("        <ele>").append(coord(altitude)).append("</ele>\n")
        }
        val atMs = point.optLong("atMs", 0L)
        if (atMs > 0L) {
            builder.append("        <time>").append(isoUtc(atMs)).append("</time>\n")
        }
        appendSpeedExtension(builder, point)
        builder.append("      </trkpt>\n")
    }

    private fun appendSpeedExtension(
        builder: StringBuilder,
        point: JSONObject,
    ) {
        val speed = optDoubleOrNull(point, "speedMps") ?: return
        // GPX 1.1 has no first-class speed on a trkpt; the carrier is a bare <extensions> block
        // holding a plain <speed> element (meters/second), which Garmin/Strava importers read.
        builder
            .append("        <extensions>\n          <speed>")
            .append(coord(speed))
            .append("</speed>\n        </extensions>\n")
    }

    /**
     * Renders the route as CSV: [CSV_HEADER] followed by one row per point. Returns just the header
     * when there are no points. Numeric fields are emitted with full precision; missing values are
     * left blank so a spreadsheet reads them as empty cells rather than zero.
     */
    fun toCsv(route: JSONObject): String {
        val points = route.optJSONArray("points") ?: JSONArray()
        val builder = StringBuilder(CSV_INITIAL_CAPACITY)
        builder.append(CSV_HEADER).append('\n')
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i) ?: continue
            builder.append(csvRow(i, point)).append('\n')
        }
        return builder.toString()
    }

    private fun csvRow(
        index: Int,
        point: JSONObject,
    ): String {
        val atMs = point.optLong("atMs", 0L)
        return listOf(
            index.toString(),
            if (atMs > 0L) isoUtc(atMs) else "",
            if (atMs > 0L) atMs.toString() else "",
            csvNumber(optDoubleOrNull(point, "lat")),
            csvNumber(optDoubleOrNull(point, "lng")),
            csvNumber(optDoubleOrNull(point, "speedMps")),
            csvNumber(optDoubleOrNull(point, "bearingDeg")),
            csvNumber(optDoubleOrNull(point, "altM")),
            csvNumber(optDoubleOrNull(point, "accuracyM")),
            csvNumber(optDoubleOrNull(point, "soc")),
        ).joinToString(",")
    }

    /**
     * Renders a bulk all-trips CSV (M6) from a list of `{ tripId, label, route }` objects (the shape
     * [com.volttracker.obdpoc.data.ObdStoreReports.allTripsForExportJson] builds). One [ALL_TRIPS_CSV_HEADER]
     * line, then every trip's points back-to-back, each row prefixed with its trip id + label so the
     * combined file stays attributable. The per-sample columns reuse the single-trip [csvRow]
     * projection. Returns just the header when there are no trips/points. The trip label is CSV-quoted
     * (it is free user text and may contain commas/quotes/newlines); the trip id is a safe route key.
     */
    fun toAllTripsCsv(trips: JSONArray): String {
        val builder = StringBuilder(CSV_INITIAL_CAPACITY)
        builder.append(ALL_TRIPS_CSV_HEADER).append('\n')
        for (t in 0 until trips.length()) {
            val trip = trips.optJSONObject(t) ?: continue
            val tripId = csvField(trip.optString("tripId", ""))
            val label = csvField(trip.optString("label", ""))
            val route = trip.optJSONObject("route") ?: continue
            val points = route.optJSONArray("points") ?: continue
            for (i in 0 until points.length()) {
                val point = points.optJSONObject(i) ?: continue
                builder
                    .append(tripId)
                    .append(',')
                    .append(label)
                    .append(',')
                    .append(csvRow(i, point))
                    .append('\n')
            }
        }
        return builder.toString()
    }

    /**
     * Header for the charge-sessions CSV (M1). One column per [chargeRow] field. When a positive
     * electricity rate is supplied to [toChargeSessionsCsv], a trailing `est_cost` column is appended
     * (see [CHARGE_SESSIONS_CSV_HEADER_WITH_COST]); kept in sync with [chargeRow].
     */
    const val CHARGE_SESSIONS_CSV_HEADER =
        "id,start_iso,start_epoch_ms,end_iso,end_epoch_ms," +
            "start_soc_pct,end_soc_pct,energy_kwh,peak_power_kw,charger_type,confidence"

    /** [CHARGE_SESSIONS_CSV_HEADER] plus the optional estimated-cost column (rate set). */
    const val CHARGE_SESSIONS_CSV_HEADER_WITH_COST = "$CHARGE_SESSIONS_CSV_HEADER,est_cost"

    /**
     * Renders the charge history as CSV (M1): a header line, then one row per charge (the shape
     * [com.volttracker.obdpoc.data.ObdStoreReports.chargeSessionsForExportJson] builds —
     * `{ id, startedAtMs, endedAtMs, startSoc, endSoc, energyKwh, powerKw, avgPowerKw, chargerType,
     * confidence }`). When [pricePerKwh] is a positive finite rate, an `est_cost` column (energyKwh ×
     * rate, 2 dp) is appended; otherwise it is omitted. Returns just the header when there are no
     * charges. The free-text `charger_type` is CSV-quoted + formula-injection-guarded via [csvField]
     * (it may arrive from a merged backup); numeric fields use [csvNumber] / blank-for-missing.
     */
    fun toChargeSessionsCsv(
        chargeRows: JSONArray,
        pricePerKwh: Double?,
    ): String {
        val withCost = pricePerKwh != null && pricePerKwh.isFinite() && pricePerKwh > 0.0
        val builder = StringBuilder(CSV_INITIAL_CAPACITY)
        builder.append(if (withCost) CHARGE_SESSIONS_CSV_HEADER_WITH_COST else CHARGE_SESSIONS_CSV_HEADER).append('\n')
        for (i in 0 until chargeRows.length()) {
            val row = chargeRows.optJSONObject(i) ?: continue
            builder.append(chargeRow(row, if (withCost) pricePerKwh else null)).append('\n')
        }
        return builder.toString()
    }

    private fun chargeRow(
        row: JSONObject,
        pricePerKwh: Double?,
    ): String {
        val startedAtMs = row.optLong("startedAtMs", 0L)
        val endedAtMs = optLongOrNull(row, "endedAtMs")
        val energyKwh = optDoubleOrNull(row, "energyKwh")
        val cells =
            mutableListOf(
                csvField(row.opt("id")?.toString().orEmpty()),
                if (startedAtMs > 0L) isoUtc(startedAtMs) else "",
                if (startedAtMs > 0L) startedAtMs.toString() else "",
                if (endedAtMs != null && endedAtMs > 0L) isoUtc(endedAtMs) else "",
                if (endedAtMs != null && endedAtMs > 0L) endedAtMs.toString() else "",
                csvNumber(optDoubleOrNull(row, "startSoc")),
                csvNumber(optDoubleOrNull(row, "endSoc")),
                csvNumber(energyKwh),
                csvNumber(optDoubleOrNull(row, "powerKw")),
                csvField(optStringOrEmpty(row, "chargerType")),
                csvNumber(optDoubleOrNull(row, "confidence")),
            )
        if (pricePerKwh != null) {
            cells.add(if (energyKwh != null) money(energyKwh * pricePerKwh) else "")
        }
        return cells.joinToString(",")
    }

    private fun optLongOrNull(
        obj: JSONObject,
        key: String,
    ): Long? = if (!obj.has(key) || obj.isNull(key)) null else obj.optLong(key, 0L)

    private fun optStringOrEmpty(
        obj: JSONObject,
        key: String,
    ): String = if (!obj.has(key) || obj.isNull(key)) "" else obj.optString(key, "")

    /** Fixed 2-dp currency-style rendering for the estimated-cost column (locale-independent). */
    private fun money(value: Double): String =
        if (!value.isFinite()) "" else value.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

    /** Point count actually written to a track (the projection may include null/invalid entries). */
    fun pointCount(route: JSONObject): Int {
        val points = route.optJSONArray("points") ?: return 0
        var count = 0
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i) ?: continue
            val lat = point.optDouble("lat", Double.NaN)
            val lng = point.optDouble("lng", Double.NaN)
            if (lat.isFinite() && lng.isFinite()) {
                count += 1
            }
        }
        return count
    }

    /**
     * A filesystem-safe base name for the export, e.g. `volt-trip-2024-05-01-0930-12`. Derives the
     * timestamp from the route's started-at (falling back to the first point), and the trailing
     * suffix from the session id, so two exports of different trips never collide in the cache dir.
     */
    fun fileBaseName(route: JSONObject): String {
        val session = route.optJSONObject("session")
        val startedAtMs =
            session?.optLong("startedAtMs", 0L)?.takeIf { it > 0L }
                ?: firstPointAtMs(route)
        val stamp = if (startedAtMs > 0L) fileStamp(startedAtMs) else "unknown"
        val suffix =
            session?.opt("sessionId")?.toString()?.takeIf { it.isNotBlank() }
                ?: session?.opt("id")?.toString()?.takeIf { it.isNotBlank() }
        val cleanSuffix = suffix?.let { sanitize(it) }.orEmpty()
        return if (cleanSuffix.isEmpty()) "volt-trip-$stamp" else "volt-trip-$stamp-$cleanSuffix"
    }

    private fun firstPointAtMs(route: JSONObject): Long {
        val points = route.optJSONArray("points") ?: return 0L
        for (i in 0 until points.length()) {
            val atMs = points.optJSONObject(i)?.optLong("atMs", 0L) ?: 0L
            if (atMs > 0L) {
                return atMs
            }
        }
        return 0L
    }

    private fun trackName(route: JSONObject): String {
        val session = route.optJSONObject("session")
        val adapter = session?.optString("adapterName").orEmpty().ifBlank { "Volt Tracker drive" }
        val startedAtMs = session?.optLong("startedAtMs", 0L)?.takeIf { it > 0L } ?: firstPointAtMs(route)
        return if (startedAtMs > 0L) "$adapter — ${isoUtc(startedAtMs)}" else adapter
    }

    private fun optDoubleOrNull(
        obj: JSONObject,
        key: String,
    ): Double? {
        if (!obj.has(key) || obj.isNull(key)) {
            return null
        }
        val value = obj.optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    private fun csvNumber(value: Double?): String = value?.let { coord(it) } ?: ""

    /**
     * RFC-4180 CSV escaping for a free-text field (the all-trips trip label/id), plus a spreadsheet
     * formula-injection guard. Wraps the value in quotes and doubles any embedded quote when it
     * contains a comma, quote, or newline; and, because the trip label is free user text (and can
     * arrive from a merged backup), neutralizes a leading `= + - @` / tab / CR — characters that
     * make Excel/Google Sheets evaluate the cell as a formula — by prefixing a single quote so the
     * cell is treated as literal text. The trip id is a numeric route key, so this only ever rewrites
     * a hostile label, never real data. Keeps a hostile/odd label from breaking the bulk CSV.
     */
    private fun csvField(raw: String): String {
        if (raw.isEmpty()) {
            return ""
        }
        val guarded = if (raw[0] in CSV_FORMULA_TRIGGERS) "'" + raw else raw
        if (guarded.contains(',') || guarded.contains('"') || guarded.contains('\n') || guarded.contains('\r')) {
            return "\"" + guarded.replace("\"", "\"\"") + "\""
        }
        return guarded
    }

    /**
     * Locale-independent, plain (non-scientific) decimal rendering for coordinates/measurements.
     *
     * Always keeps at least one decimal place so a whole-valued coordinate renders as `"0.0"`/`"33.0"`
     * rather than `"0"`/`"33"` — consistent with the fractional points alongside it in the same
     * GPX/CSV file users hand to Strava/Garmin. (Both forms parse identically, but the mixed integer/
     * decimal output was lossy/inconsistent.)
     */
    private fun coord(value: Double): String {
        val decimal = value.toBigDecimal().stripTrailingZeros()
        return if (decimal.scale() <= 0) decimal.setScale(1).toPlainString() else decimal.toPlainString()
    }

    private fun isoUtc(epochMs: Long): String = isoFormat().format(Date(epochMs))

    private fun fileStamp(epochMs: Long): String = fileStampFormat().format(Date(epochMs))

    private fun isoFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun fileStampFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun sanitize(raw: String): String = raw.replace(Regex("[^A-Za-z0-9_-]"), "-").take(MAX_SUFFIX_LEN)

    private fun escapeXml(raw: String): String =
        raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private const val GPX_CREATOR = "Volt Tracker"
    private const val GPX_INITIAL_CAPACITY = 4096
    private const val CSV_INITIAL_CAPACITY = 4096
    private const val MAX_SUFFIX_LEN = 40

    // Leading characters that make a spreadsheet treat a CSV cell as a formula (CSV/DDE injection).
    private val CSV_FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')
}
