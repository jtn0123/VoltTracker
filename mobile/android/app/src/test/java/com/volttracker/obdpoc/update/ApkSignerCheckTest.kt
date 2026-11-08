package com.volttracker.obdpoc.update

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.jar.JarFile

/**
 * [ApkSignerCheck.verify] on both branches: the legacy `GET_SIGNATURES` read (API 23–27) and
 * the `signingInfo` read (API 28+). Every case runs at SDK 23, 27, 28 and 34.
 *
 * Robolectric cannot extract signers from a real archive on these SDKs. Probed 2026-11-07 with
 * the fixtures below: SDK 23's parser throws, 27 returns null, 28 parses the package but no
 * signers, and 34's v2 verifier hits a JDK access error. So:
 * - Junk bytes go through the real platform parser, unstubbed, on every SDK.
 * - The accept/reject cases stub only the platform's parse result
 *   (`ShadowPackageManager.setPackageArchiveInfo`) and run the production branch for real.
 *   The certificates come from real signed fixtures, read by the JDK's own JAR (v1) verifier,
 *   the scheme API 23 phones check. Each SDK's stub carries only the field its branch must
 *   read, so a branch that reads the wrong API rejects the matching identity and fails.
 *
 * Fixtures (`src/test/resources/update/`): a manifest-only APK for package
 * `com.volttracker.fixture`, built with `aapt2 link` and signed v1+v2 by
 * `apksigner sign --min-sdk-version 23` with throwaway keys A and B. The keystores were
 * discarded; neither is the release key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 27, 28, 34])
class ApkSignerCheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication()

    /** The app as verify() sees it: the fixture's package name over the real PackageManager. */
    private val self =
        object : ContextWrapper(app) {
            override fun getPackageName(): String = FIXTURE_PKG
        }

    private val keyA by lazy { certificateOf("fixture-key-a.apk") }
    private val keyB by lazy { certificateOf("fixture-key-b.apk") }

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
    fun fingerprintsMatchApksignersDigestOfTheRealFixtureCertificates() {
        // `apksigner verify --print-certs` digests, taken when the fixtures were signed.
        assertEquals(setOf(KEY_A_SHA256), ApkSignerCheck.fingerprints(arrayOf(keyA)))
        assertEquals(setOf(KEY_B_SHA256), ApkSignerCheck.fingerprints(arrayOf(keyB)))
    }

    @Test
    fun theMatchingIdentityIsAccepted() {
        installSelf(keyA)
        val apk = stubArchive(FIXTURE_PKG, keyA)

        assertTrue("SDK ${Build.VERSION.SDK_INT}", ApkSignerCheck.verify(self, apk))
    }

    @Test
    fun aWrongKeyIsRejected() {
        installSelf(keyA)

        assertFalse(ApkSignerCheck.verify(self, stubArchive(FIXTURE_PKG, keyB)))
    }

    @Test
    fun anExtraSignerIsRejected() {
        installSelf(keyA)

        assertFalse(ApkSignerCheck.verify(self, stubArchive(FIXTURE_PKG, keyA, keyB)))
    }

    @Test
    fun anotherPackageIsRejected() {
        installSelf(keyA)

        assertFalse(ApkSignerCheck.verify(self, stubArchive("com.volttracker.other", keyA)))
    }

    @Test
    fun anArchiveWithMissingSignersIsRejected() {
        installSelf(keyA)

        assertFalse("no signer field", ApkSignerCheck.verify(self, stubArchive(FIXTURE_PKG, signers = null)))
        assertFalse("empty signer list", ApkSignerCheck.verify(self, stubArchive(FIXTURE_PKG)))
    }

    @Test
    fun anInstalledAppWithoutSignersRejectsEvenAnUnsignedArchive() {
        installSelf()

        assertFalse(ApkSignerCheck.verify(self, stubArchive(FIXTURE_PKG)))
    }

    @Test
    fun anAppThatCannotReadItsOwnPackageRejects() {
        // FIXTURE_PKG is not installed: the installed lookup throws NameNotFoundException.
        assertFalse(ApkSignerCheck.verify(self, stubArchive(FIXTURE_PKG, keyA)))
    }

    @Test
    fun junkBytesAreRejectedByTheRealPlatformParser() {
        installSelf(keyA)
        // Not stubbed: the real parser either returns null or (SDK 23 here) throws.
        val junk = temp.newFile("update.apk").apply { writeText("<html>not an apk</html>") }

        assertFalse(ApkSignerCheck.verify(self, junk))
    }

    // ---- helpers ----

    /** The signing certificate of a real fixture, as the JDK's JAR verifier reads it. */
    private fun certificateOf(fixture: String): Signature {
        val file = copyFixture(fixture)
        JarFile(file, true).use { jar ->
            val entry = jar.getJarEntry("AndroidManifest.xml")
            // Code signers are only known (and verified) once the entry has been read in full.
            jar.getInputStream(entry).use { it.readBytes() }
            val cert =
                entry.codeSigners
                    .single()
                    .signerCertPath.certificates
                    .first()
            return Signature(cert.encoded)
        }
    }

    private fun copyFixture(name: String): File {
        val out = temp.newFile(name)
        val stream =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("update/$name")) { "missing fixture $name" }
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun installSelf(vararg signers: Signature) {
        val info =
            PackageInfo().apply {
                packageName = FIXTURE_PKG
                applicationInfo = ApplicationInfo().apply { packageName = FIXTURE_PKG }
                withSigners(signers)
            }
        shadowOf(app.packageManager).installPackage(info)
    }

    /** A file whose platform parse result is [pkg] signed by [signers] (null = no signer field). */
    private fun stubArchive(
        pkg: String,
        vararg keys: Signature,
        signers: Array<out Signature>? = keys,
    ): File {
        val apk = temp.newFile()
        val info =
            PackageInfo().apply {
                packageName = pkg
                if (signers != null) withSigners(signers)
            }
        shadowOf(app.packageManager).setPackageArchiveInfo(apk.path, info)
        return apk
    }

    /** Only the field the running SDK's branch reads, so reading the other one finds nothing. */
    @Suppress("DEPRECATION")
    private fun PackageInfo.withSigners(signers: Array<out Signature>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(*signers)) }
        } else {
            signatures = arrayOf(*signers)
        }
    }

    private companion object {
        const val PKG = "com.volttracker.obdpoc"
        const val FIXTURE_PKG = "com.volttracker.fixture"
        const val KEY_A_SHA256 = "514e956889f4fee2ff4e5e3339cee6e4da122377d2d6e07363c33c1cef7e433a"
        const val KEY_B_SHA256 = "945b9ce2d64345b28bfc12c321e1667cf0e199d435555f7077d4843758d9e5ab"
    }
}
