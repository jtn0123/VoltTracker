package com.volttracker.obdpoc

import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The car-controls opt-in and PIN, persisted in the shared prefs file under [PREFIX]. Off by
 * default. Enabling requires a 4-8 digit PIN; only a salted PBKDF2 hash of it is stored, never the
 * PIN itself. Disabling wipes the hash, so re-enabling always sets a new PIN.
 */
class CarControlSettings(
    private val prefs: () -> SharedPreferences?,
) {
    fun isEnabled(): Boolean {
        val p = prefs() ?: return false
        return p.getBoolean(KEY_ENABLED, false) && !p.getString(KEY_PIN_HASH, null).isNullOrEmpty()
    }

    /** Turns controls on with [pin]; false (and nothing stored) when the PIN is not 4-8 digits. */
    fun enable(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val p = prefs() ?: return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        p.edit {
            putString(KEY_PIN_SALT, toHex(salt))
            putString(KEY_PIN_HASH, toHex(hash(pin, salt)))
            putBoolean(KEY_ENABLED, true)
        }
        return true
    }

    fun disable() {
        prefs()?.edit {
            putBoolean(KEY_ENABLED, false)
            remove(KEY_PIN_HASH)
            remove(KEY_PIN_SALT)
        }
    }

    fun verifyPin(pin: String): Boolean {
        val p = prefs() ?: return false
        val stored = p.getString(KEY_PIN_HASH, null) ?: return false
        val salt = fromHex(p.getString(KEY_PIN_SALT, null) ?: return false) ?: return false
        if (!isValidPin(pin)) return false
        return MessageDigest.isEqual(toHex(hash(pin, salt)).toByteArray(), stored.toByteArray())
    }

    companion object {
        /** Key prefix owned by car controls (see [PrefsKeyOwnership]). */
        const val PREFIX = "car_control_"
        const val KEY_ENABLED = "car_control_enabled"
        const val KEY_PIN_HASH = "car_control_pin_hash"
        const val KEY_PIN_SALT = "car_control_pin_salt"
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        private const val SALT_BYTES = 16
        private const val ITERATIONS = 20_000
        private const val KEY_BITS = 256

        @JvmStatic
        fun isValidPin(pin: String?): Boolean =
            pin != null && pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }

        // PBKDF2WithHmacSHA1 is the PBKDF2 variant available on every supported API level (23+).
        private fun hash(
            pin: String,
            salt: ByteArray,
        ): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.US, it) }

        private fun fromHex(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = hex.substring(i * 2, i * 2 + 2).toIntOrNull(HEX_RADIX)?.toByte() ?: return null
            }
            return out
        }

        private const val HEX_RADIX = 16
    }
}

/**
 * In-memory authorization for car commands, shared by the dashboard bridge (which shows the native
 * PIN + confirmation dialog) and the polling engine (which transmits).
 *
 * - A correct PIN opens a short [UNLOCK_WINDOW_MS] window in which further commands need only the
 *   confirmation dialog, not the PIN again. Nothing about it is persisted: a process restart locks.
 * - Each confirmed dialog grants exactly ONE execution of exactly that command, valid for
 *   [CONFIRMATION_TTL_MS]; the engine [consume]s it before transmitting, so nothing can be sent
 *   without a fresh native confirmation (no replay, no queueing, no scheduling).
 * - [MAX_PIN_FAILURES] wrong PINs lock PIN entry for [PIN_LOCKOUT_MS].
 */
object CarControlAuth {
    const val UNLOCK_WINDOW_MS = 5L * 60L * 1000L
    const val CONFIRMATION_TTL_MS = 30_000L
    const val MAX_PIN_FAILURES = 5
    const val PIN_LOCKOUT_MS = 5L * 60L * 1000L

    private class Grant(
        val command: CarCommand,
        val atMs: Long,
    )

    private val lock = Any()
    private var unlockedUntilMs = 0L
    private var grant: Grant? = null
    private var pinFailures = 0
    private var pinLockedUntilMs = 0L

    fun isUnlocked(now: Long): Boolean = synchronized(lock) { now < unlockedUntilMs }

    fun unlockedRemainingMs(now: Long): Long = synchronized(lock) { maxOf(0L, unlockedUntilMs - now) }

    fun isPinLockedOut(now: Long): Boolean = synchronized(lock) { now < pinLockedUntilMs }

    /** Records a PIN attempt; returns [ok] so callers can chain it. */
    fun recordPinAttempt(
        ok: Boolean,
        now: Long,
    ): Boolean =
        synchronized(lock) {
            if (ok) {
                pinFailures = 0
                unlockedUntilMs = now + UNLOCK_WINDOW_MS
            } else {
                pinFailures += 1
                if (pinFailures >= MAX_PIN_FAILURES) {
                    pinFailures = 0
                    pinLockedUntilMs = now + PIN_LOCKOUT_MS
                }
            }
            ok
        }

    /** Grants one execution of [command] after the user confirmed it in the native dialog. */
    fun confirm(
        command: CarCommand,
        now: Long,
    ) = synchronized(lock) {
        grant = Grant(command, now)
    }

    /** True exactly once per [confirm] of the same command within its TTL. */
    fun consume(
        command: CarCommand,
        now: Long,
    ): Boolean =
        synchronized(lock) {
            val current = grant ?: return@synchronized false
            grant = null
            current.command == command && now - current.atMs in 0..CONFIRMATION_TTL_MS
        }

    /** Ends the unlock window and drops any unconsumed confirmation. */
    fun relock() =
        synchronized(lock) {
            unlockedUntilMs = 0L
            grant = null
        }

    /** Test hook: back to the initial state. */
    fun resetForTest() =
        synchronized(lock) {
            unlockedUntilMs = 0L
            grant = null
            pinFailures = 0
            pinLockedUntilMs = 0L
        }
}
