package com.volttracker.obdpoc

import android.net.Uri
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.deleteOrLog
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Encrypted backup and restore through [DataBackup] when memory runs short: new backups are the
 * segmented v4 container, a heap too small for the work yields a readable failure instead of an
 * OutOfMemoryError crash, and no partial file is left behind either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataBackupMemoryGuardTest {
    private val context = RuntimeEnvironment.getApplication()
    private val originalHeadroom = BackupCrypto.heapHeadroomBytes
    private val passphrase = "synthetic-pass"

    @After
    fun restoreHeadroom() {
        BackupCrypto.heapHeadroomBytes = originalHeadroom
    }

    private fun <T> withStore(block: (ObdLocalStore) -> T): T {
        val store = ObdLocalStore(context)
        try {
            store.clearAllData()
            val id = store.startSession("obd", "AA:BB", "Adapter")
            store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")
            return block(store)
        } finally {
            store.close()
        }
    }

    private fun backupLeftovers(): List<String> =
        File(context.cacheDir, "backups").listFiles()?.map { it.name }.orEmpty()

    private fun restoreLeftovers(): List<String> =
        context.cacheDir
            .listFiles()
            ?.map { it.name }
            ?.filter { it.startsWith("restore-") }
            .orEmpty()

    @Test
    fun encryptedBackupIsWrittenAsTheSegmentedContainerAndRestores() {
        val backup = withStore { DataBackup(context).buildEncryptedBackupFile(it, passphrase) }
        assertNotNull(backup)
        assertArrayEquals("VTBKEN4\n".toByteArray(Charsets.US_ASCII), backup!!.readBytes().copyOf(8))

        val outcome = DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(backup), passphrase)

        assertEquals(DataBackup.RestoreStageStatus.OK, outcome.status)
        assertTrue(DataBackup.isVoltTrackerBackup(outcome.file))
        outcome.file!!.deleteOrLog()
        backup.deleteOrLog()
    }

    @Test
    fun encryptedBackupThatRunsOutOfMemoryReturnsNullAndLeavesNoFile() {
        BackupCrypto.heapHeadroomBytes = { throw OutOfMemoryError("synthetic") }

        val backup = withStore { DataBackup(context).buildEncryptedBackupFile(it, passphrase) }

        assertNull("an OutOfMemoryError must become a failed backup, not a crash", backup)
        assertEquals(emptyList<String>(), backupLeftovers())
    }

    @Test
    fun encryptedBackupWithoutHeapHeadroomReturnsNullAndLeavesNoFile() {
        BackupCrypto.heapHeadroomBytes = { 0L }

        val backup = withStore { DataBackup(context).buildEncryptedBackupFile(it, passphrase) }

        assertNull(backup)
        assertEquals(emptyList<String>(), backupLeftovers())
    }

    @Test
    fun olderEncryptedBackupTooLargeForTheHeapReportsTooLargeForMemory() {
        val plain = withStore { DataBackup(context).buildBackupFile(it) }!!
        val legacy = File(context.filesDir, "legacy-v3.vtdb")
        BackupCrypto.encryptFile(plain, legacy, passphrase)
        BackupCrypto.heapHeadroomBytes = { legacy.length() }

        val outcome = DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(legacy), passphrase)

        assertEquals(DataBackup.RestoreStageStatus.TOO_LARGE_FOR_MEMORY, outcome.status)
        assertNull(outcome.file)
        assertTrue(outcome.encrypted)
        assertEquals(emptyList<String>(), restoreLeftovers())

        BackupCrypto.heapHeadroomBytes = originalHeadroom
        val retry = DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(legacy), passphrase)
        assertEquals(
            "the same older backup still restores with room to spare",
            DataBackup.RestoreStageStatus.OK,
            retry.status,
        )
        retry.file!!.deleteOrLog()
        legacy.deleteOrLog()
        plain.deleteOrLog()
    }

    @Test
    fun encryptedRestoreThatRunsOutOfMemoryReportsTooLargeForMemory() {
        val backup = withStore { DataBackup(context).buildEncryptedBackupFile(it, passphrase) }!!
        BackupCrypto.heapHeadroomBytes = { throw OutOfMemoryError("synthetic") }

        val outcome = DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(backup), passphrase)

        assertEquals(DataBackup.RestoreStageStatus.TOO_LARGE_FOR_MEMORY, outcome.status)
        assertNull(outcome.file)
        assertEquals(emptyList<String>(), restoreLeftovers())
        backup.deleteOrLog()
    }
}
