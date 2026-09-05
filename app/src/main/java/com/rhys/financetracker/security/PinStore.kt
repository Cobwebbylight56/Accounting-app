package com.rhys.financetracker.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the app PIN.
 *
 * The PIN itself is never written down.  What is stored is a PBKDF2-HMAC-SHA256
 * hash with a random per-install salt, inside [EncryptedSharedPreferences] whose
 * key is held in the Android keystore.  That means an attacker with the device
 * file system has neither the PIN nor a cheap way to brute-force it.
 *
 * The comparison is constant-time, so the failure cannot be timed to learn how
 * many leading digits were right.
 */
@Singleton
class PinStore @Inject constructor(
    private val context: Context,
) {

    private companion object {
        const val FILE_NAME = "secure_prefs"
        const val KEY_HASH = "pin_hash"
        const val KEY_SALT = "pin_salt"
        const val KEY_FAILED_ATTEMPTS = "pin_failed_attempts"
        const val KEY_LOCKED_UNTIL = "pin_locked_until"

        /** Cost factor. High enough to slow an offline attack, fast enough to feel instant. */
        const val ITERATIONS = 120_000
        const val KEY_LENGTH_BITS = 256
        const val SALT_LENGTH_BYTES = 16

        /** After this many wrong PINs the app pauses before accepting another. */
        const val MAX_ATTEMPTS_BEFORE_DELAY = 5
        const val LOCKOUT_MILLIS = 30_000L
    }

    /**
     * Created lazily: building the master key touches the keystore, which is
     * slow enough that it should not happen during app start.
     */
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val isPinSet: Boolean get() = prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash(pin, salt).toHex())
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKED_UNTIL, 0L)
            .apply()
    }

    fun clearPin() {
        prefs.edit()
            .remove(KEY_HASH)
            .remove(KEY_SALT)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKED_UNTIL)
            .apply()
    }

    /**
     * @return [PinCheck.Correct], [PinCheck.Wrong] with the attempts left, or
     *   [PinCheck.TemporarilyLocked] while the cool-off is running.
     */
    fun checkPin(pin: String): PinCheck {
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        val now = System.currentTimeMillis()
        if (lockedUntil > now) return PinCheck.TemporarilyLocked(lockedUntil - now)

        val storedHash = prefs.getString(KEY_HASH, null)?.fromHex()
        val salt = prefs.getString(KEY_SALT, null)?.fromHex()
        if (storedHash == null || salt == null) return PinCheck.NotSet

        return if (constantTimeEquals(hash(pin, salt), storedHash)) {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).putLong(KEY_LOCKED_UNTIL, 0L).apply()
            PinCheck.Correct
        } else {
            val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
            if (attempts >= MAX_ATTEMPTS_BEFORE_DELAY) {
                editor.putLong(KEY_LOCKED_UNTIL, now + LOCKOUT_MILLIS).putInt(KEY_FAILED_ATTEMPTS, 0)
                editor.apply()
                PinCheck.TemporarilyLocked(LOCKOUT_MILLIS)
            } else {
                editor.apply()
                PinCheck.Wrong(MAX_ATTEMPTS_BEFORE_DELAY - attempts)
            }
        }
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Compares every byte regardless of where the first difference is. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) {
            difference = difference or (a[index].toInt() xor b[index].toInt())
        }
        return difference == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** The result of checking a PIN. */
sealed interface PinCheck {
    data object Correct : PinCheck
    data object NotSet : PinCheck
    data class Wrong(val attemptsRemaining: Int) : PinCheck
    data class TemporarilyLocked(val millisRemaining: Long) : PinCheck
}
