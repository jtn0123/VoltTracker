package com.volttracker.obdpoc.sim

import android.bluetooth.BluetoothDevice
import com.volttracker.obdpoc.CarControlFrames
import com.volttracker.obdpoc.engine.ElmConnection
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake ELM327 adapter plugged into a simulated gen-2 Chevy Volt.
 *
 * Unlike the command-keyed scripted fakes, it keeps adapter state the way real hardware does:
 * `ATSH` selects which module answers, so a Mode-22 DID only answers on the header that owns it.
 * The CAN receive filter is modelled too: the automatic filter only accepts 7E8-7EF, so a node
 * outside 7E0-7E7 (e.g. 0x257, replying on 0x657) is heard only after a matching `ATCRA`, and a
 * leftover `ATCRA` deafens the adapter to the 7Ex modules until `ATAR` restores the auto filter.
 * Replies come from [VirtualVoltCatalog] and are formatted the way an ELM327 prints them with
 * `ATE0 ATS0 ATH0 ATCAF1` (the app's init): hex without spaces, ISO-TP multi-frame replies in
 * `NNN / 0: / 1:` segmented form, and `NO DATA` when the addressed module stays silent.
 *
 * The car is 11-bit 500 kbps CAN only: after `ATSP7` / `ATSP8` (29-bit) every request answers
 * NO DATA until `ATSP0` / `ATSP6` puts the adapter back. Its trouble-code memory is [faults].
 */
class VirtualVolt(
    private val mode: VirtualVoltCatalog.Mode,
    private val batchStyle: BatchStyle = BatchStyle.J1979,
    /**
     * True to behave like an OBDLink (STN chip): answers `STI`, accepts ST commands, and can switch
     * to single-wire CAN (`STP 61`) where `STM` hears [VirtualVoltCatalog.SWCAN_FRAMES]. While
     * switched off HS-CAN, every OBD request answers NO DATA, exactly like the real adapter, so a
     * listener that forgets to switch back breaks polling visibly.
     *
     * An STN adapter also accepts `STPX` transmits the way the car-controls path uses them: the
     * body reacts only to the allowlisted OVMS frames ([CarControlFrames]) on the right bus, and the
     * change shows up in the next `STM` read-back ([BodyState]).
     */
    private val stn: Boolean = false,
) : ElmConnection() {
    /**
     * How the ECM answers a multi-PID Mode-01 request such as `010D0C49`.
     *
     * [J1979] is what SAE J1979 specifies on CAN: one `41` service byte followed by PID/data
     * pairs. [PER_PID] repeats `41` before every PID, which is what the scripted fakes send.
     */
    enum class BatchStyle { J1979, PER_PID }

    /** One adapter round trip, as the scorecard sees it. */
    class Exchange(
        val header: String,
        val command: String,
        val reply: String,
    )

    val exchanges: MutableList<Exchange> = Collections.synchronizedList(ArrayList())
    val closeCalls = AtomicInteger()
    private val afterCommandHooks = LinkedHashMap<String, Runnable>()

    @Volatile
    private var header = BROADCAST

    /** Reply ID set by `ATCRA`, or null while the adapter's automatic 7E8-7EF filter is active. */
    @Volatile
    private var receiveFilter: String? = null

    @Volatile
    private var protocol = HS_PROTOCOL

    /** Every frame the app transmitted with `STPX`, as `"<bus>:<id>:<data>"`, in order. */
    val transmitted: MutableList<String> = Collections.synchronizedList(ArrayList())

    /**
     * The simulated body: null fields mean "as the catalog broadcasts it" (locked from the panel, no
     * remote-start or window frames), so the listen-only tests see exactly the catalog.
     */
    class BodyState {
        @Volatile var locked: Boolean? = null

        @Volatile var remoteStart: Boolean? = null

        @Volatile var windowsOpen: Boolean? = null

        /**
         * The driver's door, sent only when it changes: like the real car's door frames, a short
         * listen window misses it and only a long one ([EVENT_LISTEN_MS]+, i.e. a body test) hears it.
         */
        @Volatile var doorFlOpen: Boolean? = null

        /** Set by the SW-CAN wake frame (sent under High Voltage Wakeup); body commands need it. */
        @Volatile var awake = false
    }

    val body = BodyState()

    /**
     * The car's trouble-code memory, answering Modes 03 / 07 / 0A / 02 / 04. Clean by default,
     * matching the only scan of the real car (2026-06-16, docs/obd-log-findings-2026-06-16.md): five
     * modules each answered `4300`, `4700` and `4A00`, and there was no freeze frame. A code set
     * with [Faults.set] answers in SAE J1979 form (never seen on this car): the ECM reports it
     * stored and permanent, with its freeze frame ([FREEZE_FRAME]).
     */
    class Faults {
        /** The ECM's confirmed code as its two J1979 bytes ("0128" for P0128), or null. */
        @Volatile var stored: String? = null

        /** The ECM's permanent code: Mode 04 can't erase it; the car drops it after the fault passes. */
        @Volatile var permanent: String? = null

        /** Sets [code] (e.g. "P0128") stored and permanent, with its freeze frame. */
        fun set(code: String) {
            val system = "PCBU".indexOf(code[0])
            require(system >= 0 && code.length == 5) { "not a trouble code: $code" }
            val bytes = "%X".format((system shl 2) or code[1].digitToInt()) + code.substring(2)
            stored = bytes
            permanent = bytes
        }
    }

    val faults = Faults()

    @Volatile
    private var highVoltageWakeup = false

    fun afterCommand(
        command: String,
        hook: Runnable,
    ) {
        afterCommandHooks[command] = hook
    }

    override fun `open`(
        device: BluetoothDevice,
        uuid: UUID,
        connectTimeoutMs: Long,
    ) = Unit

    override fun wakeNudge(toleranceMs: Long): WakeNudgeResult {
        firstReadMs = 0L
        return WakeNudgeResult(0L, true)
    }

    override fun transact(
        command: String,
        timeoutMs: Long,
        keepWaiting: KeepWaiting,
    ): String {
        val clean = command.trim().uppercase().replace(" ", "")
        val reply = answer(clean)
        exchanges.add(Exchange(header, clean, reply))
        afterCommandHooks[clean]?.run()
        return "$reply\r>"
    }

    override fun monitor(
        command: String,
        listenMs: Long,
        stopTimeoutMs: Long,
        keepWaiting: KeepWaiting,
    ): MonitorResult {
        val clean = command.trim().uppercase()
        if (!stn) {
            exchanges.add(Exchange(header, clean, "?"))
            return MonitorResult("?\r>", true, true, false)
        }
        val frames =
            if (protocol == SWCAN_PROTOCOL || protocol == SWCAN_29BIT_PROTOCOL) {
                swcanLines(listenMs).joinToString("\r") + "\r"
            } else {
                ""
            }
        val text = frames + "STOPPED\r\r>"
        exchanges.add(Exchange(header, clean, text))
        return MonitorResult(text, true, false, false)
    }

    override fun sendEscape(settleMs: Long) = Unit

    override fun close() {
        closeCalls.incrementAndGet()
    }

    private fun answer(command: String): String =
        when {
            command == "ATZ" -> {
                header = BROADCAST
                receiveFilter = null
                protocol = HS_PROTOCOL
                "ELM327 v1.5"
            }
            command == "STI" -> if (stn) "STN2255 v5.10.3" else "?"
            command.startsWith("STPX") -> if (stn) transmit(command) else "?"
            // STPO opens the current protocol; it is not a preset.
            command == "STPO" -> if (stn) "OK" else "?"
            command.startsWith("STCSWM") && stn -> {
                highVoltageWakeup = command == "STCSWM2"
                "OK"
            }
            command.startsWith("STP") && stn -> {
                protocol = command.removePrefix("STP")
                highVoltageWakeup = false
                "OK"
            }
            command.startsWith("ST") -> if (stn) "OK" else "?"
            command == "ATDPN" -> protocol
            command == "ATSP0" || command == "ATSP6" -> {
                protocol = HS_PROTOCOL
                "OK"
            }
            command.startsWith("ATSP") -> {
                protocol = command.removePrefix("ATSP")
                "OK"
            }
            command.startsWith("ATSH") -> {
                header = command.removePrefix("ATSH")
                "OK"
            }
            command.startsWith("ATCRA") -> {
                receiveFilter = command.removePrefix("ATCRA").ifEmpty { null }
                "OK"
            }
            command == "ATAR" -> {
                receiveFilter = null
                "OK"
            }
            command == "ATRV" -> VirtualVoltCatalog.find(BROADCAST, command)?.replies?.get(mode) ?: "?"
            command.startsWith("AT") -> "OK"
            protocol != HS_PROTOCOL -> NO_DATA
            command == "0100" && header == BROADCAST -> "4100BE3FA813"
            command == "0902" && header == BROADCAST -> segmented(vinPayload())
            isMode01Batch(command) && header == BROADCAST -> mode01Batch(command)
            !replyPassesFilter() -> NO_DATA
            isFaultRequest(command) -> faultReply(command)
            else -> VirtualVoltCatalog.find(header, command)?.replies?.get(mode) ?: NO_DATA
        }

    /** The catalog's broadcasts with the body's current state swapped in (bus order: state last). */
    private fun swcanLines(listenMs: Long): List<String> {
        val lines =
            VirtualVoltCatalog.SWCAN_FRAMES
                .getValue(mode)
                .map { it.line }
                .filterNot { body.locked != null && it.startsWith(LOCK_FRAME_PREFIX) }
                .toMutableList()
        body.locked?.let { lines.add(LOCK_FRAME_PREFIX + if (it) " 00 01 00 07" else " 00 00 00 07") }
        body.remoteStart?.let { lines.add(REMOTE_START_FRAME_PREFIX + if (it) " 02" else " 00") }
        body.windowsOpen?.let { lines.add(WINDOWS_FRAME_PREFIX + if (it) " 36 36" else " 00 00") }
        if (listenMs >=
            EVENT_LISTEN_MS
        ) {
            body.doorFlOpen?.let { lines.add(DOOR_FL_FRAME_PREFIX + if (it) " 01" else " 00") }
        }
        return lines
    }

    /** `STPXH:<id>,D:<hex>,R:0` (spaces already stripped): the car reacts to allowlisted frames only. */
    private fun transmit(command: String): String {
        val match = STPX.matchEntire(command) ?: return "?"
        val id = match.groupValues[1].toInt(16)
        val data = match.groupValues[2].chunked(2).map { it.toInt(16) }
        transmitted.add("$protocol:${match.groupValues[1]}:${match.groupValues[2]}")
        val frame =
            CarControlFrames.ALLOWED.firstOrNull {
                it.id == id &&
                    it.data == data &&
                    it.bus.stnProtocol == protocol
            }
        when {
            frame == null -> Unit
            frame == CarControlFrames.WAKEUP -> body.awake = body.awake || highVoltageWakeup
            frame == CarControlFrames.TELEMATICS_LOCK -> body.locked = true
            frame == CarControlFrames.TELEMATICS_UNLOCK -> body.locked = false
            !body.awake -> Unit
            frame == CarControlFrames.TELEMATICS_REMOTE_START -> body.remoteStart = true
            frame == CarControlFrames.TELEMATICS_REMOTE_STOP -> body.remoteStart = false
            frame == CarControlFrames.BCM_WINDOWS_DOWN -> body.windowsOpen = true
            frame == CarControlFrames.BCM_WINDOWS_UP -> body.windowsOpen = false
        }
        return "OK"
    }

    /** Whether the addressed module's reply ID (request + 8, or + 0x400 off 7Ex) gets through. */
    private fun replyPassesFilter(): Boolean {
        val filter = receiveFilter
        val autoFiltered = header == BROADCAST || header.startsWith("7E")
        if (filter == null) return autoFiltered
        if (autoFiltered) return false
        val replyId = header.toIntOrNull(16)?.plus(0x400) ?: return false
        return filter == "%03X".format(replyId)
    }

    /** A trouble-code service for the ECM, physically (7E0) or by broadcast; frame 00 only for Mode 02. */
    private fun isFaultRequest(command: String): Boolean =
        (header == BROADCAST || header == ECM) &&
            (command in DTC_SERVICES || (command.length == 6 && command.startsWith("02") && command.endsWith("00")))

    private fun faultReply(command: String): String {
        val stored = faults.stored
        return when (command) {
            "03" -> modules("4300", stored?.let { "4301$it" })
            "07" -> modules("4700", null)
            "0A" -> modules("4A00", faults.permanent?.let { "4A01$it" })
            "04" -> {
                // Clearing erases the stored code and its freeze frame; the permanent code stays.
                faults.stored = null
                modules("44", null)
            }
            // Mode 02: only the ECM keeps a freeze frame, and only alongside a stored code.
            else -> stored?.let { freezeFrame(command.substring(2, 4), it) } ?: NO_DATA
        }
    }

    /** Each module's reply on its own line (ATH0): the ECM's first, then the others' on a broadcast. */
    private fun modules(
        clean: String,
        ecm: String?,
    ): String {
        val first = ecm ?: clean
        return if (header == ECM) first else (listOf(first) + List(OTHER_DTC_MODULES) { clean }).joinToString("\r")
    }

    private fun freezeFrame(
        pid: String,
        storedCode: String,
    ): String? =
        when (pid) {
            "00" -> "420000" + "%08X".format(FREEZE_FRAME_PAGE)
            "02" -> "420200$storedCode"
            else -> FREEZE_FRAME[pid]?.let { "42${pid}00$it" }
        }

    private fun isMode01Batch(command: String): Boolean =
        command.length > 4 && command.length % 2 == 0 && command.startsWith("01") && command.all(::isHex)

    private fun mode01Batch(command: String): String {
        val pids = command.substring(2).chunked(2)
        val answers =
            pids.mapNotNull { pid ->
                VirtualVoltCatalog
                    .find(BROADCAST, "01$pid")
                    ?.replies
                    ?.get(mode)
                    ?.removePrefix("41")
            }
        if (answers.isEmpty()) return NO_DATA
        return when (batchStyle) {
            BatchStyle.J1979 -> segmented("41" + answers.joinToString(""))
            BatchStyle.PER_PID -> answers.joinToString("") { "41$it" }
        }
    }

    private companion object {
        const val BROADCAST = "7DF"
        const val ECM = "7E0"
        const val NO_DATA = "NO DATA"
        const val HS_PROTOCOL = "6"
        const val SWCAN_PROTOCOL = "61"
        const val SWCAN_29BIT_PROTOCOL = "62"
        const val LOCK_FRAME_PREFIX = "0C 41 40 40"
        const val REMOTE_START_FRAME_PREFIX = "10 39 00 40"

        // GMLAN PID 0x325 (window positions) from the BCM: FL|RL<<3, FR|RR<<3; 6 = fully open.
        const val WINDOWS_FRAME_PREFIX = "10 64 A0 CB"

        /** 0x0C630040: the driver's door (open = data bit 0). */
        const val DOOR_FL_FRAME_PREFIX = "0C 63 00 40"

        /** Shortest listen that catches an event-only frame (a body test chunk is 5 s). */
        const val EVENT_LISTEN_MS = 3_000L

        val DTC_SERVICES = setOf("03", "07", "0A", "04")

        /** The modules besides the ECM that answered the real car's generic trouble-code reads. */
        const val OTHER_DTC_MODULES = 4

        /**
         * Frame 00 the ECM saves with a stored code, as each PID's data bytes: a cold engine pulling
         * at 45 mph, which is what a thermostat code (P0128) looks like.
         */
        val FREEZE_FRAME =
            linkedMapOf(
                "04" to "4D", // 30 % load
                "05" to "5A", // 50 °C coolant: below the thermostat's regulating temperature
                "0C" to "1AF8", // 1726 rpm
                "0D" to "48", // 72 km/h
                "0F" to "37", // 15 °C intake air
                "11" to "26", // 15 % throttle
                "1F" to "00F0", // 240 s since the engine started
                "2F" to "80", // 50 % fuel
                "42" to "3732", // 14.13 V
            )

        /** `02 00 00`'s supported-PID bitmap: bit 31 is PID 01 … bit 0 is PID 20. */
        val FREEZE_FRAME_PAGE: Long =
            (listOf("02") + FREEZE_FRAME.keys)
                .map { it.toInt(16) }
                .filter { it in 1..0x20 }
                .fold(0L) { bits, pid -> bits or (1L shl (32 - pid)) }
        val STPX = Regex("STPXH:([0-9A-F]+),D:([0-9A-F]*),R:\\d+")

        /**
         * A synthetic 2017 Volt VIN: GM's 1G1 maker code, model year H, a valid check digit, and an
         * all-zero serial no car carries. It has to be a well-formed VIN: the app rightly rejects
         * one containing I, O or Q, so the old placeholder never reached the vehicle record.
         */
        const val VIN = "1G1RC6S5XHU000000"

        fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'A'..'F'

        fun vinPayload(): String = "490201" + VIN.map { "%02X".format(it.code) }.joinToString("")

        /**
         * Formats a reply the way ELM327 prints it with CAN auto-formatting on and headers off: up
         * to 7 bytes fit a single frame; longer replies print a 3-hex-digit byte count, then a
         * `0:` first frame of 6 bytes and `1:`, `2:`… consecutive frames of 7.
         */
        fun segmented(payloadHex: String): String {
            val bytes = payloadHex.chunked(2)
            if (bytes.size <= 7) return payloadHex
            val lines = mutableListOf("%03X".format(bytes.size), "0:" + bytes.take(6).joinToString(""))
            bytes.drop(6).chunked(7).forEachIndexed { index, frame ->
                lines.add("${(index + 1) % 16}:" + frame.joinToString("").padEnd(14, '0'))
            }
            return lines.joinToString("\r")
        }
    }
}
