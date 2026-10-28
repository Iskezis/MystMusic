---
name: media3-playback
description: Player de música com Media3 (ExoPlayer + MediaSessionService), notificação de mídia, mini player, fila, foco de áudio e resolução de URL expirada.
---
# Reprodução (Media3)
- `PlaybackService : MediaSessionService` (foreground, `foregroundServiceType="mediaPlayback"`, `android:exported="true"` com intent-filter `androidx.media3.session.MediaSessionService`).
- `ExoPlayer` com `setAudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC, true)` e `setHandleAudioBecomingNoisy(true)`. Fila (`MediaItem`) com metadados (título, artista, capa).
- Resolver stream sob demanda: `ResolvingDataSource` que troca `videoId` por URL real; em `PlaybackException` 403/410 → reresolver e retomar na posição.
- Controles: play/pause, próxima, anterior, shuffle, repeat, seek. Notificação padrão Media3 com capa.
- UI: `MediaController` no ViewModel; `StateFlow` com `isPlaying`, `position`, `duration`, `currentItem`. Atualizar progresso a cada 500ms só com UI visível.
- Cada mudança de faixa: gravar em `history`, atualizar Discord RPC.
- Pedir `POST_NOTIFICATIONS` (API 33+) com explicação. Sem crash se negado.
- TV: mesmo serviço; teclas de mídia tratadas.
