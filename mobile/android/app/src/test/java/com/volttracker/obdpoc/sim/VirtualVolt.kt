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
     * The simulated body: null fields mean "as the catalog broadcasts it" (locked by fob, no
     * remote-start or window frames), so the listen-only tests see exactly the catalog.
     */
    class BodyState {
        @Volatile var locked: Boolean? = null

        @Volatile var remoteStart: Boolean? = null

        @Volatile var windowsOpen: Boolean? = null

        /** Set by the SW-CAN wake frame (sent under High Voltage Wakeup); body commands need it. */
        @Volatile var awake = false
    }

    val body = BodyState()

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
                swcanLines().joinToString("\r") + "\r"
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
            command == "ATSP6" -> {
                protocol = HS_PROTOCOL
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
            else -> VirtualVoltCatalog.find(header, command)?.replies?.get(mode) ?: NO_DATA
        }

    /** The catalog's broadcasts with the body's current state swapped in (bus order: state last). */
    private fun swcanLines(): List<String> {
        val lines =
            VirtualVoltCatalog.SWCAN_FRAMES
                .getValue(mode)
                .map { it.line }
                .filterNot { body.locked != null && it.startsWith(LOCK_FRAME_PREFIX) }
                .toMutableList()
        body.locked?.let { lines.add(LOCK_FRAME_PREFIX + if (it) " 00 01 00 07" else " 00 00 00 07") }
        body.remoteStart?.let { lines.add(REMOTE_START_FRAME_PREFIX + if (it) " 02" else " 00") }
        body.windowsOpen?.let { lines.add(WINDOWS_FRAME_PREFIX + if (it) " 36 36" else " 00 00") }
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
        const val NO_DATA = "NO DATA"
        const val HS_PROTOCOL = "6"
        const val SWCAN_PROTOCOL = "61"
        const val SWCAN_29BIT_PROTOCOL = "62"
        const val LOCK_FRAME_PREFIX = "0C 41 40 40"
        const val REMOTE_START_FRAME_PREFIX = "10 39 00 40"

        // GMLAN PID 0x325 (window positions) from the BCM: FL|RL<<3, FR|RR<<3; 6 = fully open.
        const val WINDOWS_FRAME_PREFIX = "10 64 A0 CB"
        val STPX = Regex("STPXH:([0-9A-F]+),D:([0-9A-F]*),R:\\d+")

        // 17 characters like a real VIN, but obviously synthetic (VINs never contain I, O or Q).
        const val VIN = "SYNTHETICVOLTVIN0"

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
