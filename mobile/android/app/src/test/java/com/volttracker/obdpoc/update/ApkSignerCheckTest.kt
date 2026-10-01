package com.volttracker.obdpoc.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkSignerCheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun sameAppAndSameKeyMatches() {
        assertTrue(ApkSignerCheck.matches(PKG, setOf("aa"), PKG, setOf("aa")))
    }

    @Test
    fun aDifferentKeyOrAppIsRejected() {
        assertFalse("different key", ApkSignerCheck.matches(PKG, setOf("aa"), PKG, setOf("bb")))
        assertFalse("extra signer", ApkSignerCheck.matches(PKG, setOf("aa"), PKG, setOf("aa", "bb")))
        assertFalse("different app", ApkSignerCheck.matches(PKG, setOf("aa"), "$PKG.debug", setOf("aa")))
        assertFalse("unreadable apk", ApkSignerCheck.matches(PKG, setOf("aa"), null, emptySet()))
        assertFalse("unknown installed key", ApkSignerCheck.matches(PKG, emptySet(), PKG, emptySet()))
    }

    @Test
    fun aFileThatIsNotAnApkFailsVerification() {
        val junk = temp.newFile("update.apk").apply { writeText("<html>not an apk</html>") }
        assertFalse(ApkSignerCheck.verify(RuntimeEnvironment.getApplication(), junk))
    }

    private companion object {
        const val PKG = "com.volttracker.obdpoc"
    }
}
