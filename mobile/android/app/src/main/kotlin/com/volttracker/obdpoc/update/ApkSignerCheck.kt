package com.volttracker.obdpoc.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
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
     * Reads both identities through [PackageManager]. Below API 28 there is no non-deprecated
     * signer API, so the check is skipped there and the installer's own signature check stands.
     */
    fun verify(
        context: Context,
        apk: File,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val pm = context.packageManager
        val installed =
            runCatching { pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES) }
                .getOrNull()
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        return matches(
            installedPackage = context.packageName,
            installedSigners = fingerprints(installed?.signingInfo?.apkContentsSigners),
            apkPackage = archive?.packageName,
            apkSigners = fingerprints(archive?.signingInfo?.apkContentsSigners),
        )
    }

    private fun fingerprints(signers: Array<Signature>?): Set<String> =
        signers.orEmpty().mapTo(HashSet()) { signature ->
            MessageDigest
                .getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
}
