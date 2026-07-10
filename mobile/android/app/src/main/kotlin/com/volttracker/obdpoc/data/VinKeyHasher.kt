package com.volttracker.obdpoc.data

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
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
    private val appContext = context.applicationContext
    private val secret: ByteArray = loadOrCreateSecret(appContext)

    fun hash(vin: String): String = hashWith(secret, vin)

    /** Primary plus backup-imported keys, used to recognize the same restored vehicle. */
    fun hashCandidates(vin: String): List<String> = loadSecrets(appContext).map { hashWith(it, vin) }.distinct()

    private fun hashWith(
        key: ByteArray,
        vin: String,
    ): String =
        try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
            mac.doFinal(vin.toByteArray(StandardCharsets.UTF_8)).toHex()
        } catch (ex: GeneralSecurityException) {
            throw IllegalStateException("VIN HMAC unavailable", ex)
        }

    companion object {
        private const val PREFS_NAME = "vehicle_identity"
        private const val SECRET_KEY = "vin_hmac_secret_v1"
        private const val IMPORTED_SECRET_KEYS = "vin_hmac_imported_secrets_v1"
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

        /** Encoded identity keys embedded only in the full backup transport manifest. */
        fun exportSecrets(context: Context): List<String> =
            loadSecrets(context.applicationContext)
                .map { Base64.encodeToString(it, Base64.NO_WRAP) }
                .distinct()

        /** Adds valid donor keys without replacing this installation's primary identity key. */
        fun importSecrets(
            context: Context,
            encodedSecrets: Collection<String>,
        ) {
            val valid = encodedSecrets.mapNotNull(::decodeSecret).take(MAX_IMPORTED_SECRETS)
            if (valid.isEmpty()) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getStringSet(IMPORTED_SECRET_KEYS, emptySet()).orEmpty().toMutableSet()
            for (secret in valid) current.add(Base64.encodeToString(secret, Base64.NO_WRAP))
            prefs.edit { putStringSet(IMPORTED_SECRET_KEYS, current.take(MAX_IMPORTED_SECRETS).toSet()) }
        }

        internal fun replaceSecretsForTest(
            context: Context,
            encodedSecrets: List<String>,
        ) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit(commit = true) {
                clear()
                encodedSecrets.firstOrNull()?.let { putString(SECRET_KEY, it) }
                if (encodedSecrets.size > 1) putStringSet(IMPORTED_SECRET_KEYS, encodedSecrets.drop(1).toSet())
            }
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

        private fun loadSecrets(context: Context): List<ByteArray> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val out = ArrayList<ByteArray>()
            out.add(loadOrCreateSecret(context))
            for (encoded in prefs.getStringSet(IMPORTED_SECRET_KEYS, emptySet()).orEmpty()) {
                decodeSecret(encoded)?.let(out::add)
            }
            return out.distinctBy { Base64.encodeToString(it, Base64.NO_WRAP) }
        }

        private fun decodeSecret(encoded: String?): ByteArray? {
            if (encoded.isNullOrBlank()) return null
            return try {
                Base64.decode(encoded, Base64.NO_WRAP).takeIf { it.size == SECRET_BYTES }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private const val MAX_IMPORTED_SECRETS = 8

        private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
    }
}
