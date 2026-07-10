package com.volttracker.obdpoc.data

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Creates stable, non-enumerable vehicle keys without storing the raw VIN in SQLite. */
class VinKeyHasher(
    context: Context,
) {
    private val secret: ByteArray = loadOrCreateSecret(context.applicationContext)

    fun hash(vin: String): String =
        try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
            mac.doFinal(vin.toByteArray(StandardCharsets.UTF_8)).toHex()
        } catch (ex: GeneralSecurityException) {
            throw IllegalStateException("VIN HMAC unavailable", ex)
        }

    companion object {
        private const val PREFS_NAME = "vehicle_identity"
        private const val SECRET_KEY = "vin_hmac_secret_v1"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val SECRET_BYTES = 32

        fun legacyHash(vin: String): String =
            try {
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(vin.toByteArray(StandardCharsets.UTF_8))
                    .toHex()
            } catch (ex: GeneralSecurityException) {
                throw IllegalStateException("SHA-256 unavailable", ex)
            }

        private fun loadOrCreateSecret(context: Context): ByteArray {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getString(SECRET_KEY, null)
            if (!stored.isNullOrBlank()) {
                try {
                    val decoded = Base64.decode(stored, Base64.NO_WRAP)
                    if (decoded.size == SECRET_BYTES) return decoded
                } catch (_: IllegalArgumentException) {
                    // Replace corrupt local state below.
                }
            }
            val generated = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
            val saved =
                prefs
                    .edit()
                    .putString(SECRET_KEY, Base64.encodeToString(generated, Base64.NO_WRAP))
                    .commit()
            check(saved) { "Could not persist VIN HMAC secret" }
            return generated
        }

        private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
    }
}
