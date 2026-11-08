package com.volttracker.obdpoc.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.volttracker.obdpoc.AppPrefs
import java.io.File
import java.security.MessageDigest

/**
 * Confirms a downloaded APK is this same app, signed with this same key, before the install
 * sheet opens. Android refuses a mismatched APK anyway, but only after the user taps Install
 * and with a vague "app not installed" error; checking first turns a wrong or tampered asset
 * into a plain failed download instead.
 */
internal object ApkSignerCheck {
    /** The pure verdict: same package name and exactly the same set of signing certificates. */
    fun matches(
        installedPackage: String,
        installedSigners: Set<String>,
        apkPackage: String?,
        apkSigners: Set<String>,
    ): Boolean = apkPackage == installedPackage && installedSigners.isNotEmpty() && apkSigners == installedSigners

    /**
     * Reads both identities through [PackageManager] and applies [matches] on every supported
     * SDK. API 28+ reads `signingInfo`; API 23–27 only has the legacy signature list, which holds
     * the same certificates for an APK's current signers. Anything unreadable — a truncated or
     * non-APK file, a parser that throws, a package with no signers — is a mismatch, never a pass.
     */
    fun verify(
        context: Context,
        apk: File,
    ): Boolean {
        val read =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                readModern(context, apk)
            } else {
                readLegacy(context, apk)
            }
        return matches(
            installedPackage = context.packageName,
            installedSigners = read.installedSigners,
            apkPackage = read.apkPackage,
            apkSigners = read.apkSigners,
        )
    }

    /** What [verify] compares: this app's signers and the archive's package and signers. */
    private class Identities(
        val installedSigners: Set<String>,
        val apkPackage: String?,
        val apkSigners: Set<String>,
    )

    @RequiresApi(Build.VERSION_CODES.P)
    private fun readModern(
        context: Context,
        apk: File,
    ): Identities {
        val pm = context.packageManager
        val installed =
            readOrNull("installed package") {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        val archive =
            readOrNull("downloaded archive") {
                pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        return Identities(
            installedSigners = fingerprints(installed?.signingInfo?.apkContentsSigners),
            apkPackage = archive?.packageName,
            apkSigners = fingerprints(archive?.signingInfo?.apkContentsSigners),
        )
    }

    // GET_SIGNATURES and PackageInfo.signatures are deprecated from API 28, but they are the
    // documented signer API on API 23–27, the only levels this branch runs on. matches()
    // compares the complete signer set, never just signatures[0].
    @Suppress("DEPRECATION")
    private fun readLegacy(
        context: Context,
        apk: File,
    ): Identities {
        val pm = context.packageManager
        val installed =
            readOrNull("installed package") {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
        val archive =
            readOrNull("downloaded archive") {
                pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
            }
        return Identities(
            installedSigners = fingerprints(installed?.signatures),
            apkPackage = archive?.packageName,
            apkSigners = fingerprints(archive?.signatures),
        )
    }

    /**
     * The platform parser throws on some inputs rather than returning null (and the installed
     * lookup throws NameNotFoundException), so a read failure becomes "no identity", which
     * [matches] always rejects.
     */
    private inline fun readOrNull(
        what: String,
        read: () -> PackageInfo?,
    ): PackageInfo? =
        try {
            read()
        } catch (ex: PackageManager.NameNotFoundException) {
            Log.w(AppPrefs.LOG_TAG, "update signer check: $what not found", ex)
            null
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "update signer check: $what unreadable", ex)
            null
        }

    /** SHA-256 of each certificate's DER bytes, as lowercase hex (apksigner's digest format). */
    internal fun fingerprints(signers: Array<Signature>?): Set<String> =
        signers.orEmpty().mapTo(HashSet()) { signature ->
            MessageDigest
                .getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
}
