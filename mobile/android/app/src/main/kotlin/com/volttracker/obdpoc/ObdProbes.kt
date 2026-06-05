package com.volttracker.obdpoc

import java.util.UUID

/**
 * Static ELM327 connection parameters and the OBD probe command lists used by [ObdPollingEngine].
 */
object ObdProbes {
    @JvmField val ELM327_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val CONNECT_TIMEOUT_MS: Long = 15000L
    const val MAX_RECONNECT_ATTEMPTS: Int = 6

    @JvmField val PROTOCOL_PROBES: Array<String> = arrayOf("ATSP0", "ATSP6", "ATSP7", "ATSP8")

    @JvmField val CAPABILITY_PROBES: Array<String> = arrayOf("0100", "0120", "0140", "0160", "0180", "01A0")

    @JvmField
    val LIVE_PROBES: Array<String> =
        arrayOf(
            "ATRV",
            "010D",
            "010C",
            "0105",
            "0104",
            "0111",
            "0149",
            "015B",
            "0142",
            "011F",
            "012F",
            "010F",
            "015C",
            "01A6",
        )

    @JvmField
    val VOLT_7E1_PROBES: Array<String> =
        arrayOf("222429", "222414", "222883", "222884", "222885", "222886", "222487", "222889")

    @JvmField val VOLT_7E0_PROBES: Array<String> = arrayOf("22119F", "22119F01", "221154", "22203F")

    @JvmField val VOLT_7E2_PROBES: Array<String> = arrayOf("221940", "22194001")

    @JvmField
    val VOLT_7E4_PROBES: Array<String> =
        arrayOf(
            "22434F",
            "224368",
            "224369",
            "22436B",
            "22436C",
            "224373",
            "22437D",
            "2243A5",
            "2243AF",
            "224531",
            "228334",
            "2241B2",
            "2241B4",
            "2241B6",
            "22801E",
            "22801F",
        )

    @JvmField
    val TPMS_7E0_DISCOVERY_PROBES: Array<String> =
        arrayOf("22248E", "22248F", "222490", "222491", "22C901", "22C902")

    @JvmField val TPMS_760_DISCOVERY_PROBES: Array<String> = arrayOf("224051", "224052", "224053", "224054")
}
