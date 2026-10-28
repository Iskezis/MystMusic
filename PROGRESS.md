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
| 40758692289 | 9c0c395 | verde | Ajustes finais de resolução de stream e correspondência do Spotify | `apks/app.apk`, `apks/app-tv.apk` |

## Decisões
- Player: `ResolvingDataSource` resolve `myst://track/<id>` na hora de abrir. O InnerTube tenta `ANDROID_VR`, `IOS`, `ANDROID_MUSIC`, `ANDROID` e por último `WEB_REMIX`; os clientes de app usam `www.youtube.com/youtubei/v1`, o web usa `music.youtube.com/youtubei/v1`.
- Formatos de áudio priorizam AAC (itag 140/141/139) e depois Opus (251/250/249), para funcionar também em TVs 32 bits sem decodificador Opus.
- Em erro de rede/HTTP do player, o `PlaybackService` invalida o cache da URL e tenta uma vez de novo. O botão "Tentar novamente" também limpa o estado de erro.
- Capas: o parser escolhe a maior miniatura disponível, eleva URLs do YouTube para `maxresdefault`/1080 e a UI aplica essa melhoria também a faixas antigas salvas no banco. Capas de playlist escolhidas pelo usuário são salvas em 1024 px (JPEG 92).
- Spotify: a Web API é tentada primeiro; sem chaves, o embed é lido priorizando a entidade cujo `uri`/`id` corresponde ao link colado. A ordem original é preservada por índice, mesmo com mapeamento paralelo. O mapeamento exige título, artista e duração (diferença máxima de 8 s), rejeitando karaokê, cover, remix, live, sped up etc. quando não fazem parte do título original.
- A capa importada do Spotify usa a maior imagem disponível e é gravada em até 1024 px.

## Problemas em aberto
- Fase 9: protocolo de pareamento criado em `data/pairing/PairingProtocol.kt`, mas servidor HTTP/NSD, tela de pareamento e adaptação completa do flavor TV ainda precisam ser integrados.
- Fase 10: revisão final da checklist e teste em aparelho físico (o endpoint de áudio foi validado externamente com resposta HTTP 206 `audio/mp4`).
