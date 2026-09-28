package com.volttracker.obdpoc.ui.settings

/**
 * A one-off action started from a Settings page (as opposed to a stored [SettingChange]). The host
 * runs each through the same helpers the classic dashboard uses.
 */
sealed interface SettingsCommand {
    /** Connect to the remembered adapter for a short probe. */
    data object TestConnection : SettingsCommand

    /** Bundle recent logs and open the share sheet (behind the privacy disclosure). */
    data object SendDiagnostics : SettingsCommand

    /** Keep probing for [minutes] and notify once the car answers; [on] = false cancels. */
    data class WaitForAdapter(
        val on: Boolean,
        val minutes: Int,
    ) : SettingsCommand

    /** Back up everything; a non-blank [passphrase] encrypts the file. */
    data class BackUp(
        val passphrase: String?,
    ) : SettingsCommand

    /** Pick a backup file and restore it; [passphrase] is only needed for an encrypted one. */
    data class Restore(
        val passphrase: String?,
    ) : SettingsCommand

    /** Every logged trip as one CSV, via the share sheet. */
    data object ExportTrips : SettingsCommand
}
