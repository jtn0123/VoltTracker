package com.volttracker.obdpoc

import java.util.Locale

/**
 * What of the car's SW-CAN traffic may be written to a log. Payloads are allowlisted: only the body
 * broadcasts in [PAYLOAD_PIDS] keep their data bytes, some only their first few ([PAYLOAD_BYTES]),
 * and every other frame is at most counted by id. The broadcasts in [SENSITIVE_PIDS] carry the VIN,
 * the car's location, immobilizer, key-store and passive-entry ids, the driver's identity and the
 * OnStar Wi-Fi name and password, so they are not even counted. Plain 11-bit frames are
 * diagnostics, not body broadcasts, and are dropped too.
 *
 * `tools/swcan_correlate.py` holds the same sensitive list as `MUST_BE_SENSITIVE`; SwcanPrivacyTest
 * keeps the two equal.
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
            // unlock key store (cryptographic), keyless-start authentication
            0x148,
            0x150,
            0x17F,
            // theft notification (carries the security column-lock password), driver identifier
            0x1CA,
            0x38A,
            // teen driver PIN, Bluetooth tethering pairing reply
            0x306,
            0x30B,
            0x125,
            // OnStar Wi-Fi settings, name and password, and their AMM copies
            0x474,
            0x476,
            0x478,
            0x479,
            0x47A,
            0x480,
            0x481,
            0x482,
            0x483,
            0x484,
            0x485,
            0x486,
            0x487,
            0x488,
            0x489,
            0x490,
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
     * The broadcasts whose payloads may be stored: doors, locks, windows, hood and hatch, seat heat,
     * climate, tyres, dash warnings, power mode, charge port and cord, 12 V, and the speeds and gear
     * lever that show the car moving. Not the odometer, trip meters, hour meters, clocks, or the
     * energy and distance counters. SwcanPrivacyTest checks that nothing here is in [SENSITIVE_PIDS].
     */
    @JvmField
    val PAYLOAD_PIDS: Set<Int> =
        setOf(
            // doors, lock command, hatch and its release, hood, windows and their normalized flags, theft alarm
            0x318,
            0x17B,
            0x17C,
            0x17D,
            0x20A,
            0x355,
            0x35A,
            0x394,
            0x325,
            0x323,
            0x130,
            // tyres, warnings, washer fluid, bulbs, oil life
            0x1EA,
            0x132,
            0x3C0,
            0x3C4,
            0x1DE,
            0x319,
            0x168,
            // power mode, charge port door, charge cord, refuel state and fuel door, 12 V, remote start
            0x121,
            0x112,
            0x176,
            PID_VICM_INFO,
            0x124,
            0x1C8,
            // climate
            0x138,
            0x13A,
            0x39A,
            0x40A,
            0x312,
            0x36A,
            0x31A,
            0x170,
            // seat heat: indicators, switches, requests
            0x391,
            0x392,
            0x393,
            0x3B4,
            0x3B6,
            0x3B8,
            // vehicle and wheel speeds, gear lever
            0x108,
            0x35C,
            0x165,
        )

    /**
     * Frames that keep only their first bytes: the fuel frame its fuel-door byte, the hybrid status
     * its charge-cord and charger bytes (the last ones are the charge-complete time of day).
     */
    @JvmField
    val PAYLOAD_BYTES: Map<Int, Int> = mapOf(PID_VICM_INFO to 1, 0x176 to 5)

    /** Whether [frame]'s payload may be stored. */
    @JvmStatic
    fun storable(frame: SwcanFrame): Boolean = frame.extended && frame.gmlanPid in PAYLOAD_PIDS

    /** Whether [frame] may be counted by id; its payload may still not be ([storable]). */
    @JvmStatic
    fun countable(frame: SwcanFrame): Boolean = frame.extended && frame.gmlanPid !in SENSITIVE_PIDS

    /** [frame]'s data bytes as they may be stored ([PAYLOAD_BYTES]); only for a [storable] frame. */
    @JvmStatic
    fun storedBytes(frame: SwcanFrame): List<Int> =
        PAYLOAD_BYTES[frame.gmlanPid]?.let { frame.data.take(it) } ?: frame.data.toList()

    /**
     * [frames] as monitor lines (`10 24 80 40 C8 7B`, one per CR), only the [storable] ones, the
     * form `tools/swcan_correlate.py` reads back.
     */
    @JvmStatic
    fun loggable(frames: List<SwcanFrame>): String = frames.filter(::storable).joinToString("\r") { line(it) }

    /**
     * [response] without any line that holds a SW-CAN frame. After a body-bus listen the adapter can
     * still print queued frames into the next command's reply, and command replies are stored. It
     * fails closed: any run of five or more spaced hex bytes, and any unspaced hex token of ten or
     * more digits, whose first four bytes read as a 29-bit GMLAN header below 0x800 withholds its
     * line, wherever in the line it starts (after a `|` batch separator, a prompt or a status word).
     * HS replies don't match: a mode 01/02/03/09/22 reply read as a 29-bit header lands at 0x800 or
     * above, and an 11-bit header breaks a run of bytes.
     */
    @JvmStatic
    fun redactFrames(response: String?): String? {
        if (response == null) return null
        val lines = response.split('\r', '\n')
        if (lines.none(::isWithheld)) return response
        return lines.filterNot(::isWithheld).joinToString("\r")
    }

    private fun isWithheld(line: String): Boolean {
        val tokens = line.uppercase(Locale.US).split(SEPARATORS).filter { it.isNotEmpty() }
        var run = 0
        for ((i, token) in tokens.withIndex()) {
            if (token.length == 2 && token.all(::isHex)) {
                run += 1
                if (run >= MIN_FRAME_BYTES && isSwcanHeader(tokens.subList(i - run + 1, i - run + 1 + HEADER_BYTES))) {
                    return true
                }
            } else {
                run = 0
                if (token.length >= MIN_COMPACT_CHARS &&
                    token.all(::isHex) &&
                    isSwcanHeader(token.take(8).chunked(2))
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** Four header bytes whose GMLAN parameter id (bits 13..25) is a body broadcast's, below 0x800. */
    private fun isSwcanHeader(bytes: List<String>): Boolean {
        val id = bytes.joinToString("").toLong(HEX_RADIX)
        return ((id ushr GMLAN_PID_SHIFT) and GMLAN_PID_MASK) < FIRST_HS_REPLY_PID
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'A'..'F'

    private val SEPARATORS = Regex("[\\s|>]+")
    private const val HEADER_BYTES = 4

    /** A 29-bit header plus at least one data byte. */
    private const val MIN_FRAME_BYTES = HEADER_BYTES + 1
    private const val MIN_COMPACT_CHARS = MIN_FRAME_BYTES * 2
    private const val HEX_RADIX = 16
    private const val GMLAN_PID_SHIFT = 13
    private const val GMLAN_PID_MASK = 0x1FFFL
    private const val FIRST_HS_REPLY_PID = 0x800L

    private fun line(frame: SwcanFrame): String {
        val header = listOf(frame.id ushr 24, frame.id ushr 16, frame.id ushr 8, frame.id).map { it and 0xFF }
        return (header + storedBytes(frame)).joinToString(" ") { "%02X".format(Locale.US, it) }
    }
}
