package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Writes the per-session `.jsonl` field log to disk.
 */
class ObdSessionLog(
    private val logsDir: File,
) {
    private var fos: FileOutputStream? = null
    private var writer: BufferedWriter? = null
    private var file: File? = null

    /** Opens a fresh `session-<ts>-<mode>.jsonl`. Leaves the log closed on failure. */
    @Synchronized
    fun open(mode: String) {
        close()
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            return
        }
        val pending = File(logsDir, "session-${System.currentTimeMillis()}-$mode.jsonl")
        try {
            fos = FileOutputStream(pending, true)
            writer = BufferedWriter(OutputStreamWriter(fos, StandardCharsets.UTF_8))
            file = pending
            writeLatestPointer(pending)
        } catch (ex: IOException) {
            closeQuietly()
        }
    }

    @Synchronized
    fun isOpen(): Boolean = writer != null

    /** The current log file name, or `null` when the log is closed. */
    @Synchronized
    fun fileName(): String? = file?.name

    /** Appends one `{ts,type,payload,file}` line. A no-op when the log is closed. */
    @Synchronized
    fun write(
        type: String,
        payload: JSONObject,
    ) {
        writeInternal(type, payload, false)
    }

    @Synchronized
    fun writeDurable(
        type: String,
        payload: JSONObject,
    ) {
        writeInternal(type, payload, true)
    }

    private fun writeInternal(
        type: String,
        payload: JSONObject,
        durable: Boolean,
    ) {
        val currentWriter = writer ?: return
        val line = JSONObject()
        try {
            line.put("ts", System.currentTimeMillis())
            line.put("type", type)
            line.put("payload", payload)
            file?.let { line.put("file", it.name) }
            currentWriter.write(line.toString())
            currentWriter.newLine()
            currentWriter.flush()
            if (durable) {
                try {
                    fos?.fd?.sync()
                } catch (ignored: IOException) {
                    // Already past flush; nothing more we can do here.
                }
            }
        } catch (ignored: IOException) {
        } catch (ignored: JSONException) {
        }
    }

    @Synchronized
    fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        val currentWriter = writer
        val currentFos = fos
        if (currentWriter != null) {
            try {
                currentWriter.flush()
                currentWriter.close()
            } catch (ignored: IOException) {
            }
        } else if (currentFos != null) {
            try {
                currentFos.close()
            } catch (ignored: IOException) {
            }
        }
        writer = null
        fos = null
        file = null
    }

    private fun writeLatestPointer(logFile: File) {
        val pointer = File(logsDir, "latest.txt")
        try {
            BufferedWriter(FileWriter(pointer, false)).use { pointerWriter ->
                pointerWriter.write(logFile.name)
                pointerWriter.newLine()
            }
        } catch (ignored: IOException) {
        }
    }
}
