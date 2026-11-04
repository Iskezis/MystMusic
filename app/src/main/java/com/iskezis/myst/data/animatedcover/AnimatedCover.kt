package com.iskezis.myst.data.animatedcover

/**
 * Chave de correspondência de uma faixa para os provedores de capa animada.
 * Os provedores são não oficiais: qualquer falha deve virar capa estática.
 */
data class TrackKey(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

data class AnimatedCover(
    val url: String,
    val provider: String,
    val mime: String,
    val aspect: String = "1:1",
)

data class ResolvedAnimatedCover(
    val albumKey: String,
    val cover: AnimatedCover,
)

/**
 * Provedor de arte em movimento. [find] devolve `null` quando não há
 * correspondência e lança em falhas de rede, para que o repositório consiga
 * diferenciar "não existe" de "não foi possível consultar agora".
 */
interface AnimatedCoverProvider {
    val id: String
    suspend fun find(track: TrackKey): AnimatedCover?
}
