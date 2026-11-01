---
name: animated-covers
description: Capas animadas (vídeo curto em loop, mudo) no player do MystMusic via provedores intercambiáveis, com cache, fallback para a capa estática, sincronização com a reprodução e economia de dados/bateria. Use na fase 23.
---
# Capas animadas

## O que é
Em faixas com capa animada disponível (estilo "motion artwork"/Canvas), o player mostra o vídeo em loop no lugar da capa quadrada. Sem capa animada → capa estática normal. **Nunca** bloquear a música por causa disso.

## Provedores (interface)
```
interface AnimatedCoverProvider {
  val id:String
  suspend fun find(track:TrackKey /*title, artist, album, durationMs*/): AnimatedCover? // url, mime (mp4|hls), aspect
}
```
- Implementar **pelo menos um** provedor realmente funcional e registrá-lo em `data/animatedcover`. Pesquise e valide o que funciona hoje (registre em Decisões o que testou). Candidatos conhecidos, todos não oficiais: arte em movimento ("editorial video") das páginas públicas de álbum do Apple Music (HLS) e o Canvas do Spotify (vídeo MP4 vertical). Outros apps open source de YouTube Music (por exemplo ArchiveTune) mostram que existem esses dois e o TIDAL; estude a ideia, **não copie código** (GPL-3.0) nem tokens/chaves.
- Credenciais: nada embutido no APK; chaves via `local.properties`/GitHub Secrets com fallback "provedor indisponível". Se um provedor exigir login/cookie do usuário, deixe-o desligado por padrão com aviso claro de risco (como o Discord RPC).
- Ordem de tentativa configurável no código; timeout 6 s por provedor; falha → próximo → estático. Resultados em Room: `animated_cover(albumKey, url, provider, mime, fetchedAt, notFound)`; negativo vale 7 dias, positivo 30 dias (URLs podem expirar: revalidar em erro).
- Correspondência: só aceitar quando título/artista/álbum batem (normalizados) e, se houver, duração ±8 s. Preferir chave por álbum (várias faixas compartilham a mesma capa animada).

## Reprodução
- Segundo `ExoPlayer` **dedicado** (mudo, `volume=0`, sem foco de áudio, `REPEAT_MODE_ONE`, `PlaybackParameters` normais), com `PlayerView`/`TextureView` ou `SurfaceView` recortado: `clipToOutline` + `ViewOutlineProvider` com cantos iguais aos da capa. Escala `RESIZE_MODE_ZOOM` (vídeo vertical preenche o quadrado, corte central).
- Dependência `media3-exoplayer-hls` se usar HLS (verificar versão compatível com o Media3 do projeto). Cache pequeno (`SimpleCache` ≤ 60 MB, LRU) e resolução máx. 720p (`DefaultTrackSelector.setMaxVideoSize`).
- Ciclo: ao mudar a faixa, esperar 600 ms (debounce), buscar, preparar; **só mostrar** o vídeo quando `onRenderedFirstFrame` disparar, com crossfade 300 ms sobre a capa estática (que continua embaixo). Se falhar em qualquer ponto → mantém a capa estática, sem erro visível.
- Sincronia com a música: pausa quando a música pausa, retoma junto; pausa ao sair do player, tela apagada ou app em segundo plano; `release()` em `onDestroyView`/serviço parado. Nunca dois vídeos vivos.
- O backdrop borrado e as cores adaptativas (fase 21) continuam usando a capa **estática** (estabilidade de cor).
- Álbum/fila: opcionalmente nada; só o player em tela cheia é obrigatório.

## Configuração
- Aparência → interruptor **Capas animadas** (padrão: ligado no celular, desligado e **oculto** na TV de baixo desempenho). Descrição curta explicando uso de dados.
- Respeitar: Economia de dados do Android (`ConnectivityManager.restrictBackgroundStatus`) e Economia de bateria (`PowerManager.isPowerSaveMode`) → desligam automaticamente; rede medida (`isActiveNetworkMetered`) → não pré-carregar, só tocar se o usuário optar? Para simplificar: em rede medida, não buscar vídeo.
- TV: `ActivityManager.isLowRamDevice` ou flavor tv 32 bits → oculto/desligado.

## Robustez e legal
Sem crash; sem log de URLs com tokens; aviso no PROGRESS/README de que provedores não são oficiais e podem parar. Não baixar vídeo para armazenamento permanente.

## Verificação
Faixa com capa animada (loop sem corte, sem som, sincronizado), faixa sem (estática), rede ruim, pausa/retoma, trocar de faixa rápido (sem vazamento de player), girar tela, economia de bateria, 3 temas e idiomas, uso de memória estável após 20 trocas.
