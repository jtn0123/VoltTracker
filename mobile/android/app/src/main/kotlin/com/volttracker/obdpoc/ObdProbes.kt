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
            "0132",
        )

    @JvmField
    val VOLT_7E1_PROBES: Array<String> =
        arrayOf(
            "222429",
            "222414",
            "222883",
            "222884",
            "222885",
            "222886",
            "222487",
            "222889",
            "221141",
            "221C26",
            "221C28",
            "221C2A",
            "2228CB",
            "22242C",
            "2224B0",
        )

    @JvmField val VOLT_7E0_PROBES: Array<String> = arrayOf("22119F", "22119F01", "221154", "221C47")

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
            "2241A3",
            "2245F9",
            "224329",
            "22432A",
            "22432B",
            "22432C",
            "22435F",
            "2240E9",
            "22433B",
            "22433C",
            "224349",
            "22434A",
            "22434B",
            "22434C",
            "221C43",
            "2241A4",
            "22433F",
            "2241B0",
            "22437E",
            "2243A6",
            "2241EC",
            "2241B1",
            "2241B3",
            "2241B5",
            "2282B5",
            "2282B7",
            "224531",
            "228334",
            "2241B2",
            "2241B4",
            "2241B6",
            "22801E",
            "22801F",
        )

    @JvmField val VOLT_7E6_PROBES: Array<String> = arrayOf("224501", "224502")

    @JvmField
    val VOLT_7E7_LAYOUT_PROBES: Array<String> =
        arrayOf(
            "2240D7",
            "2240D9",
            "2240DB",
            "2240DD",
            "2240DF",
            "2240E1",
            "224181",
            "224182",
            "22419F",
            "2241E0",
            "224200",
            "224201",
            "224240",
            "22C218",
            "2240D4",
        )

    // These candidates returned negative/no-data frames on the real car in the June 2026 scan.
    // Keep the executable lists empty until a new TPMS header/addressing source is found.
    @JvmField val TPMS_7E0_DISCOVERY_PROBES: Array<String> = emptyArray()

    @JvmField val TPMS_760_DISCOVERY_PROBES: Array<String> = emptyArray()
}
