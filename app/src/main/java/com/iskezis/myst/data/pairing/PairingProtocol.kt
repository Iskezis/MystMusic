package com.iskezis.myst.data.pairing

import android.util.Base64
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class PairRequest(
    val codeHash: String,
    val nonce: String,
    val phonePort: Int,
    val deviceName: String = "",
)

@Serializable
data class PairConfirm(
    val code: String,
    val tvName: String = "",
)

@Serializable
data class PairPayload(
    val payload: String,
)

@Serializable
data class PairAck(
    val ok: Boolean,
    val error: String? = null,
)

/**
 * Dados transferidos do celular para a TV. A sessão não carrega a senha local:
 * o objetivo do pareamento é entrar na mesma conta e receber as playlists, não
 * copiar credenciais que a TV não precisa.
 */
@Serializable
data class PairAccount(
    val type: String,
    val username: String,
    val displayName: String,
)

@Serializable
data class PairTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
)

@Serializable
data class PairPlaylist(
    val name: String,
    val coverUri: String? = null,
    val tracks: List<PairTrack> = emptyList(),
)

@Serializable
data class PairBundle(
    val account: PairAccount? = null,
    val youtubeCookies: String? = null,
    val youtubeSapisid: String? = null,
    val youtubeVisitorData: String? = null,
    val youtubeDataSyncId: String? = null,
    val playlists: List<PairPlaylist> = emptyList(),
)

object PairingCrypto {

    private const val SALT = "MystMusicPairing-v1"
    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12

    private val random = SecureRandom()

    fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun newCode(): String = "%05d".format(random.nextInt(100_000))

    fun hashCode(code: String, nonce: String): String =
        com.iskezis.myst.util.sha256Hex("$code:$nonce")

    /** Comparação em tempo constante para não vazar o código por timing. */
    fun matchesHash(code: String, nonce: String, expected: String): Boolean =
        java.security.MessageDigest.isEqual(
            hashCode(code, nonce).toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )

    fun encrypt(code: String, plaintext: String): String {
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(code), "AES"),
            GCMParameterSpec(128, iv),
        )
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        iv.copyInto(combined, 0)
        encrypted.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(code: String, encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= IV_BYTES) throw IllegalArgumentException("payload")
        val iv = combined.copyOfRange(0, IV_BYTES)
        val encrypted = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(deriveKey(code), "AES"),
            GCMParameterSpec(128, iv),
        )
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun deriveKey(code: String): ByteArray {
        val spec = PBEKeySpec(
            code.toCharArray(),
            SALT.toByteArray(Charsets.UTF_8),
            ITERATIONS,
            KEY_BITS,
        )
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }
}
