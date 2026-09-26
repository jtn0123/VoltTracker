package com.volttracker.obdpoc

import java.util.Locale

/** Splits batched mode-01 replies (several PIDs in one request) into per-PID frames. */
internal object ObdMode01Batch {
    /**
     * Splits a batched mode-01 reply into one standalone `41<pid><data>` frame per requested PID,
     * or returns null when any PID is missing. Accepts both reply shapes seen in the field:
     * - per-PID markers (`41 0D 10 41 0C 00 00`), which some clones emit; and
     * - SAE J1979 multi-PID form (`41 0D 10 0C 00 00`): one `41` followed by PID/data pairs. This is
     *   what the target Volt sends (real log: `010D0C` -> `410D100C0000`).
     * Multi-frame (ISO-TP "0:/1:") replies are reassembled first.
     */
    @JvmStatic
    fun split(
        response: String?,
        pidHex: List<String>,
    ): Map<String, String>? {
        if (response == null || pidHex.isEmpty()) {
            return null
        }
        val cleanPids = pidHex.map { it.uppercase(Locale.US) }
        if (ObdProtocol.responseContainsAllMode01Pids(response, cleanPids)) {
            return cleanPids.associateWith { pid -> mode01Frame(response, pid) ?: return null }
        }
        val frames = HashMap<String, String>()
        val sources = ObdProtocol.elmSegmentedHex(response)?.let(::listOf) ?: ObdProtocol.adapterHexLines(response)
        for (hex in sources) {
            collectJ1979Frames(hex, cleanPids.toSet(), frames)
        }
        return if (frames.keys.containsAll(cleanPids)) cleanPids.associateWith(frames::getValue) else null
    }

    /** First complete `41<pid><data>` frame for [pid] in a per-PID-marker reply. */
    private fun mode01Frame(
        response: String,
        pid: String,
    ): String? {
        val bytes = ObdProtocol.mode01Bytes(response, pid, ObdProtocol.mode01PayloadBytes(pid)) ?: return null
        return "41" + pid + bytes.joinToString("") { "%02X".format(Locale.US, it) }
    }

    /** Walks every `41` in [hex] as a J1979 multi-PID reply, keeping the first frame per PID. */
    private fun collectJ1979Frames(
        hex: String,
        wanted: Set<String>,
        frames: MutableMap<String, String>,
    ) {
        var start = hex.indexOf("41")
        while (start >= 0) {
            var cursor = start + 2
            while (cursor + 2 <= hex.length) {
                val pid = hex.substring(cursor, cursor + 2)
                if (pid !in wanted) {
                    break
                }
                val dataEnd = cursor + 2 + ObdProtocol.mode01PayloadBytes(pid) * 2
                if (dataEnd > hex.length) {
                    break
                }
                frames.getOrPut(pid) { "41" + hex.substring(cursor, dataEnd) }
                cursor = dataEnd
            }
            start = hex.indexOf("41", maxOf(start + 2, cursor))
        }
    }
}
