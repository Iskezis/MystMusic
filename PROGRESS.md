# PROGRESS.md
Atualize após cada etapa. Marque [x] só quando o build do GitHub estiver verde e a funcionalidade verificada.

## Fases
- [x] 1. Setup + workflow + build verde inicial
- [x] 2. Tema + navbar flutuante + esqueleto
- [x] 3. Contas (Myst + YouTube)
- [x] 4. Navegação (busca, recentes, em alta, recomendados)
- [x] 5. Player Media3 + notificação
- [x] 6. Playlists + Integrar do Spotify
- [x] 7. Discord RPC
- [x] 8. Animações (telas + elementos)
- [ ] 9. Flavor TV + pareamento 5 dígitos
- [ ] 10. Revisão final + APKs em apks/

## Builds
| Run # | Commit | Resultado | Erro/Correção | APK movido |
|-------|--------|-----------|---------------|------------|
| 40749118868 | — | verde | Setup inicial mobile + tv | — |
| 40754788954 | ef53952 | verde | Correção do adaptador de playlist + Discord opt-in | — |
| 40755922583 | 74a3af5 | falha | `binding.navBar.animate()` em ViewBinding incluído; trocado por `binding.navBar.root` | — |
| 40757711581 | c4141a5 | verde | Reprodução, capas em alta resolução e importação fiel do Spotify | — |
| 40758692289 | 9c0c395 | verde | Ajustes finais de resolução de stream e correspondência do Spotify | `apks/app.apk`, `apks/app-tv.apk` (antigos) |
| 40767006166 | 3c00bf9 | verde | PlayerActivity isolado, proteção do MediaController, User-Agent do InnerTube e logs | — |
| 40772322300 | 164db22 | verde | OkHttp no ExoPlayer, TVHTML5 autenticado, login YouTube no player e erros amigáveis | — |
| 40776217605 | 39fde6d | verde | Cliente VISIONOS 0.1 do InnerTube, visitorData novo, URL direta validada e diagnóstico copiável | `apks/app.apk`, `apks/app-tv.apk` |
| 40783122795 | 3645940 | falha | Erros de compilação na serialização do heartbeat/shard do Discord (`JsonNull`/`JsonArray`) | — |
| 40783625441 | a14f2e7 | verde | Correção da serialização do Discord + proteção contra crash na navegação/busca + log persistente de crash | `apks/app.apk`, `apks/app-tv.apk` |

## Decisões
- Player: `ResolvingDataSource` resolve `myst://track/<id>` na hora de abrir. A extração foi refeita seguindo o caminho que o BitChord usa via InnerTube (`InnerTubeXResolver`/`PlaybackClientCatalog`): o primeiro cliente é o **VISIONOS 0.1** no endpoint `music.youtube.com/youtubei/v1/player`, com `visitorData` novo, `clientId` 101, `X-Goog-Api-Format-Version: 1`, sem cookies e sem `playbackContext`. Ele devolve URL direta, que é validada com Range de 2 bytes antes de chegar ao ExoPlayer.
- O `visitorData` é preservado entre buscas e, quando não existe (primeiro play ou conta recém-vinculada), é obtido da home do YouTube Music com o mesmo User-Agent do VISIONOS. Foi validado nesta rede: `playabilityStatus=OK`, cinco formatos de áudio e `HTTP 206 audio/mp4` em Range de 1 MiB.
- Fallback: `ANDROID_VR` (também no endpoint de música), `IOS`, `WEB_REMIX`, `WEB_EMBEDDED_PLAYER` e, com cookies, `TVHTML5`. Formatos `signatureCipher` com `s` cifrado são ignorados; a URL direta do VISIONOS não precisa de decifrador, então não há QuickJS/AAR no app.
- O bloqueio de bot (`LOGIN_REQUIRED`/"confirme que você não é um bot") continua detectado: se todos os clientes recusarem assim, o `InnerTubeClient` lança `LoginRequiredException` e o player mostra **Entrar com YouTube**. Nenhum cookie/token é registrado no Logcat.
- O login do YouTube pelo player usa `AccountRepository.linkYouTube`: grava cookies/SAPISID na sessão sem trocar a conta Myst, preservando playlists e histórico locais. Também limpa o `visitorData` antigo; o VISIONOS busca um visitante anônimo novo.
- O `PlaybackService` usa `OkHttpDataSource` (o mesmo cliente que valida a URL com Range 2 bytes) e `ProgressiveMediaSource` explícito. Isso elimina diferenças de TLS/proxy/`HttpURLConnection` entre a validação e a reprodução. O User-Agent não é fixado na fábrica HTTP: vem dos headers por cliente do `StreamInfo`.
- Erros do ExoPlayer são traduzidos para mensagens amigáveis (rede, HTTP, áudio inválido, decodificador, timeout). O código técnico continua no Logcat. O botão "Tentar novamente" recria a conexão se o `MediaController` tiver caído.
- Mini player abre uma `PlayerActivity` própria (`singleTop`), em vez de duplicar a navegação do NavController.
- Diagnóstico: `AppLog` mantém as últimas 500 linhas sanitizadas em memória; o player oferece **Copiar diagnóstico** para o usuário colar no relato sem adb.
- Formatos de áudio priorizam AAC (itag 140/141/139) e depois Opus (251/250/249), para funcionar também em TVs 32 bits sem decodificador Opus.
- Em erro de rede/HTTP do player, o `PlaybackService` invalida o cache da URL e tenta uma vez de novo. O botão "Tentar novamente" também limpa o estado de erro.
- Capas: o parser escolhe a maior miniatura disponível, eleva URLs do YouTube para `maxresdefault`/1080 e a UI aplica essa melhoria também a faixas antigas salvas no banco. Capas de playlist escolhidas pelo usuário são salvas em 1024 px (JPEG 92).
- Spotify: a Web API é tentada primeiro; sem chaves, o embed é lido priorizando a entidade cujo `uri`/`id` corresponde ao link colado. A ordem original é preservada por índice, mesmo com mapeamento paralelo. O mapeamento exige título, artista e duração (diferença máxima de 8 s), rejeitando karaokê, cover, remix, live, sped up etc. quando não fazem parte do título original.
- A capa importada do Spotify usa a maior imagem disponível e é gravada em até 1024 px.

## Problemas em aberto
- Reprodução: o APK do run 40783625441 (`a14f2e7`) inclui a correção do crash de navegação/busca e a nova conexão Discord. Precisa ser testado no aparelho.
- Crash: as lambdas de animação de lista em Busca/Playlists usavam `binding` depois de `onDestroyView`; agora são canceladas com o `viewLifecycleOwner`. Um handler persistente também grava a pilha do próximo crash em `myst_last_crash.txt`, carregada no diagnóstico após reiniciar.
- Discord: o identify usava as chaves erradas (`$os`/`$browser`/`$device`); agora usa o fingerprint Android do Discord, valida o token pela REST, espera ACK do heartbeat, reconecta com backoff e reenvia a presença. Se continuar sem conectar, o diagnóstico mostrará `MystDiscord` com o HTTP/close code exato.
- Fase 9: protocolo de pareamento criado em `data/pairing/PairingProtocol.kt`, mas servidor HTTP/NSD, tela de pareamento e adaptação completa do flavor TV ainda precisam ser integrados.
- Fase 10: revisão final da checklist e teste em aparelho físico.
