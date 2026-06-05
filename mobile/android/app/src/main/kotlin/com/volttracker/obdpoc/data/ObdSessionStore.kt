package com.volttracker.obdpoc.data

import com.volttracker.obdpoc.materialize.ChargeSession
import com.volttracker.obdpoc.materialize.Trip
import org.json.JSONObject
import java.io.Closeable

/** Write- and lifecycle-side of the on-device SQLite store. */
interface ObdSessionStore : Closeable {
    fun startSession(
        mode: String?,
        adapterAddress: String?,
        adapterName: String?,
    ): Long

    fun startSession(
        mode: String?,
        adapterAddress: String?,
        adapterName: String?,
        startedAtMs: Long,
    ): Long

    fun finishSession(
        sessionId: Long,
        status: String?,
    )

    fun finishSession(
        sessionId: Long,
        status: String?,
        endedAtMs: Long,
        supportedPids: String?,
    )

    fun finalizeSession(
        sessionId: Long,
        status: String?,
        endedAtMs: Long,
        supportedPids: String?,
        address: String?,
        adapterName: String?,
        mode: String?,
        sampleCount: Int,
        lastEventDetail: String?,
    )

    fun recordTelemetry(
        sessionId: Long,
        sample: JSONObject?,
    ): Long

    fun recordTelemetry(
        sessionId: Long,
        sample: JSONObject?,
        capturedAtMs: Long,
    ): Long

    fun recordPidObservation(
        sessionId: Long,
        observation: JSONObject?,
    ): Long

    fun recordPidObservation(
        sessionId: Long,
        observation: JSONObject?,
        observedAtMs: Long,
    ): Long

    fun recordPidObservation(
        sessionId: Long,
        observedAtMs: Long,
        command: String?,
        header: String?,
        pid: String?,
        name: String?,
        valueText: String?,
        valueNumeric: Double?,
        unit: String?,
        rawRequest: String?,
        rawResponse: String?,
    ): Long

    fun recordDiagnosticCode(
        sessionId: Long,
        diagnosticCode: JSONObject?,
    ): Long

    fun recordLocationSample(
        sessionId: Long,
        sample: JSONObject?,
    ): Long

    fun recordLocationSample(
        sessionId: Long,
        sample: JSONObject?,
        capturedAtMs: Long,
    ): Long

    fun recordLocationSample(
        sessionId: Long,
        capturedAtMs: Long,
        provider: String?,
        latitude: Double,
        longitude: Double,
        accuracyM: Double?,
        altitudeM: Double?,
        speedMps: Double?,
        bearingDeg: Double?,
        locationAgeMs: Long?,
        elapsedRealtimeNanos: Long?,
    ): Long

    fun recordStatus(
        sessionId: Long,
        state: String?,
        detail: String?,
        blocked: Boolean,
        payload: JSONObject?,
    ): Long

    fun recordEvent(
        sessionId: Long,
        kind: String?,
        state: String?,
        detail: String?,
        blocked: Boolean,
        payload: JSONObject?,
    ): Long

    fun upsertVehicleFromVin(vin: String?): Long

    fun recordAdapterSummary(
        address: String?,
        name: String?,
        mode: String?,
        sessionId: Long,
        status: String?,
        samples: Int,
        supportedPids: String?,
        lastEventDetail: String?,
    )

    fun persistTrips(
        sessionId: Long,
        trips: List<Trip>?,
    )

    fun persistChargeSessions(
        sessionId: Long,
        sessions: List<ChargeSession>?,
    )

    fun clearAllData()

    fun checkpoint()

    fun pruneRawDataOlderThan(keepDays: Int): Int

    override fun close()
}
