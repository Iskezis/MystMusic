package com.iskezis.myst.data.discord

import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Fingerprint que o cliente Android oficial do Discord envia nas chamadas
 * REST e no identify do gateway. O endpoint que registra imagens externas
 * rejeita requisições sem ele, mesmo quando o token é válido.
 *
 * Os UUIDs são gerados uma vez por processo, como no cliente real; não são
 * credenciais e nunca são registrados no Logcat.
 */
object DiscordSuperProperties {

    private const val CLIENT_VERSION = "314.13 - Stable"
    private const val CLIENT_BUILD_NUMBER = 314013
    private const val RELEASE_CHANNEL = "googleRelease"

    val userAgent: String = "Discord-Android/$CLIENT_BUILD_NUMBER;RNA"

    val base64: String by lazy {
        val properties = JSONObject().apply {
            put("os", "Android")
            put("browser", "Discord Android")
            put("device", Build.DEVICE.ifBlank { "Android" })
            put("system_locale", Locale.getDefault().toString())
            put("client_version", CLIENT_VERSION)
            put("release_channel", RELEASE_CHANNEL)
            put("device_vendor_id", UUID.randomUUID().toString())
            put("client_uuid", UUID.randomUUID().toString())
            put("client_launch_id", UUID.randomUUID().toString())
            put("os_version", Build.VERSION.RELEASE)
            put("os_sdk_version", Build.VERSION.SDK_INT.toString())
            put("client_build_number", CLIENT_BUILD_NUMBER)
            put("client_event_source", JSONObject.NULL)
            put("design_id", 0)
        }
        Base64.encodeToString(
            properties.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
    }
}
