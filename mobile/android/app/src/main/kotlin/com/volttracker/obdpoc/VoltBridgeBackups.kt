package com.volttracker.obdpoc

import android.util.Log

/**
 * Backup/restore bridge implementation: debug-bundle export, plain + encrypted backup share and
 * restore, and the destructive on-phone data clear (grouped here because clearing local data is
 * the other half of the backup lifecycle — users clear after confirming a backup exists).
 * [VoltBridge] owns the `@JavascriptInterface` wrappers; this class is plumbing only.
 */
internal class VoltBridgeBackups(
    private val activity: DashboardHost,
    private val stateProvider: BridgeStateProvider,
) {
    fun exportDebugBundle(): String =
        stateProvider.requireDataBackup().exportDebugBundle(
            stateProvider.getAppStateJson(),
            stateProvider.getStorageSummaryJson(),
        )

    fun shareBackup(dashboardPreferencesJson: String? = null) {
        val preferences = dashboardPreferencesJson?.take(MAX_BACKUP_PREFERENCES_CHARS)
        activity.runOnUiThread { stateProvider.requireBackupController().launchShare(preferences) }
    }

    fun shareEncryptedBackup(
        passphrase: String?,
        dashboardPreferencesJson: String? = null,
    ) {
        val cleanPassphrase = sanitizedPassphraseOrNull(passphrase) ?: return
        val preferences = dashboardPreferencesJson?.take(MAX_BACKUP_PREFERENCES_CHARS)
        activity.runOnUiThread {
            stateProvider.requireBackupController().launchEncryptedShare(cleanPassphrase, preferences)
        }
    }

    fun restoreBackup() {
        activity.runOnUiThread(stateProvider.requireBackupController()::launchRestorePicker)
    }

    fun restoreEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = sanitizedPassphraseOrNull(passphrase) ?: return
        activity.runOnUiThread {
            stateProvider.requireBackupController().launchEncryptedRestorePicker(cleanPassphrase)
        }
    }

    /**
     * Length-bounds the backup passphrase WITHOUT trimming: edge whitespace is key material, and
     * trimming it (the old behavior) silently derived a different PBKDF2 key than the user typed.
     * An all-whitespace passphrase is rejected with a clear error instead of silently collapsing
     * to empty; an empty/null one passes through so [BackupController]'s existing
     * "enter a passphrase" messaging still handles it. Backups encrypted before this change used
     * the TRIMMED form — decrypt compatibility lives in [BackupCrypto.decryptFileWithTrimFallback].
     */
    private fun sanitizedPassphraseOrNull(passphrase: String?): String? {
        val bounded = bridgeSafeNoTrim(passphrase, BRIDGE_MAX_PASSPHRASE_LEN)
        if (bounded.isNotEmpty() && bounded.isBlank()) {
            activity.runOnUiThread {
                activity.publishStatus(
                    "blocked",
                    "A backup passphrase cannot be only spaces. Include visible characters.",
                    true,
                )
            }
            return null
        }
        return bounded
    }

    fun clearStoredData() {
        if (activity.isLoggingActive()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Stop logging before clearing stored data.", true)
            }
            return
        }
        activity.confirmBridgeAction(
            "Clear stored data?",
            "This permanently deletes local OBD sessions, samples, routes, diagnostics, and storage rollups from this phone.",
            "Clear data",
        ) {
            clearStoredDataConfirmed()
        }
    }

    private fun clearStoredDataConfirmed() {
        activity.runOnBackground {
            if (activity.isLoggingActive()) {
                activity.runOnUiThread {
                    activity.publishStatus("blocked", "Stop logging before clearing stored data.", true)
                }
                return@runOnBackground
            }
            try {
                activity.localStore?.clearAllData()
            } catch (ex: RuntimeException) {
                Log.w(AppPrefs.LOG_TAG, "clearStoredData failed", ex)
                activity.runOnUiThread {
                    activity.publishStatus("blocked", "Could not clear the local OBD database.", true)
                }
                return@runOnBackground
            }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus("ready", "On-phone OBD database cleared.", false)
            }
        }
    }

    private companion object {
        const val MAX_BACKUP_PREFERENCES_CHARS = 64 * 1024
    }
}
