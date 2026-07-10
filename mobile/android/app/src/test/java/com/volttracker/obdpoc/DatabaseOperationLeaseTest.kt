package com.volttracker.obdpoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseOperationLeaseTest {
    @Test
    fun aBackupLeaseBlocksEveryCompetingDatabaseOperationUntilReleased() {
        val backup = DatabaseOperationLease.tryAcquire("backup")
        assertNotNull(backup)
        try {
            assertTrue(DatabaseOperationLease.isHeld())
            assertNull(DatabaseOperationLease.tryAcquire("restore"))
        } finally {
            backup?.close()
        }
        assertFalse(DatabaseOperationLease.isHeld())
        DatabaseOperationLease.tryAcquire("restore")!!.close()
    }

    @Test
    fun failedRollbackPreservesTheOnlyRestoreSafetyCopy() {
        assertFalse(BackupController.shouldDeleteRestoreSafetyCopy(rollbackFailed = true))
        assertTrue(BackupController.shouldDeleteRestoreSafetyCopy(rollbackFailed = false))
    }
}
