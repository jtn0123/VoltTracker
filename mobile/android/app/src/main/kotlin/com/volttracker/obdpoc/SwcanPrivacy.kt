package com.volttracker.obdpoc

import java.util.Locale

/**
 * What of the car's SW-CAN traffic may be written to a log. The broadcasts in [SENSITIVE_PIDS]
 * carry the VIN, the car's location, immobilizer and passive-entry ids and the OnStar Wi-Fi name and
 * password, so they are dropped before anything is stored, and the lifetime fuel-economy frame
 * ([PID_VICM_INFO]) keeps only its first byte, the refuel state and fuel door. Plain 11-bit frames
 * are diagnostics, not body broadcasts, and are dropped too.
 *
 * `tools/swcan_correlate.py` holds the same list as `MUST_BE_SENSITIVE`; SwcanPrivacyTest keeps the
 * two equal.
 */
object SwcanPrivacy {
    @JvmField
    val SENSITIVE_PIDS: Set<Int> =
        setOf(
            // VIN
            0x762,
            0x764,
            // GPS
            0x155,
            0x156,
            // immobilizer id and environment id
            0x160,
            0x182,
            0x183,
            0x184,
            // OnStar Wi-Fi settings, name and password
            0x474,
            0x478,
            0x479,
            0x47A,
            0x480,
            0x481,
            0x482,
            // compass heading, location-based charging state
            0x382,
            0x13D,
            // passive-entry challenge and reply, vehicle and key ids
            0x146,
            0x3C9,
            0x3C8,
            0x1D3,
        )

    /** VICM_Info: byte 0 is the refuel state and fuel door; the rest is lifetime fuel economy. */
    const val PID_VICM_INFO = 0x3B2

    /**
     * [frames] as monitor lines (`10 24 80 40 C8 7B`, one per CR) without the sensitive ones, the
     * form `tools/swcan_correlate.py` reads back.
     */
    @JvmStatic
    fun loggable(frames: List<SwcanFrame>): String =
        frames
            .filter { it.extended && it.gmlanPid !in SENSITIVE_PIDS }
            .joinToString("\r") { line(it) }

    private fun line(frame: SwcanFrame): String {
        val data = if (frame.gmlanPid == PID_VICM_INFO) frame.data.take(1) else frame.data.toList()
        val header = listOf(frame.id ushr 24, frame.id ushr 16, frame.id ushr 8, frame.id).map { it and 0xFF }
        return (header + data).joinToString(" ") { "%02X".format(Locale.US, it) }
    }
}
