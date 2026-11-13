package app.moneytracker.web

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 hash of the dashboard password, stored as "w1:salt:key" in hex. */
object WebPassword {
    private const val PREFIX = "w1:"
    const val MIN_LENGTH = 4

    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return PREFIX + hex(salt) + ":" + hex(derive(password, salt))
    }

    fun check(password: String, stored: String?): Boolean {
        val parts = stored?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.split(':')?.takeIf { it.size == 2 } ?: return false
        val salt = unhex(parts[0]) ?: return false
        val key = unhex(parts[1]) ?: return false
        return MessageDigest.isEqual(derive(password, salt), key)
    }

    private fun derive(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(password.toCharArray(), salt, 20_000, 256)).encoded

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val bytes = s.chunked(2).map { it.toIntOrNull(16) ?: return null }
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }
}
