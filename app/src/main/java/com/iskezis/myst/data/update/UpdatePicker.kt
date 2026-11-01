package com.iskezis.myst.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Canal de atualização escolhido pelo usuário neste aparelho. */
enum class UpdateChannel(val storageValue: String) {
    STABLE("stable"),
    BETA("beta");

    companion object {
        fun fromStorage(value: String?): UpdateChannel =
            entries.firstOrNull { it.storageValue == value } ?: STABLE
    }
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0L,
)

data class PickedRelease(
    val release: GitHubRelease,
    val versionCode: Int,
    val isBeta: Boolean,
    val asset: GitHubAsset,
    val checksumUrl: String?,
)

/**
 * Regras puras de escolha da atualização. Ficam fora do repositório para
 * poderem ser testadas sem Android/Room/rede.
 */
object UpdatePicker {

    private const val CHECKSUM_ASSET = "SHA256SUMS.txt"

    private val BETA_TAG = Regex(
        pattern = "(?i).*[-.](alpha|beta|rc|dev)([-.]?\\d+)?.*",
    )
    private val DISPLAY_TAG = Regex(
        pattern = "(?i)^v?(\\d+(?:\\.\\d+)*)[-.](alpha|beta|rc|dev)[-.]?(\\d+)?$",
    )
    private val SEMVER = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)")
    private val VERSION_CODE = Regex(
        pattern = "(?im)^\\s*versionCode\\s*:\\s*(\\d+)\\s*$",
    )

    fun isBeta(tagName: String, prerelease: Boolean): Boolean =
        prerelease || BETA_TAG.matches(tagName.trim())

    /**
     * Devolve a melhor release do canal ou `null` quando não há nada mais novo
     * que a versão instalada. Releases sem o APK do flavor são ignoradas em
     * vez de interromper a verificação inteira.
     */
    fun pick(
        channel: UpdateChannel,
        installedCode: Int,
        installedVersionName: String,
        releases: List<GitHubRelease>,
        assetName: String,
        isAllowedAssetUrl: (String) -> Boolean,
    ): PickedRelease? = releases.asSequence()
        .filterNot { it.draft }
        .mapNotNull { release ->
            val code = versionCodeOf(release, installedCode, installedVersionName)
                ?: return@mapNotNull null
            if (code <= installedCode) return@mapNotNull null
            val beta = isBeta(release.tagName, release.prerelease)
            if (channel == UpdateChannel.STABLE && beta) return@mapNotNull null
            val asset = release.assets.firstOrNull {
                it.name == assetName && isAllowedAssetUrl(it.downloadUrl)
            } ?: return@mapNotNull null
            val checksum = release.assets.firstOrNull {
                it.name == CHECKSUM_ASSET && isAllowedAssetUrl(it.downloadUrl)
            }
            PickedRelease(
                release = release,
                versionCode = code,
                isBeta = beta,
                asset = asset,
                checksumUrl = checksum?.downloadUrl,
            )
        }
        .maxByOrNull { it.versionCode }

    /**
     * Nome amigável para o pop-up. Tags como `v0.2.5-beta.1` viram
     * `0.2.5 Beta 1`; tags estáveis ficam sem o `v`.
     */
    fun displayVersion(tagName: String, releaseName: String, versionCode: Int): String {
        val clean = tagName.trim()
            .removePrefix("v")
            .ifBlank {
                releaseName.trim().removePrefix("v").ifBlank { versionCode.toString() }
            }
        val match = DISPLAY_TAG.matchEntire(clean) ?: return clean
        val base = match.groupValues[1]
        val kind = when (match.groupValues[2].lowercase()) {
            "alpha" -> "Alpha"
            "beta" -> "Beta"
            "rc" -> "RC"
            else -> "Dev"
        }
        val number = match.groupValues[3]
        return if (number.isBlank()) "$base $kind" else "$base $kind $number"
    }

    private fun versionCodeOf(
        release: GitHubRelease,
        installedCode: Int,
        installedVersionName: String,
    ): Int? {
        val declared = VERSION_CODE.find(release.body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (declared != null && declared > 0) return declared

        // Releases antigas podem não ter a linha versionCode. Nesse caso só é
        // seguro atualizar quando o núcleo SemVer remoto é maior que o local.
        val remote = SEMVER.find(release.tagName) ?: return null
        val local = SEMVER.find(installedVersionName) ?: return null
        val remoteParts = remote.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        val localParts = local.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        if (remoteParts.size != 3 || localParts.size != 3) return null
        for (index in 0..2) {
            if (remoteParts[index] > localParts[index]) return installedCode + 1
            if (remoteParts[index] < localParts[index]) return null
        }
        return null
    }
}

/** Isola o JSON para que o teste de fixture use exatamente o mesmo parser. */
object UpdateReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<GitHubRelease> =
        json.decodeFromString(body)
}
