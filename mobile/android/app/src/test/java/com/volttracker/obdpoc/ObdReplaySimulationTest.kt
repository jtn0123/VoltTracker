package com.volttracker.obdpoc

import android.content.Context
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Recorded-log style simulations for OBD sessions. Each scenario feeds timestamped raw ELM frames
 * through the production parsers, persists the decoded samples in the real SQLite store, then reads
 * the same dashboard projections used by the WebView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdReplaySimulationTest {
    private lateinit var context: Context
    private lateinit var store: ObdLocalStore
    private lateinit var reader: DashboardStorageReader

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = ObdLocalStore(context)
        store.clearAllData()
        reader = DashboardStorageReader { store }
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun replayedMovingDriveFeedsVehicleTripRouteAndReviewProjections() {
        val replay = replaySession("recorded commute", movingDriveBatches())

        val summary = JSONObject(reader.storageSummaryJson())
        assertEquals(1, summary.optInt("sessionCount"))
        assertEquals(replay.sessionId, summary.optLong("lastSessionId"))
        assertEquals(replay.persistedSamples.toLong(), summary.optLong("sampleCount"))
        assertEquals(1, summary.optInt("vehicleCount"))
        assertTrue("replay should store PID observations", summary.optLong("pidObservationCount") >= 30L)
        assertTrue("replay should store route fixes", summary.optLong("locationSampleCount") >= 6L)

        val details = JSONObject(reader.storageDetailsJson())
        val review = details.getJSONObject("latestReview")
        assertEquals(replay.sessionId, review.getJSONObject("session").optLong("id"))
        assertTrue("review should count replayed samples", review.optLong("usefulTelemetryCount") >= 6L)
        assertTrue("review should expose the fastest decoded frame", review.optInt("maxSpeedKph") >= 58)
        assertTrue(
            "most replayed frames should parse",
            review.optLong("parsedPidCount") > review.optLong("unknownPidCount"),
        )

        val vehicle = details.getJSONObject("latestVehicle")
        assertEquals("Chevrolet", vehicle.optString("make"))
        assertEquals(2018, vehicle.optInt("modelYear"))
        assertTrue("dashboard should show only the redacted VIN suffix", vehicle.optString("vin").endsWith("2020"))
        assertFalse("dashboard must not expose the raw VIN", vehicle.optString("vin").contains("1G1ZD5"))

        val route = details.getJSONObject("latestRoute")
        assertTrue("route projection should have replayed geometry", route.optInt("pointCount") >= 6)
        assertTrue("route should accumulate realistic movement", route.optDouble("distanceMeters") > 350.0)

        val trips = JSONArray(reader.tripsJson())
        assertEquals(1, trips.length())
        val trip = trips.getJSONObject(0)
        assertEquals(replay.sessionId, trip.optLong("sessionId"))
        assertTrue("moving replay should keep a renderable trip route", trip.optBoolean("hasRoute"))
        assertTrue("trip distance should come from replayed GPS", trip.optDouble("distanceMeters") > 350.0)
    }

    @Test
    fun replayedAdapterDropKeepsPartialDriveAndSurfacesDashboardWarnings() {
        val replay = replaySession("dropout commute", dropoutDriveBatches())

        val details = JSONObject(reader.storageDetailsJson())
        val review = details.getJSONObject("latestReview")
        val warnings = warningsByCode(review)

        assertEquals(replay.sessionId, review.getJSONObject("session").optLong("id"))
        assertTrue("partial drive still persists useful samples", review.optLong("usefulTelemetryCount") >= 5L)
        assertTrue("malformed replay frame should be visible as PID warning", warnings.containsKey("pid-unparsed"))
        assertTrue("dropout replay should surface the sample gap warning", warnings.containsKey("sample-gap"))
        assertTrue(
            "timeline should keep the simulated adapter drop",
            timelineContains(review, "adapter_link_dropped"),
        )

        val route = details.getJSONObject("latestRoute")
        assertTrue("route should survive a transient adapter drop", route.optInt("pointCount") >= 5)
        assertTrue("partial route should still move", route.optDouble("distanceMeters") > 250.0)

        val trips = JSONArray(reader.tripsJson())
        assertEquals(1, trips.length())
        assertEquals(replay.sessionId, trips.getJSONObject(0).optLong("sessionId"))
    }

    @Test
    fun replayedChargingSessionMaterializesChargeWithoutCreatingTrip() {
        val replay = replaySession("garage charging", chargingBatches())

        val summary = JSONObject(reader.storageSummaryJson())
        assertEquals(replay.sessionId, summary.optLong("lastSessionId"))
        assertEquals(replay.persistedSamples.toLong(), summary.optLong("sampleCount"))
        assertTrue("charging replay should materialize a charge session", summary.optInt("chargeSessionCount") >= 1)

        val details = JSONObject(reader.storageDetailsJson())
        val review = details.getJSONObject("latestReview")
        assertEquals(replay.sessionId, review.getJSONObject("session").optLong("id"))
        assertEquals(0, review.optInt("maxSpeedKph"))
        assertTrue(
            "stationary charging samples are still useful telemetry",
            review.optLong("usefulTelemetryCount") >= 4L,
        )

        val charge = details.getJSONObject("chargeSummary")
        assertTrue(charge.optInt("chargeSessionCount") >= 1)
        val latest = charge.getJSONObject("latest")
        assertTrue("SOC should rise during replayed charge", latest.optDouble("endSoc") > latest.optDouble("startSoc"))
        assertTrue(
            "charge power should be derived from replayed pack voltage/current",
            latest.optDouble("powerKw") > 5.0,
        )

        val latestRoute = details.getJSONObject("latestRoute")
        assertEquals("charging session should not invent GPS points", 0, latestRoute.optInt("pointCount"))
        assertEquals("charging session should not appear in trips", 0, JSONArray(reader.tripsJson()).length())
    }

    @Test
    fun replayedDiagnosticScanFeedsDashboardDtcSummary() {
        val replay = replaySession("quick dtc scan", diagnosticScanBatches())

        val summary = JSONObject(reader.storageSummaryJson())
        assertEquals(replay.sessionId, summary.optLong("lastSessionId"))
        assertEquals(4L, summary.optLong("diagnosticCodeCount"))
        val statusCounts = summary.getJSONObject("diagnosticCodeStatusCounts")
        assertEquals(2L, statusCounts.optLong("stored"))
        assertEquals(1L, statusCounts.optLong("pending"))
        assertEquals(1L, statusCounts.optLong("permanent"))

        val latest = summary.getJSONArray("latestDiagnosticCodes")
        val statuses = diagnosticStatuses(latest)
        assertTrue(statuses.contains("P0133:stored"))
        assertTrue(statuses.contains("P25A2:stored"))
        assertTrue(statuses.contains("U0073:pending"))
        assertTrue(statuses.contains("P25A2:permanent"))

        val details = JSONObject(reader.storageDetailsJson())
        val review = details.getJSONObject("latestReview")
        assertEquals(replay.sessionId, review.getJSONObject("session").optLong("id"))
        assertTrue("DTC replay still records PID frame evidence", review.optLong("pidObservationCount") >= 4L)
        assertTrue(
            "timeline should keep scan completion status",
            timelineContains(review, "dtc_scan_complete"),
        )
    }

    @Test
    fun replayedBatteryHealthFramesFeedSohHistoryAndBatterySummary() {
        val replay = replaySession("battery health replay", batteryHealthBatches())

        val summary = JSONObject(reader.storageSummaryJson())
        assertEquals(replay.sessionId, summary.optLong("lastSessionId"))
        assertEquals(3L, summary.optLong("batterySnapshotCount"))

        val history = JSONArray(reader.batterySohHistoryJson())
        assertEquals(3, history.length())
        assertEquals(51.7, history.getJSONObject(0).optDouble("capacityAh"), 0.001)
        assertEquals(99.4, history.getJSONObject(0).optDouble("sohPct"), 0.001)
        assertEquals(48.0, history.getJSONObject(2).optDouble("capacityAh"), 0.001)
        assertEquals(92.3, history.getJSONObject(2).optDouble("sohPct"), 0.001)

        val batterySummary =
            JSONObject(reader.storageDetailsJson())
                .getJSONObject("batterySummary")
        assertEquals(3L, batterySummary.optLong("snapshotCount"))
        val latest = batterySummary.getJSONObject("latestBatterySnapshot")
        assertEquals(48.0, latest.optDouble("capacityAh"), 0.001)
        assertEquals(92.3, latest.optDouble("sohPct"), 0.001)
        assertEquals(352.1, latest.optDouble("packVoltage"), 0.1)
    }

    private fun replaySession(
        label: String,
        batches: List<ReplayBatch>,
    ): ReplayResult {
        require(batches.isNotEmpty()) { "Replay requires at least one batch." }
        val startedAtMs = batches.first().atMs
        val sessionId = store.startSession(ObdLocalStore.MODE_OBD, ADAPTER_ADDRESS, label, startedAtMs)
        var persistedSamples = 0
        for ((index, batch) in batches.withIndex()) {
            for (event in batch.events) {
                store.recordStatus(
                    sessionId,
                    event.state,
                    event.detail,
                    false,
                    JSONObject().put("updatedAt", batch.atMs).put("source", "replay"),
                )
            }
            val sample = sampleFromBatch(batch, startedAtMs, index + 1)
            for (frame in batch.frames) {
                persistPidFrame(sessionId, batch.atMs, frame)
                decodeIntoSample(sample, frame)
            }
            sample.put(
                "raw",
                batch.frames.joinToString("\n") { frame ->
                    "${frame.command}: ${ObdElmDecode.summarizeForStorage(frame.command, frame.response)}"
                },
            )
            val inserted = store.recordTelemetry(sessionId, sample, batch.atMs)
            if (inserted > 0) {
                persistedSamples += 1
                if (batch.latitude != null && batch.longitude != null) {
                    store.recordLocationSample(
                        sessionId,
                        batch.atMs,
                        "replay",
                        batch.latitude,
                        batch.longitude,
                        8.0,
                        null,
                        sample.optDouble("gpsSpeedMps", 0.0),
                        null,
                        0L,
                        null,
                    )
                }
            }
        }
        val endedAtMs = batches.last().atMs
        store.finalizeSession(
            sessionId,
            ObdLocalStore.STATUS_COMPLETE,
            endedAtMs,
            "4100FFFFFFFF",
            ADAPTER_ADDRESS,
            label,
            ObdLocalStore.MODE_OBD,
            persistedSamples,
            "",
        )
        store.materializeSession(sessionId, startedAtMs, endedAtMs)
        return ReplayResult(sessionId, persistedSamples)
    }

    private fun sampleFromBatch(
        batch: ReplayBatch,
        startedAtMs: Long,
        sampleNumber: Int,
    ): JSONObject =
        JSONObject()
            .put("source", "obd-replay")
            .put("updatedAt", batch.atMs)
            .put("sampleCount", sampleNumber)
            .put("sessionMs", batch.atMs - startedAtMs)
            .also { sample ->
                if (batch.vehicleState.isNotEmpty()) {
                    sample.put("vehicleState", batch.vehicleState)
                }
                if (batch.latitude != null && batch.longitude != null) {
                    sample.put("latitude", batch.latitude)
                    sample.put("longitude", batch.longitude)
                    sample.put("accuracyM", 8.0)
                }
            }

    private fun persistPidFrame(
        sessionId: Long,
        atMs: Long,
        frame: ReplayFrame,
    ) {
        val command = frame.command.uppercase(Locale.US)
        val parsed = ObdProtocol.parseKnownValue(command, frame.response)
        val vin = if (command == "0902") ObdProtocol.parseVin(frame.response) else null
        val diagnosticCodes = ObdProtocol.parseDiagnosticTroubleCodes(command, frame.response, headerFor(command))
        store.recordPidObservation(
            sessionId,
            atMs,
            command,
            headerFor(command),
            ObdElmDecode.pidForCommand(command),
            parsed?.name ?: ObdElmDecode.nameForCommand(command),
            parsed?.valueText ?: if (vin == null) "" else "redacted",
            parsed?.valueNumeric,
            parsed?.unit ?: "",
            command,
            ObdElmDecode.summarizeForStorage(command, frame.response),
        )
        for (code in diagnosticCodes) {
            store.recordDiagnosticCode(sessionId, SessionRecorder.diagnosticCodeJson(code, atMs, command))
        }
        if (parsed == null &&
            vin == null &&
            diagnosticCodes.isEmpty() &&
            shouldFlagParseFailure(command, frame.response)
        ) {
            store.recordEvent(
                sessionId,
                "status",
                "connected",
                "pid_parse_failed",
                false,
                JSONObject()
                    .put("updatedAt", atMs)
                    .put("command", command)
                    .put("response", ObdElmDecode.summarizeForStorage(command, frame.response)),
            )
        }
    }

    private fun decodeIntoSample(
        sample: JSONObject,
        frame: ReplayFrame,
    ) {
        val command = frame.command.uppercase(Locale.US)
        when (command) {
            "010D" -> {
                val speed = ObdProtocol.parseSpeedKph(frame.response)
                if (speed != null) {
                    sample.put("speedKph", speed)
                    sample.put("gpsSpeedMps", speed / 3.6)
                } else if (ObdProtocol.hasMaxSpeedSentinel(frame.response)) {
                    sample.put("chargeTransitionHint", true)
                }
            }
            "010C" -> ObdProtocol.parseRpm(frame.response)?.let { sample.put("rpm", it.roundToInt()) }
            "0105" -> ObdProtocol.parseCoolantC(frame.response)?.let { sample.put("coolantC", it) }
            "0104" -> ObdProtocol.parseEngineLoadPct(frame.response)?.let { sample.put("loadPct", it) }
            "0111" -> ObdProtocol.parseThrottlePct(frame.response)?.let { sample.put("throttlePct", it) }
            "015B" -> ObdProtocol.parseStateOfChargePct(frame.response)?.let { sample.put("soc", it) }
            "0142" -> ObdProtocol.parseControlModuleVoltage(frame.response)?.let { sample.put("voltage", it) }
            "ATRV" -> ObdProtocol.parseVoltage(frame.response)?.let { sample.put("voltage", it.toDouble()) }
            "222429" -> parsedNumeric(frame)?.let { sample.put("packVoltage", it) }
            "222414" -> parsedNumeric(frame)?.let { sample.put("packCurrentA", it) }
            "22434F" -> parsedNumeric(frame)?.let { sample.put("batteryTemp", it) }
            "2241A3" ->
                parsedNumeric(frame)?.let { capacityAh ->
                    sample.put("capacityAh", round1(capacityAh))
                    sample.put("capacityAhStaleMs", 0L)
                    sample.put("sohPct", round1(capacityAh / GEN2_NOMINAL_CAPACITY_AH * 100.0))
                }
            "0902" -> ObdProtocol.parseVin(frame.response)?.let { store.upsertVehicleFromVin(it) }
        }
        val packVoltage = if (sample.has("packVoltage")) sample.optDouble("packVoltage") else null
        val packCurrent = if (sample.has("packCurrentA")) sample.optDouble("packCurrentA") else null
        if (packVoltage != null && packCurrent != null) {
            sample.put("powerKw", packVoltage * packCurrent / 1000.0)
        }
    }

    private fun parsedNumeric(frame: ReplayFrame): Double? =
        ObdProtocol.parseKnownValue(frame.command, frame.response)?.valueNumeric

    private fun shouldFlagParseFailure(
        command: String,
        response: String,
    ): Boolean {
        if (ObdElmDecode.isNoDataResponse(response)) {
            return false
        }
        if (isPositiveDtcResponse(command, response)) {
            return false
        }
        return !ObdProtocol.isBenignSentinelResponse(command, response)
    }

    private fun isPositiveDtcResponse(
        command: String,
        response: String,
    ): Boolean {
        val marker =
            when (command) {
                "03" -> "43"
                "07" -> "47"
                "0A" -> "4A"
                "0202" -> "4202"
                else -> return false
            }
        return ObdProtocol
            .summarize(response)
            .uppercase(Locale.US)
            .replace(Regex("[^0-9A-F]"), "")
            .contains(marker)
    }

    private fun headerFor(command: String): String =
        when {
            command.startsWith("22") -> "7E1"
            command.startsWith("09") -> "7E0"
            else -> "7DF"
        }

    private fun warningsByCode(review: JSONObject): Map<String, JSONObject> {
        val warnings = review.getJSONArray("warnings")
        val byCode = LinkedHashMap<String, JSONObject>()
        for (i in 0 until warnings.length()) {
            val item = warnings.getJSONObject(i)
            byCode[item.getString("code")] = item
        }
        return byCode
    }

    private fun timelineContains(
        review: JSONObject,
        detail: String,
    ): Boolean {
        val timeline = review.getJSONArray("timeline")
        for (i in 0 until timeline.length()) {
            if (timeline.getJSONObject(i).optString("detail") == detail) {
                return true
            }
        }
        return false
    }

    private fun diagnosticStatuses(codes: JSONArray): Set<String> {
        val out = LinkedHashSet<String>()
        for (i in 0 until codes.length()) {
            val code = codes.getJSONObject(i)
            out.add("${code.optString("dtc")}:${code.optString("status")}")
        }
        return out
    }

    private fun movingDriveBatches(): List<ReplayBatch> =
        listOf(
            driveBatch(
                10_000L,
                replayLat(0.0),
                replayLng(0.0),
                speed = "41 0D 24\r>",
                rpm = "41 0C 17 70\r>",
                soc = "41 5B A6\r>",
                packCurrent = "62 24 14 00 4E\r>",
                includeVin = true,
            ),
            driveBatch(20_000L, replayLat(0.00085), replayLng(0.00060), "41 0D 2E\r>", "41 0C 1A F8\r>", "41 5B A4\r>"),
            driveBatch(30_000L, replayLat(0.00170), replayLng(0.00115), "41 0D 39\r>", "41 0C 1D 4C\r>", "41 5B A2\r>"),
            driveBatch(40_000L, replayLat(0.00265), replayLng(0.00175), "41 0D 3A\r>", "41 0C 20 D0\r>", "41 5B A1\r>"),
            driveBatch(50_000L, replayLat(0.00355), replayLng(0.00225), "41 0D 35\r>", "41 0C 1F 40\r>", "41 5B A0\r>"),
            driveBatch(60_000L, replayLat(0.00435), replayLng(0.00280), "41 0D 2B\r>", "41 0C 1B 58\r>", "41 5B 9E\r>"),
        )

    private fun dropoutDriveBatches(): List<ReplayBatch> =
        listOf(
            driveBatch(
                110_000L,
                replayLat(0.01000),
                replayLng(0.01000),
                "41 0D 20\r>",
                "41 0C 15 7C\r>",
                "41 5B A0\r>",
            ),
            driveBatch(
                120_000L,
                replayLat(0.01075),
                replayLng(0.01055),
                "41 0D 2C\r>",
                "41 0C 19 64\r>",
                "41 5B 9F\r>",
            ),
            ReplayBatch(
                atMs = 135_000L,
                latitude = replayLat(0.01145),
                longitude = replayLng(0.01110),
                frames =
                    listOf(
                        ReplayFrame("010D", "41 0D\r>"),
                        ReplayFrame("010C", "CAN ERROR\r>"),
                        ReplayFrame("015B", "41 5B 9E\r>"),
                        ReplayFrame("ATRV", "14.0V\r>"),
                    ),
                events = listOf(ReplayEvent("connected", "sample_gap")),
            ),
            ReplayBatch(
                atMs = 150_000L,
                latitude = replayLat(0.01225),
                longitude = replayLng(0.01165),
                frames =
                    listOf(
                        ReplayFrame("010D", "41 0D 31\r>"),
                        ReplayFrame("010C", "41 0C 1C 20\r>"),
                        ReplayFrame("015B", "41 5B 9D\r>"),
                        ReplayFrame("ATRV", "14.1V\r>"),
                    ),
                events = listOf(ReplayEvent("reconnecting", "adapter_link_dropped")),
            ),
            driveBatch(
                160_000L,
                replayLat(0.01300),
                replayLng(0.01220),
                "41 0D 35\r>",
                "41 0C 1E 78\r>",
                "41 5B 9C\r>",
            ),
            driveBatch(
                170_000L,
                replayLat(0.01380),
                replayLng(0.01280),
                "41 0D 2F\r>",
                "41 0C 1B 58\r>",
                "41 5B 9B\r>",
            ),
        )

    private fun chargingBatches(): List<ReplayBatch> =
        listOf(
            chargeBatch(210_000L, "41 5B 80\r>", "62 24 29 5A 00\r>", "62 24 14 FD A8\r>"),
            chargeBatch(450_000L, "41 5B 88\r>", "62 24 29 5A 20\r>", "62 24 14 FD 94\r>"),
            chargeBatch(690_000L, "41 5B 90\r>", "62 24 29 5A 30\r>", "62 24 14 FD 80\r>"),
            chargeBatch(930_000L, "41 5B 98\r>", "62 24 29 5A 10\r>", "62 24 14 FD 9E\r>"),
        )

    private fun diagnosticScanBatches(): List<ReplayBatch> =
        listOf(
            ReplayBatch(
                atMs = 1_010_000L,
                frames =
                    listOf(
                        ReplayFrame("ATRV", "13.9V\r>"),
                        ReplayFrame("03", "7E8 06 43 02 01 33 25 A2\r>"),
                        ReplayFrame("07", "47 01 C0 73 00 00\r>"),
                        ReplayFrame("0A", "4A 01 25 A2 00 00\r>"),
                    ),
                events = listOf(ReplayEvent("scan-complete", "dtc_scan_complete")),
            ),
        )

    private fun batteryHealthBatches(): List<ReplayBatch> =
        listOf(
            healthBatch(1_110_000L, "41 5B A0\r>", "62 41 A3 02 05\r>"),
            healthBatch(1_170_000L, "41 5B 9E\r>", "62 41 A3 01 F4\r>"),
            healthBatch(1_230_000L, "41 5B 9C\r>", "62 41 A3 01 E0\r>"),
        )

    private fun driveBatch(
        atMs: Long,
        latitude: Double,
        longitude: Double,
        speed: String,
        rpm: String,
        soc: String,
        packCurrent: String = "62 24 14 00 64\r>",
        includeVin: Boolean = false,
    ): ReplayBatch {
        val frames =
            mutableListOf(
                ReplayFrame("010D", speed),
                ReplayFrame("010C", rpm),
                ReplayFrame("015B", soc),
                ReplayFrame("ATRV", "14.1V\r>"),
                ReplayFrame("222429", "62 24 29 58 06\r>"),
                ReplayFrame("222414", packCurrent),
                ReplayFrame("22434F", "62 43 4F 45\r>"),
            )
        if (includeVin) {
            frames.add(ReplayFrame("0902", mode09VinResponse("1G1ZD5ST8JF" + "202020")))
        }
        return ReplayBatch(atMs, latitude, longitude, frames)
    }

    private fun replayLat(offset: Double): Double = 10.0 + offset

    private fun replayLng(offset: Double): Double = 20.0 + offset

    private fun chargeBatch(
        atMs: Long,
        soc: String,
        packVoltage: String,
        packCurrent: String,
    ): ReplayBatch =
        ReplayBatch(
            atMs = atMs,
            vehicleState = "charging",
            frames =
                listOf(
                    ReplayFrame("010D", "41 0D 00\r>"),
                    ReplayFrame("015B", soc),
                    ReplayFrame("ATRV", "14.2V\r>"),
                    ReplayFrame("222429", packVoltage),
                    ReplayFrame("222414", packCurrent),
                ),
        )

    private fun healthBatch(
        atMs: Long,
        soc: String,
        capacity: String,
    ): ReplayBatch =
        ReplayBatch(
            atMs = atMs,
            frames =
                listOf(
                    ReplayFrame("010D", "41 0D 00\r>"),
                    ReplayFrame("015B", soc),
                    ReplayFrame("222429", "62 24 29 58 06\r>"),
                    ReplayFrame("222414", "62 24 14 00 10\r>"),
                    ReplayFrame("22434F", "62 43 4F 40\r>"),
                    ReplayFrame("2241A3", capacity),
                ),
        )

    private fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0

    private fun mode09VinResponse(vin: String): String {
        val hex = StringBuilder("490201")
        for (c in vin.toCharArray()) {
            hex.append(String.format(Locale.US, "%02X", c.code))
        }
        return hex.append("\r>").toString()
    }

    private data class ReplayResult(
        val sessionId: Long,
        val persistedSamples: Int,
    )

    private data class ReplayBatch(
        val atMs: Long,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val frames: List<ReplayFrame>,
        val events: List<ReplayEvent> = emptyList(),
        val vehicleState: String = "",
    )

    private data class ReplayFrame(
        val command: String,
        val response: String,
    )

    private data class ReplayEvent(
        val state: String,
        val detail: String,
    )

    private companion object {
        private const val ADAPTER_ADDRESS = "AA:BB:CC:DD:EE:FF"
        private const val GEN2_NOMINAL_CAPACITY_AH = 52.0
    }
}
