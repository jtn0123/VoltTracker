package com.volttracker.obdpoc

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes a genuine v3 encrypted backup (one GCM operation over the whole file, 600k PBKDF2
 * iterations recorded in the header, magic+salt+IV+count as AAD), byte for byte the way releases
 * before the segmented v4 container did.
 *
 * The app now writes only v4 ([BackupCrypto.encryptFileSegmented]), but v3 files are still out
 * there and must keep restoring. The compatibility suites ([BackupCryptoTest], [DataBackupTest],
 * [BackupRoundTripTest]) mint their v3 containers here. It is a plain top-level function, not a
 * `BackupCrypto` extension, so a production method can never silently take over these call sites.
 */
internal fun writeLegacyV3Backup(
    source: File,
    dest: File,
    passphrase: String,
) {
    val magic = "VTBKEN3\n".toByteArray(Charsets.US_ASCII)
    val random = SecureRandom()
    val salt = ByteArray(16)
    random.nextBytes(salt)
    val iv = ByteArray(12)
    random.nextBytes(iv)
    val iterBytes = ByteBuffer.allocate(4).putInt(V3_ITERATIONS).array()
    val spec = PBEKeySpec(passphrase.toCharArray(), salt, V3_ITERATIONS, 256)
    val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
    cipher.updateAAD(magic)
    cipher.updateAAD(salt)
    cipher.updateAAD(iv)
    cipher.updateAAD(iterBytes)
    FileInputStream(source).use { input ->
        FileOutputStream(dest).use { fileOut ->
            fileOut.write(magic)
            fileOut.write(salt)
            fileOut.write(iv)
            fileOut.write(iterBytes)
            CipherOutputStream(fileOut, cipher).use { out -> input.copyTo(out) }
        }
    }
}

private const val V3_ITERATIONS = 600_000
