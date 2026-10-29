package com.iskezis.myst.data.pairing

import android.util.Base64
import kotlinx.serialization.Serializable
import java.security.MessageDigest
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

/** Resposta de identificação do servidor persistente da TV. */
@Serializable
data class PairHello(
    val pairId: String,
    val deviceName: String = "",
    val appVersion: String = "",
    val paired: Boolean = false,
)

/**
 * Envelope do canal persistente. `payload` é AES-GCM com a chave derivada do
 * segredo criado no pareamento; quem não tem o segredo não consegue ler nem
 * forjar uma sincronização ou desconexão.
 */
@Serializable
data class PairSecureEnvelope(
    val pairId: String,
    val payload: String,
)

@Serializable
data class PairSyncRequest(
    val snapshot: PairSnapshot,
    val sentAt: Long = System.currentTimeMillis(),
)

@Serializable
data class PairSyncResponse(
    val snapshot: PairSnapshot,
    val sentAt: Long = System.currentTimeMillis(),
)

@Serializable
data class PairSnapshot(
    val generatedAt: Long = System.currentTimeMillis(),
    val playlists: List<PairPlaylist> = emptyList(),
    val tombstones: List<PairTombstone> = emptyList(),
    val history: List<PairHistory> = emptyList(),
    val affinities: List<PairAffinity> = emptyList(),
)

/** Nome de playlist apagada/renomeada; impede que a sincronização a ressuscite. */
@Serializable
data class PairTombstone(
    val key: String,
    val deletedAt: Long,
)

@Serializable
data class PairHistory(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val playedMs: Long = 0L,
    val playedAt: Long = 0L,
    val completed: Boolean = false,
)

@Serializable
data class PairAffinity(
    val artist: String,
    val score: Double = 0.0,
    val updatedAt: Long = 0L,
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
    /** JPEG 512x512 da conta Myst, em base64, dentro do payload cifrado. */
    val avatarBase64: String? = null,
    val youtubeAccountId: String? = null,
    val youtubeDisplayName: String? = null,
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
    val coverBase64: String? = null,
    val createdAt: Long = 0L,
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
    val history: List<PairHistory> = emptyList(),
    val affinities: List<PairAffinity> = emptyList(),
    val tombstones: List<PairTombstone> = emptyList(),
    /** Identidade do canal celular↔TV criada junto com o primeiro pareamento. */
    val pairId: String? = null,
    val pairSecret: String? = null,
    val phoneName: String = "",
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

    /** Segredo de 256 bits do canal persistente celular↔TV. */
    fun newSecret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

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

    /**
     * Canal persistente: o segredo já tem 256 bits aleatórios, então o SHA-256
     * direto basta e evita repetir 60 mil iterações a cada sincronização.
     */
    fun encryptSecret(secret: String, plaintext: String): String =
        encryptWithKey(secretKey(secret), plaintext)

    fun decryptSecret(secret: String, encoded: String): String =
        decryptWithKey(secretKey(secret), encoded)

    private fun secretKey(secret: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))

    private fun encryptWithKey(key: ByteArray, plaintext: String): String {
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        iv.copyInto(combined, 0)
        encrypted.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptWithKey(key: ByteArray, encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= IV_BYTES) throw IllegalArgumentException("payload")
        val iv = combined.copyOfRange(0, IV_BYTES)
        val encrypted = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
