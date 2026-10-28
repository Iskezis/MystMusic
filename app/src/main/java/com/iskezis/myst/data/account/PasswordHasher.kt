package com.iskezis.myst.data.account

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2 com fallback para aparelhos antigos (API 21/22 não têm HmacSHA256).
 * O algoritmo e as iterações ficam gravados no próprio hash.
 */
object PasswordHasher {

    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256
    private const val SALT_BYTES = 16

    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hash(password: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val algorithm = chooseAlgorithm()
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val encoded = try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return "$algorithm:$ITERATIONS:${Base64.encodeToString(encoded, Base64.NO_WRAP)}"
    }

    fun verify(password: String, saltBase64: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 3) return false
        val algorithm = parts[0]
        val iterations = parts[1].toIntOrNull() ?: return false
        val expected = try {
            Base64.decode(parts[2], Base64.NO_WRAP)
        } catch (t: Throwable) {
            return false
        }
        val salt = try {
            Base64.decode(saltBase64, Base64.NO_WRAP)
        } catch (t: Throwable) {
            return false
        }
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, expected.size * 8)
        val actual = try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } catch (t: Throwable) {
            return false
        } finally {
            spec.clearPassword()
        }
        return MessageDigest.isEqual(expected, actual)
    }

    private fun chooseAlgorithm(): String {
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            "PBKDF2WithHmacSHA256"
        } catch (t: Throwable) {
            "PBKDF2WithHmacSHA1"
        }
    }
}
