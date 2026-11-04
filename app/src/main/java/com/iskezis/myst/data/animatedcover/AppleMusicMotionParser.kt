package com.iskezis.myst.data.animatedcover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Extrai a arte em movimento da própria página pública do álbum da Apple
 * Music. A página traz um JSON (`serialized-server-data`) com URLs HLS em
 * `videoArtwork.dictionary`; nenhuma chave ou login é necessário.
 */
object AppleMusicMotionParser {

    private val serializedData = Regex(
        """<script[^>]*\bid\s*=\s*["']serialized-server-data["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val motionKeys = listOf(
        "motionDetailSquare",
        "motionSquareVideo1x1",
        "motionDetailRaw",
        "motionDetailTall",
        "motionTallVideo3x4",
    )

    fun extractMotionUrl(html: String, albumId: Long? = null): String? {
        val script = serializedData.find(html)?.groupValues?.getOrNull(1) ?: return null
        val root = runCatching { Json.parseToJsonElement(script) }.getOrNull() ?: return null
        val stack = ArrayDeque<JsonElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            when (val element = stack.removeLast()) {
                is JsonObject -> {
                    val artwork = element["videoArtwork"] as? JsonObject
                    if (artwork != null && belongsToAlbum(element, albumId)) {
                        motionUrlFromArtwork(artwork)?.let { return it }
                    }
                    element.values.forEach(stack::addLast)
                }

                is JsonArray -> element.forEach(stack::addLast)
                else -> Unit
            }
        }
        return null
    }

    private fun belongsToAlbum(element: JsonObject, albumId: Long?): Boolean {
        if (albumId == null) return true
        val id = (element["id"] as? JsonPrimitive)?.contentOrNull ?: return false
        return id.contains(albumId.toString())
    }

    private fun motionUrlFromArtwork(artwork: JsonObject): String? {
        val dictionary = artwork["dictionary"] as? JsonObject ?: return null
        for (key in motionKeys) {
            val motion = dictionary[key] as? JsonObject ?: continue
            val url = (motion["video"] as? JsonPrimitive)?.contentOrNull
                ?: (motion["videoUrl"] as? JsonPrimitive)?.contentOrNull
                ?: (motion["hlsUrl"] as? JsonPrimitive)?.contentOrNull
                ?: (motion["url"] as? JsonPrimitive)?.contentOrNull
            if (url.isNullOrBlank()) continue
            if (!url.startsWith("https://")) continue
            if (!url.contains(".m3u8", ignoreCase = true) &&
                !url.contains(".mp4", ignoreCase = true)
            ) {
                continue
            }
            return url
        }
        return null
    }
}
