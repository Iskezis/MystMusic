package com.iskezis.myst.util

/**
 * O YouTube entrega vários tamanhos de miniatura e nem sempre o maior vem por
 * último no JSON. Estas funções normalizam a URL para a melhor resolução
 * disponível, inclusive para músicas já salvas em playlists antigas.
 */
object Thumbnails {

    private val youTubeId = Regex("""(?:i\.ytimg\.com|img\.youtube\.com)/vi/([A-Za-z0-9_-]{11})/""")
    private val googleSize = Regex("""=w\d+-h\d+""")
    private val googleSquare = Regex("""=s\d+""")

    fun best(url: String?, videoId: String? = null): String? {
        val id = videoId?.takeIf(::isVideoId)
            ?: url?.let { youTubeId.find(it)?.groupValues?.getOrNull(1) }
        if (url.isNullOrBlank()) return id?.let(::youTubeUrl)
        if (url.contains("ytimg.com") || url.contains("img.youtube.com")) {
            if (url.contains("maxresdefault") || url.contains("hq720")) return url
            val lowQuality = url.contains("default.jpg") ||
                url.contains("hqdefault") ||
                url.contains("mqdefault") ||
                url.contains("sddefault")
            if (lowQuality && id != null) return youTubeUrl(id)
            return url
        }
        if (url.contains("googleusercontent") || url.contains("ggpht")) {
            return url
                .replace(googleSize, "=w1080-h1080")
                .replace(googleSquare, "=s1080")
        }
        return url
    }

    private fun youTubeUrl(id: String): String =
        "https://i.ytimg.com/vi/$id/maxresdefault.jpg"

    private fun isVideoId(value: String): Boolean =
        value.length == 11 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
