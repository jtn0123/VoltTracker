package com.volttracker.obdpoc

import com.volttracker.obdpoc.ObdElmDecode.round1
import com.volttracker.obdpoc.classify.ClassifierInput
import com.volttracker.obdpoc.classify.VehicleStateClassifier
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Builds one live OBD telemetry sample from the current PID polling state. */
class LiveSampleReader(
    private val service: ObdService,
    private val speedFilter: SpeedPlausibilityFilter,
    private val pidPolling: PidPollingState,
) {
    interface SampleContext {
        fun incrementSampleCount(): Int

        fun supportedPidsSummary(): String

        @Throws(JSONException::class)
        fun appendSessionHealth(sample: JSONObject)

        @Throws(JSONException::class)
        fun appendLocation(sample: JSONObject)

        fun redactedVin(): String?
    }

    @Throws(IOException::class)
    fun read(context: SampleContext): JSONObject {
        val sample = JSONObject()
        val rawThisCycle = StringBuilder()
        try {
            val isInitialCycle = pidPolling.isInitialCycle()
            val due = pidPolling.dueForCurrentCycle()
            pidPolling.runScheduledPolls(due, rawThisCycle)
            pidPolling.advanceCycle()

            val voltageRaw = pidPolling.lastRaw("ATRV")
            val voltage = ObdProtocol.parseVoltage(voltageRaw)
            if (voltage != null) {
                sample.put("voltage", voltage)
            }

            val speedRaw = pidPolling.lastRaw("010D")
            val speed = ObdProtocol.parseSpeedKph(speedRaw)
            val polledSpeedThisCycle =
                if (!isInitialCycle) PidPollingState.wasPolledThisCycle(due, "010D") else true
            val chargeTransitionHint =
                polledSpeedThisCycle && ObdProtocol.hasMaxSpeedSentinel(speedRaw)
            val acceptedSpeed = appendSpeed(sample, speed, polledSpeedThisCycle)
            if (acceptedSpeed == null && polledSpeedThisCycle && chargeTransitionHint) {
                sample.put("speedRejectedKph", 255)
                sample.put("chargeTransitionHint", true)
                service.recorder.logEvent(
                    "speed_rejected",
                    "speedKph",
                    "255",
                    "reason",
                    "charge_transition_hint",
                )
            }

            val rpm = ObdProtocol.parseRpm(pidPolling.lastRaw("010C"))
            if (rpm != null) {
                sample.put("rpm", Math.round(rpm))
            }
            val coolant = ObdProtocol.parseCoolantC(pidPolling.lastRaw("0105"))
            if (coolant != null) {
                sample.put("coolantC", coolant)
            }
            putNumeric(sample, "intakeAirTempC", "010F", 0)
            val load = ObdProtocol.parseEngineLoadPct(pidPolling.lastRaw("0104"))
            if (load != null) {
                sample.put("loadPct", load)
            }
            appendThrottle(sample)
            val soc = ObdProtocol.parseStateOfChargePct(pidPolling.lastRaw("015B"))
            if (soc != null) {
                sample.put("soc", soc)
            }
            appendStandardContextFields(sample)

            val packVoltageRaw = pidPolling.lastRaw("222429")
            val packCurrentRaw = pidPolling.lastRaw("222414")
            appendBatteryFields(sample, packVoltageRaw, packCurrentRaw)
            appendChargingFields(sample)
            appendEnhancedContextFields(sample)

            val now = System.currentTimeMillis()
            pidPolling.putStaleMsIfTracked(sample, "voltageStaleMs", "ATRV", now)
            pidPolling.putStaleMsIfTracked(sample, "speedKphStaleMs", "010D", now)
            pidPolling.putStaleMsIfTracked(sample, "rpmStaleMs", "010C", now)
            pidPolling.putStaleMsIfTracked(sample, "loadPctStaleMs", "0104", now)
            putThrottleStaleMsIfKnown(sample, now)
            pidPolling.putStaleMsIfTracked(sample, "socStaleMs", "015B", now)
            putStaleMsForPresentValue(
                sample,
                "controlModuleVoltage",
                "controlModuleVoltageStaleMs",
                "0142",
                now,
            )
            putStaleMsForPresentValue(sample, "engineRunTimeSec", "engineRunTimeStaleMs", "011F", now)
            putStaleMsForPresentValue(sample, "fuelLevelPct", "fuelLevelStaleMs", "012F", now)
            putStaleMsForPresentValue(sample, "intakeAirTempC", "intakeAirTempStaleMs", "010F", now)
            putStaleMsForFirstPresentValue(
                sample,
                "engineOilTempC",
                "engineOilTempStaleMs",
                now,
                "221154",
                "015C",
            )
            putStaleMsForPresentValue(sample, "odometerKm", "odometerStaleMs", "01A6", now)
            pidPolling.putStaleMsIfTracked(sample, "coolantCStaleMs", "0105", now)
            pidPolling.putStaleMsIfTracked(sample, "batteryTempStaleMs", "22434F", now)
            pidPolling.putStaleMsIfTracked(sample, "packVoltageStaleMs", "222429", now)
            pidPolling.putStaleMsIfTracked(sample, "packCurrentAStaleMs", "222414", now)
            putPowerStaleMsIfKnown(sample, now)
            putChargingStaleMs(sample, now)
            putEnhancedContextStaleMs(sample, now)

            val sampleCount = context.incrementSampleCount()
            sample.put("source", "obd")
            sample.put("connected", true)
            sample.put("adapter", service.activeName)
            val redactedVin = context.redactedVin()
            if (!redactedVin.isNullOrEmpty()) {
                sample.put("vin", redactedVin)
            }
            sample.put("sampleCount", sampleCount)
            sample.put("sessionMs", maxOf(0, now - service.sessionStartedAtMs))
            sample.put("supportedPids", context.supportedPidsSummary())
            val packCurrentA = appendPackCurrent(sample, packCurrentRaw)
            appendVehicleState(sample, acceptedSpeed, rpm, voltage, packCurrentA, chargeTransitionHint, now)
            sample.put("updatedAt", now)
            context.appendSessionHealth(sample)
            context.appendLocation(sample)
            sample.put("raw", ObdPollingEngine.boundedRawTranscript(rawThisCycle))
        } catch (ex: JSONException) {
            service.recorder.logError("sample_encoding_error", ex)
            return JSONObject()
        }
        return sample
    }

    @Throws(JSONException::class)
    private fun appendSpeed(
        sample: JSONObject,
        speed: Int?,
        polledSpeedThisCycle: Boolean,
    ): Int? {
        if (polledSpeedThisCycle) {
            if (speed != null && speedFilter.accept(speed, System.currentTimeMillis())) {
                sample.put("speedKph", speed)
                return speed
            }
            if (speed != null) {
                sample.put("speedRejectedKph", speed)
                service.recorder.logEvent("speed_rejected", "speedKph", speed.toString())
            }
            return null
        }
        if (speed != null) {
            sample.put("speedKph", speed)
            return speed
        }
        return null
    }

    @Throws(JSONException::class)
    private fun appendStandardContextFields(sample: JSONObject) {
        putNumeric(sample, "controlModuleVoltage", "0142", 2)
        putNumeric(sample, "engineRunTimeSec", "011F", 0)
        putNumeric(sample, "fuelLevelPct", "012F", 0)
        putNumericFirst(sample, "engineOilTempC", 0, "221154", "015C")
        putNumeric(sample, "odometerKm", "01A6", 1)
        if (sample.has("odometerKm")) {
            sample.put("odometerMiles", round1(sample.optDouble("odometerKm") * 0.621371))
        }
    }

    @Throws(JSONException::class)
    private fun appendThrottle(sample: JSONObject) {
        val pedal = ObdProtocol.parseAccelPedalPct(pidPolling.lastRaw("0149"))
        if (pedal != null) {
            sample.put("throttlePct", pedal)
            sample.put("throttleSource", "accelPedal")
            return
        }
        val throttle = ObdProtocol.parseThrottlePct(pidPolling.lastRaw("0111"))
        if (throttle != null) {
            sample.put("throttlePct", throttle)
            sample.put("throttleSource", "iceThrottleBody")
        }
    }

    @Throws(JSONException::class)
    private fun putThrottleStaleMsIfKnown(
        sample: JSONObject,
        now: Long,
    ) {
        if (!sample.has("throttlePct")) {
            return
        }
        val source = sample.optString("throttleSource", "")
        val command = if (source == "iceThrottleBody") "0111" else "0149"
        pidPolling.putStaleMsIfTracked(sample, "throttlePctStaleMs", command, now)
    }

    @Throws(JSONException::class)
    private fun appendBatteryFields(
        sample: JSONObject,
        packVoltageRaw: String?,
        packCurrentRaw: String?,
    ) {
        val batteryTemp = ObdProtocol.parseKnownValue("22434F", pidPolling.lastRaw("22434F"))
        if (batteryTemp?.valueNumeric != null) {
            sample.put("batteryTemp", round1(batteryTemp.valueNumeric))
        }
        val packVoltage = ObdProtocol.parseKnownValue("222429", packVoltageRaw)
        if (packVoltage?.valueNumeric != null) {
            sample.put("packVoltage", round1(packVoltage.valueNumeric))
        }
        val powerKw = ObdProtocol.parsePackPowerKw(packVoltageRaw, packCurrentRaw)
        if (powerKw != null) {
            sample.put("powerKw", round1(powerKw))
        }
        putNumeric(sample, "hvBatteryRawSoc", "2243AF", 2)
        putNumeric(sample, "hvBatteryChargeCount", "2243A5", 0)
        putNumeric(sample, "lastChargeEnergyWh", "22437D", 0)
    }

    @Throws(JSONException::class)
    private fun appendChargingFields(sample: JSONObject) {
        putNumeric(sample, "chargerHvVoltage", "22436B", 1)
        putNumeric(sample, "chargerHvCurrent", "22436C", 2)
        putDerivedChargerPower(sample)
        putText(sample, "chargingMode", "224373")
        putText(sample, "chargingLevel", "224531")
    }

    @Throws(JSONException::class)
    private fun appendEnhancedContextFields(sample: JSONObject) {
        putNumericFirst(sample, "engineOilLifePct", 0, "22119F01", "22119F")
        putNumeric(sample, "engineTorqueNm", "22203F", 1)
        putNumeric(sample, "motorACurrentA", "222883", 1)
        putNumeric(sample, "motorBCurrentA", "222884", 1)
        putNumeric(sample, "motorAVoltage", "222885", 1)
        putNumeric(sample, "motorBVoltage", "222886", 1)
        putDerivedMotorPower(sample, "motorAPowerKw", "222885", "222883")
        putDerivedMotorPower(sample, "motorBPowerKw", "222886", "222884")
        putNumeric(sample, "evDistanceThisCycleKm", "222487", 2)
        putText(sample, "prndlState", "222889")
        putNumericFirst(sample, "transmissionTempC", 0, "22194001", "221940")
        putNumeric(sample, "batteryCoolantPumpRpm", "2241B2", 0)
        putNumeric(sample, "batteryCoolantValveRaw", "2241B4", 0)
        putNumeric(sample, "batteryHeaterPowerW", "2241B6", 0)
        putNumeric(sample, "outsideTempRawC", "22801E", 1)
        putNumeric(sample, "outsideTempC", "22801F", 1)
    }

    @Throws(JSONException::class)
    private fun putPowerStaleMsIfKnown(
        sample: JSONObject,
        now: Long,
    ) {
        if (!sample.has("powerKw")) {
            return
        }
        val voltageStaleMs = pidPolling.staleMsFor("222429", now)
        val currentStaleMs = pidPolling.staleMsFor("222414", now)
        if (voltageStaleMs != null && currentStaleMs != null) {
            sample.put("powerKwStaleMs", maxOf(voltageStaleMs, currentStaleMs))
        }
    }

    @Throws(JSONException::class)
    private fun putChargingStaleMs(
        sample: JSONObject,
        now: Long,
    ) {
        putStaleMsForPresentValue(sample, "hvBatteryRawSoc", "hvBatteryRawSocStaleMs", "2243AF", now)
        putStaleMsForPresentValue(
            sample,
            "hvBatteryChargeCount",
            "hvBatteryChargeCountStaleMs",
            "2243A5",
            now,
        )
        putStaleMsForPresentValue(sample, "lastChargeEnergyWh", "lastChargeEnergyStaleMs", "22437D", now)
        putStaleMsForPresentValue(sample, "chargerHvVoltage", "chargerHvVoltageStaleMs", "22436B", now)
        putStaleMsForPresentValue(sample, "chargerHvCurrent", "chargerHvCurrentStaleMs", "22436C", now)
        putStaleMsForPresentValue(sample, "chargerPowerKw", "chargerPowerStaleMs", "22436C", now)
        putStaleMsForPresentValue(sample, "chargingMode", "chargingModeStaleMs", "224373", now)
        putStaleMsForPresentValue(sample, "chargingLevel", "chargingLevelStaleMs", "224531", now)
    }

    @Throws(JSONException::class)
    private fun putEnhancedContextStaleMs(
        sample: JSONObject,
        now: Long,
    ) {
        putStaleMsForFirstPresentValue(
            sample,
            "engineOilLifePct",
            "engineOilLifeStaleMs",
            now,
            "22119F01",
            "22119F",
        )
        putStaleMsForPresentValue(sample, "engineTorqueNm", "engineTorqueStaleMs", "22203F", now)
        putStaleMsForPresentValue(sample, "motorACurrentA", "motorAStaleMs", "222883", now)
        putStaleMsForPresentValue(sample, "motorBCurrentA", "motorBStaleMs", "222884", now)
        putStaleMsForPresentValue(
            sample,
            "evDistanceThisCycleKm",
            "evDistanceThisCycleStaleMs",
            "222487",
            now,
        )
        putStaleMsForPresentValue(sample, "prndlState", "prndlStateStaleMs", "222889", now)
        putStaleMsForFirstPresentValue(
            sample,
            "transmissionTempC",
            "transmissionTempStaleMs",
            now,
            "22194001",
            "221940",
        )
        putStaleMsForPresentValue(
            sample,
            "batteryCoolantPumpRpm",
            "batteryCoolantPumpStaleMs",
            "2241B2",
            now,
        )
        putStaleMsForPresentValue(
            sample,
            "batteryCoolantValveRaw",
            "batteryCoolantValveStaleMs",
            "2241B4",
            now,
        )
        putStaleMsForPresentValue(
            sample,
            "batteryHeaterPowerW",
            "batteryHeaterPowerStaleMs",
            "2241B6",
            now,
        )
        putStaleMsForPresentValue(sample, "outsideTempC", "outsideTempStaleMs", "22801F", now)
    }

    @Throws(JSONException::class)
    private fun putStaleMsForPresentValue(
        sample: JSONObject,
        valueKey: String,
        staleKey: String,
        command: String,
        now: Long,
    ) {
        if (sample.has(valueKey)) {
            pidPolling.putStaleMsIfTracked(sample, staleKey, command, now)
        }
    }

    @Throws(JSONException::class)
    private fun putStaleMsForFirstPresentValue(
        sample: JSONObject,
        valueKey: String,
        staleKey: String,
        now: Long,
        vararg commands: String,
    ) {
        if (!sample.has(valueKey)) {
            return
        }
        var bestStaleMs: Long? = null
        for (command in commands) {
            val staleMs = pidPolling.staleMsFor(command, now)
            if (staleMs != null && (bestStaleMs == null || staleMs < bestStaleMs)) {
                bestStaleMs = staleMs
            }
        }
        if (bestStaleMs != null) {
            sample.put(staleKey, bestStaleMs)
        }
    }

    @Throws(JSONException::class)
    private fun appendPackCurrent(
        sample: JSONObject,
        packCurrentRaw: String?,
    ): Double? {
        val packCurrent = ObdProtocol.parseKnownValue("222414", packCurrentRaw)
        val packCurrentA = packCurrent?.valueNumeric
        if (packCurrentA != null) {
            sample.put("packCurrentA", round1(packCurrentA))
        }
        return packCurrentA
    }

    @Throws(JSONException::class)
    private fun putNumeric(
        sample: JSONObject,
        key: String,
        command: String,
        decimals: Int,
    ) {
        val parsed = ObdProtocol.parseKnownValue(command, pidPolling.lastRaw(command))
        val numeric = parsed?.valueNumeric ?: return
        putRoundedNumeric(sample, key, numeric, decimals)
    }

    @Throws(JSONException::class)
    private fun putNumericFirst(
        sample: JSONObject,
        key: String,
        decimals: Int,
        vararg commands: String,
    ) {
        for (command in commands) {
            val parsed = ObdProtocol.parseKnownValue(command, pidPolling.lastRaw(command))
            val numeric = parsed?.valueNumeric
            if (numeric != null) {
                putRoundedNumeric(sample, key, numeric, decimals)
                return
            }
        }
    }

    @Throws(JSONException::class)
    private fun putRoundedNumeric(
        sample: JSONObject,
        key: String,
        value: Double,
        decimals: Int,
    ) {
        if (decimals <= 0) {
            sample.put(key, Math.round(value))
        } else if (decimals == 1) {
            sample.put(key, round1(value))
        } else {
            val scale = Math.pow(10.0, decimals.toDouble())
            sample.put(key, Math.round(value * scale) / scale)
        }
    }

    @Throws(JSONException::class)
    private fun putText(
        sample: JSONObject,
        key: String,
        command: String,
    ) {
        val parsed = ObdProtocol.parseKnownValue(command, pidPolling.lastRaw(command))
        if (parsed?.valueText != null && parsed.valueText.isNotEmpty()) {
            sample.put(key, parsed.valueText)
        }
    }

    @Throws(JSONException::class)
    private fun putDerivedChargerPower(sample: JSONObject) {
        val voltage = ObdProtocol.parseKnownValue("22436B", pidPolling.lastRaw("22436B"))
        val current = ObdProtocol.parseKnownValue("22436C", pidPolling.lastRaw("22436C"))
        if (voltage?.valueNumeric == null || current?.valueNumeric == null) {
            return
        }
        val powerKw = voltage.valueNumeric * current.valueNumeric / 1000.0
        sample.put("chargerPowerKw", round1(powerKw))
    }

    @Throws(JSONException::class)
    private fun putDerivedMotorPower(
        sample: JSONObject,
        key: String,
        voltageCommand: String,
        currentCommand: String,
    ) {
        val voltage = ObdProtocol.parseKnownValue(voltageCommand, pidPolling.lastRaw(voltageCommand))
        val current = ObdProtocol.parseKnownValue(currentCommand, pidPolling.lastRaw(currentCommand))
        if (voltage?.valueNumeric == null || current?.valueNumeric == null) {
            return
        }
        sample.put(key, round1(voltage.valueNumeric * current.valueNumeric / 1000.0))
    }

    @Throws(JSONException::class)
    private fun appendVehicleState(
        sample: JSONObject,
        acceptedSpeed: Int?,
        rpm: Float?,
        voltage: Float?,
        packCurrentA: Double?,
        chargeTransitionHint: Boolean,
        now: Long,
    ) {
        val engineRunningHint = rpm?.let { it > 200f }
        val input =
            try {
                ClassifierInput(
                    acceptedSpeed?.toDouble(),
                    rpm?.let { Math.round(it) },
                    voltage?.toDouble(),
                    packCurrentA,
                    if (chargeTransitionHint) true else null,
                    engineRunningHint,
                    now,
                )
            } catch (ex: IllegalArgumentException) {
                service.recorder.logError("classifier_input_rejected", ex)
                ClassifierInput(null, null, null, null, null, null, now)
            }
        val classified = VehicleStateClassifier.classify(input)
        sample.put("vehicleState", classified.state.asPayloadKey())
        sample.put("vehicleStateConfidence", classified.confidence.asPayloadKey())
        sample.put("vehicleStateReasons", JSONArray(classified.reasons))
    }
}
