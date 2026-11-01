package com.iskezis.myst.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras puras do canal de atualização. A fixture é o mesmo JSON que a API
 * `/releases` devolve (campos desconhecidos são ignorados pelo parser).
 */
class UpdatePickerTest {

    private val releases: List<GitHubRelease> by lazy {
        UpdateReleaseParser.parse(fixture("fixtures/releases.json"))
    }

    private fun fixture(path: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Fixture ausente: $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun pick(
        channel: UpdateChannel,
        installedCode: Int = 44,
        installedVersionName: String = "0.1.8 Beta 5",
        list: List<GitHubRelease> = releases,
        assetName: String = "app.apk",
    ): PickedRelease? = UpdatePicker.pick(
        channel = channel,
        installedCode = installedCode,
        installedVersionName = installedVersionName,
        releases = list,
        assetName = assetName,
        isAllowedAssetUrl = { it.startsWith("https://github.com/") },
    )

    @Test
    fun `stable ignora beta pela tag e prerelease do GitHub`() {
        val picked = pick(UpdateChannel.STABLE)
        assertEquals(50, picked?.versionCode)
        assertEquals("v0.2.0", picked?.release?.tagName)
        assertFalse(picked?.isBeta ?: true)
    }

    @Test
    fun `beta pega a maior versao valida, inclusive prerelease`() {
        val picked = pick(UpdateChannel.BETA)
        assertEquals(53, picked?.versionCode)
        assertEquals("v0.2.5", picked?.release?.tagName)
        assertTrue(picked?.isBeta ?: false)
    }

    @Test
    fun `nunca oferece downgrade`() {
        assertNull(
            pick(
                channel = UpdateChannel.BETA,
                installedCode = 60,
                installedVersionName = "0.3.0",
            ),
        )
    }

    @Test
    fun `stable sem versao mais nova devolve up to date`() {
        assertNull(
            pick(
                channel = UpdateChannel.STABLE,
                installedCode = 50,
                installedVersionName = "0.2.0",
            ),
        )
    }

    @Test
    fun `release sem o apk do flavor e ignorada`() {
        val onlyTv = releases.filter { it.tagName == "v0.2.2" }
        assertNull(pick(UpdateChannel.STABLE, list = onlyTv))
    }

    @Test
    fun `rascunho e ignorado`() {
        val draft = releases.filter { it.draft }
        assertNull(pick(UpdateChannel.BETA, list = draft))
    }

    @Test
    fun `lista vazia devolve up to date`() {
        assertNull(pick(UpdateChannel.BETA, list = emptyList()))
    }

    @Test
    fun `json invalido falha de forma explicita`() {
        assertThrows(Throwable::class.java) {
            UpdateReleaseParser.parse("{ isto não é json")
        }
    }

    @Test
    fun `checksum e encontrado quando existe`() {
        val picked = pick(UpdateChannel.STABLE)
        assertTrue(picked?.checksumUrl?.endsWith("SHA256SUMS.txt") == true)
    }

    @Test
    fun `classificacao beta usa tag ou flag prerelease`() {
        assertTrue(UpdatePicker.isBeta("v0.2.1-beta.1", prerelease = false))
        assertTrue(UpdatePicker.isBeta("v0.2.1", prerelease = true))
        assertTrue(UpdatePicker.isBeta("v0.2.0-rc2", prerelease = false))
        assertTrue(UpdatePicker.isBeta("v0.2.0-dev.5", prerelease = false))
        assertFalse(UpdatePicker.isBeta("v0.2.0", prerelease = false))
    }

    @Test
    fun `nome amigavel da versao`() {
        assertEquals("0.2.0", UpdatePicker.displayVersion("v0.2.0", "", 50))
        assertEquals(
            "0.2.1 Beta 1",
            UpdatePicker.displayVersion("v0.2.1-beta.1", "", 51),
        )
        assertEquals(
            "0.2.0 RC 2",
            UpdatePicker.displayVersion("v0.2.0-rc.2", "", 52),
        )
        assertEquals(
            "0.2.0 Dev 5",
            UpdatePicker.displayVersion("v0.2.0-dev.5", "", 53),
        )
    }
}
