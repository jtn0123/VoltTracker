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
    private var lastFailure: String? = null

    /** Opens a fresh `session-<ts>-<mode>.jsonl`. Leaves the log closed on failure. */
    @Synchronized
    fun open(mode: String) {
        close()
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            noteFailure("open_mkdir_failed", null)
            return
        }
        if (!logsDir.isDirectory) {
            noteFailure("open_mkdir_failed", null)
            return
        }
        val pending = File(logsDir, "session-${System.currentTimeMillis()}-$mode.jsonl")
        try {
            fos = FileOutputStream(pending, true)
            writer = BufferedWriter(OutputStreamWriter(fos, StandardCharsets.UTF_8))
            file = pending
            lastFailure = null
            writeLatestPointer(pending)
        } catch (ex: IOException) {
            noteFailure("open_failed", ex)
            closeQuietly()
        }
    }

    @Synchronized
    fun isOpen(): Boolean = writer != null

    /** Most recent local log IO failure, or null once a later open/write succeeds. */
    @Synchronized
    fun lastWriteFailure(): String? = lastFailure

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
            var syncFailedThisCall = false
            var syncSucceededThisCall = false
            if (durable) {
                try {
                    fos?.fd?.sync()
                    syncSucceededThisCall = true
                } catch (ignored: IOException) {
                    syncFailedThisCall = true
                    noteFailure("sync_failed", ignored)
                }
            }
            // The line landed, so clear any prior failure — but a stale "sync_failed" may only be
            // cleared by a later durable write whose own sync just succeeded; a plain buffered write
            // proves nothing about fsync health and must not mask the earlier durability failure.
            // (And never clear when THIS call's own fsync just failed — its noteFailure must stand.)
            val staleFailureIsSyncRelated = lastFailure?.startsWith("sync_failed") == true
            if (lastFailure != null &&
                !syncFailedThisCall &&
                (!staleFailureIsSyncRelated || syncSucceededThisCall)
            ) {
                lastFailure = null
            }
        } catch (ex: IOException) {
            noteFailure("write_failed", ex)
        } catch (ex: JSONException) {
            // The line is dropped, but record it so lastWriteFailure() surfaces
            // the loss instead of the log silently missing entries.
            noteFailure("encode_failed", ex)
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
            } catch (ex: IOException) {
                OBDLog.warn("ObdSessionLog", "close_failed: ${ex.javaClass.simpleName}: ${ex.message}")
            }
        } else if (currentFos != null) {
            try {
                currentFos.close()
            } catch (ex: IOException) {
                OBDLog.warn("ObdSessionLog", "close_failed: ${ex.javaClass.simpleName}: ${ex.message}")
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
            noteFailure("latest_pointer_failed", ignored)
        }
    }

    private fun noteFailure(
        stage: String,
        ex: Exception?,
    ) {
        val detail = if (ex == null) stage else "$stage: ${ex.javaClass.simpleName}: ${ex.message}"
        lastFailure = detail
        OBDLog.warn("ObdSessionLog", detail)
    }
}
