package com.volttracker.obdpoc

import com.volttracker.obdpoc.ui.settings.SettingsUiState

/**
 * The pure decisions behind the native dashboard's data tools (backup, restore, export), kept off
 * [ComposeDashboardActivity] so each can be tested on its own.
 */
object ComposeDataTools {
    /** Whether a restore may start from the native dashboard. */
    enum class RestoreGate {
        /** Nothing else holds the database: go ahead. */
        OK,

        /** A logging (or demo) session is writing to the database: stop it first. */
        STOP_LOGGING,

        /** The classic dashboard is open with its own database handle: restore from there. */
        USE_CLASSIC,
    }

    fun restoreGate(
        loggingActive: Boolean,
        classicAlive: Boolean,
    ): RestoreGate =
        when {
            loggingActive -> RestoreGate.STOP_LOGGING
            classicAlive -> RestoreGate.USE_CLASSIC
            else -> RestoreGate.OK
        }

    /** "Last backup Sep 27, 9:40 PM · 42 trips", or the no-backup note when none is recorded. */
    fun lastBackupLabel(
        atMs: Long,
        trips: Int,
        formatDateTime: (Long) -> String,
    ): String {
        if (atMs <= 0L) return SettingsUiState.NO_BACKUP
        val stamp = "Last backup ${formatDateTime(atMs)}"
        return if (trips > 0) "$stamp · $trips ${if (trips == 1) "trip" else "trips"}" else stamp
    }

    /**
     * One line for the running data task, from a backup/restore progress update: the title plus
     * the percentage while busy, the title and outcome once done, or null when the task is hidden.
     */
    fun progressLabel(
        visible: Boolean,
        busy: Boolean,
        title: String?,
        detail: String?,
        percent: Int,
    ): String? {
        if (!visible) return null
        return if (busy) {
            listOfNotNull(title?.takeIf { it.isNotBlank() }, percent.takeIf { it >= 0 }?.let { "$it%" })
                .joinToString(" · ")
                .ifBlank { null }
        } else {
            // The outcome detail often restates the title ("Backup ready. Choose where…").
            val repeatsTitle = title != null && detail?.startsWith(title.trimEnd('.')) == true
            val parts = if (repeatsTitle) listOf(detail) else listOf(title, detail)
            parts
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString(" — ")
                .ifBlank { null }
        }
    }
}
