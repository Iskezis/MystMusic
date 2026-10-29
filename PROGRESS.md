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
- [x] 9. Flavor TV + pareamento 5 dígitos
- [x] 10. Revisão final + APKs em apks/

## Release
- **0.1.0 Alpha** (`versionCode 9`, `versionName "0.1.0 Alpha"`): primeira alpha pública para celular e Android TV. A numeração anterior (1.0.x) era de desenvolvimento; o `versionCode` continua crescendo (9) para permitir atualização sobre a v1.0.7.
- APKs: `apks/app.apk` (mobile) e `apks/app-tv.apk` (TV, armeabi-v7a), assinados com a chave de debug do build.
- Notas: `CHANGELOG.md` e `README.md` (instalação e limitações).
- Tag prevista: `v0.1.0-alpha`.

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
| 40784867928 | 49fd60c | falha | Exportação do pareamento bloqueava a thread principal (NetworkOnMainThread) | — |
| 40785219360 | 029b71a | verde | Exportação do pareamento movida para `withContext(Dispatchers.IO)` | — |
| 40788161795 | f4013c1 | verde | Navegação durante reprodução, Discord com identify válido e pareamento TV estabilizado | — |
| 40790472742 | 9146214 | verde | Logs persistentes em arquivo, tela de diagnóstico, cópia/compartilhamento e stack de crash no arquivo (v1.0.2) | `apks/app.apk`, `apks/app-tv.apk` |
| 40791173336 | 0b30817 | verde | Commit dos APKs do run 40790472742 + PROGRESS | — |
| 40792909555 | 6784eb2 | verde | Coil sem bitmaps de hardware para as transições de fragmento não quebrarem no snapshot (v1.0.3) | `apks/app.apk`, `apks/app-tv.apk` |
| 40795596184 | ac522e9 | verde | Token do Discord sem aspas, leitura por iframe, validação REST e migração de sessão antiga (v1.0.4) | `apks/app.apk`, `apks/app-tv.apk` |
| 40899680539 | 208d4b3 | verde | Capa do Discord via media proxy (`mp:`) e card somente com ação/música/artista (v1.0.6) | `apks/app.apk`, `apks/app-tv.apk` |
| 40903108724 | 80d2f3b | verde | Capa pelo aplicativo público do BitChord, sem criação de app (captcha) e barra de progresso restaurada (v1.0.7) | `apks/app.apk`, `apks/app-tv.apk` |

## Decisões
- Player: `ResolvingDataSource` resolve `myst://track/<id>` na hora de abrir. A extração foi refeita seguindo o caminho que o BitChord usa via InnerTube (`InnerTubeXResolver`/`PlaybackClientCatalog`): o primeiro cliente é o **VISIONOS 0.1** no endpoint `music.youtube.com/youtubei/v1/player`, com `visitorData` novo, `clientId` 101, `X-Goog-Api-Format-Version: 1`, sem cookies e sem `playbackContext`. Ele devolve URL direta, que é validada com Range de 2 bytes antes de chegar ao ExoPlayer.
- O `visitorData` é preservado entre buscas e, quando não existe (primeiro play ou conta recém-vinculada), é obtido da home do YouTube Music com o mesmo User-Agent do VISIONOS. Foi validado nesta rede: `playabilityStatus=OK`, cinco formatos de áudio e `HTTP 206 audio/mp4` em Range de 1 MiB.
- Fallback: `ANDROID_VR` (também no endpoint de música), `IOS`, `WEB_REMIX`, `WEB_EMBEDDED_PLAYER` e, com cookies, `TVHTML5`. Formatos `signatureCipher` com `s` cifrado são ignorados; a URL direta do VISIONOS não precisa de decifrador, então não há QuickJS/AAR no app.
- O bloqueio de bot (`LOGIN_REQUIRED`/"confirme que você não é um bot") continua detectado: se todos os clientes recusarem assim, o `InnerTubeClient` lança `LoginRequiredException` e o player mostra **Entrar com YouTube**. Nenhum cookie/token é registrado no Logcat.
- O login do YouTube pelo player usa `AccountRepository.linkYouTube`: grava cookies/SAPISID na sessão sem trocar a conta Myst, preservando playlists e histórico locais. Também limpa o `visitorData` antigo; o VISIONOS busca um visitante anônimo novo.
- O `PlaybackService` usa `OkHttpDataSource` (o mesmo cliente que valida a URL com Range 2 bytes) e `ProgressiveMediaSource` explícito. Isso elimina diferenças de TLS/proxy/`HttpURLConnection` entre a validação e a reprodução. O User-Agent não é fixado na fábrica HTTP: vem dos headers por cliente do `StreamInfo`.
- Erros do ExoPlayer são traduzidos para mensagens amigáveis (rede, HTTP, áudio inválido, decodificador, timeout). O código técnico continua no Logcat. O botão "Tentar novamente" recria a conexão se o `MediaController` tiver caído.
- Mini player abre uma `PlayerActivity` própria (`singleTop`), em vez de duplicar a navegação do NavController.
- Diagnóstico: `AppLog` mantém as últimas 500 linhas sanitizadas em memória e grava o histórico completo em `files/logs/mystmusic.log` (rotação em 1 MB). A tela **Configurações → Logs e diagnóstico** mostra, atualiza, copia, limpa e compartilha o arquivo; o compartilhamento usa um `FileProvider` não exportado e concede leitura temporária apenas à URI enviada. Nada é exposto publicamente. O Logcat Reader externo continua sujeito ao `READ_LOGS` do Android, concedido por ADB/root; o app não pode conceder essa permissão a outro aplicativo.
- Pareamento: a TV exige o código de 5 dígitos quando não há sessão pareada; o celular exporta conta Myst/YouTube, cookies e playlists por HTTP local com AES-GCM derivado do código. O NSD compara o tipo de serviço normalizado e o celular pode reiniciar o servidor local.
- TV: como digitar cookies no controle remoto é inviável, o login chega pelo pareamento. O logout na TV volta ao `TvPairingActivity`; no celular, ao `AuthActivity`.
- Configurações: além de **Deslogar** e do item opt-in **Discord**, há **Conectar à TV** (necessário para o pareamento) e **Logs e diagnóstico** (solicitado para relatar bugs). Nenhuma outra opção foi adicionada.
- Imagens: `MystApp` implementa `ImageLoaderFactory` com `allowHardware(false)`. As transições de fragmento (`MaterialSharedAxis`) tiram um snapshot da tela em um `Canvas` de software; uma capa carregada pelo Coil como bitmap de hardware lançava `IllegalArgumentException: Software rendering doesn't support hardware bitmaps` ao sair da Busca com resultados. O crossfade continua; só a otimização de bitmap de hardware foi desativada.
- Discord: o `localStorage.token` do web client é uma string JSON **com aspas**. O `evaluateJavascript` devolvia o valor ainda com as aspas (74 caracteres para um token de 72) e esse valor ia inteiro no header `Authorization`, por isso a REST respondia `HTTP 401` mesmo com a conta logada. O login agora lê o token em um iframe same-origin novo (o Discord remove o acesso ao localStorage da janela principal depois do boot), remove as aspas e valida em `/api/v9/users/@me` com o fingerprint Android. O `DiscordPresenceManager` também limpa aspas de tokens já salvos, migrando a sessão da v1.0.3 sem exigir novo login. O log mostra `token=72` e o HTTP real, nunca o token.
- Discord/capa: mandar a URL HTTPS crua em `large_image` fazia o card ficar **sem imagem** (não era o ícone padrão). O gateway só resolve assets do aplicativo ou caminhos `mp:` emitidos por `POST /applications/{id}/external-assets`. A v1.0.7 registra a miniatura nesse endpoint e envia `mp:...`. A tentativa da v1.0.6 de criar um aplicativo “MystMusic” com o próprio token falhou com `HTTP 400 captcha-required`; por isso o app usa por padrão o aplicativo público do BitChord (`1543679166967193620`) apenas como namespace do media proxy. O `application_id` **não** vai na atividade, então o card continua “Ouvindo MystMusic”. Um `DISCORD_APP_ID` próprio em `local.properties`/GitHub Secrets tem prioridade; o token nunca é registrado.
- Discord/card: a atividade é `type=2` (Listening) com `name=MystMusic`, `details=música`, `state=artista`, capa em `assets.large_image` e `timestamps.start/end` para a barra de progresso. Não enviamos `status_display_type` nem `large_text` (linhas extras). Quando a duração chega depois do primeiro estado “playing”, a presença é reenviada para o Discord conseguir desenhar o progresso. Pausar ou trocar de faixa remove a presença imediatamente.
- Formatos de áudio priorizam AAC (itag 140/141/139) e depois Opus (251/250/249), para funcionar também em TVs 32 bits sem decodificador Opus.
- Em erro de rede/HTTP do player, o `PlaybackService` invalida o cache da URL e tenta uma vez de novo. O botão "Tentar novamente" também limpa o estado de erro.
- Capas: o parser escolhe a maior miniatura disponível, eleva URLs do YouTube para `maxresdefault`/1080 e a UI aplica essa melhoria também a faixas antigas salvas no banco. Capas de playlist escolhidas pelo usuário são salvas em 1024 px (JPEG 92).
- Spotify: a Web API é tentada primeiro; sem chaves, o embed é lido priorizando a entidade cujo `uri`/`id` corresponde ao link colado. A ordem original é preservada por índice, mesmo com mapeamento paralelo. O mapeamento exige título, artista e duração (diferença máxima de 8 s), rejeitando karaokê, cover, remix, live, sped up etc. quando não fazem parte do título original.
- A capa importada do Spotify usa a maior imagem disponível e é gravada em até 1024 px.

## Problemas em aberto
- Validação funcional em aparelho físico confirmada pelo usuário em 29/10/2026: login YouTube, reprodução, navegação, Discord RPC com capa/progresso e pareamento TV. Nenhum bloqueador conhecido para a 0.1.0 Alpha.
- Discord: se o aplicativo público de capas for recusado, o log mostra `Discord recusou o aplicativo de capas: HTTP ...`; nesse caso, criar um aplicativo no Discord Developer Portal e definir `DISCORD_APP_ID` em `local.properties`/GitHub Secrets. Sem isso, o app continua funcionando e a presença aparece sem capa.
- Crash corrigido: o log de 28/10 mostrou `IllegalArgumentException: Software rendering doesn't support hardware bitmaps` em `TransitionUtils.createViewBitmap` ao sair da Busca com capas carregadas. A causa era o bitmap de hardware padrão do Coil; `MystApp.newImageLoader()` usa `allowHardware(false)` e o build 40795596184 inclui a correção.
- Logcat Reader: qualquer leitor externo precisa receber `android.permission.READ_LOGS` por ADB/root; sem isso o Android só mostra ao leitor os próprios logs. Exemplo (troque pelo pacote do leitor): `adb shell pm grant com.dp.logcatapp android.permission.READ_LOGS`. Os logs internos e o botão Compartilhar funcionam sem ADB.
- Crash: as lambdas de animação de lista em Busca/Playlists usavam `binding` depois de `onDestroyView`; agora são canceladas com o `viewLifecycleOwner`. O handler persistente grava a pilha em `myst_last_crash.txt`, carrega no diagnóstico após reiniciar e agora também grava a pilha completa no arquivo de log de forma síncrona.
- Discord: a causa do 401 era o token salvo com as aspas do `localStorage` (74 caracteres em vez de 72). A v1.0.4 remove as aspas no login, sanitiza tokens antigos no `DiscordPresenceManager` e valida pela REST v9. Se ainda falhar, a tela de logs mostra `MystDiscord` com o HTTP/close code exato; o token nunca aparece.
- Áudio: o APK não contém bibliotecas nativas próprias; a compatibilidade de codecs AAC/Opus depende do decodificador do aparelho/TV, por isso a ordem prioriza AAC.
- Fase 10: checklist estrutural concluída (pacote/nome, flavors, telas com estados, APKs) e validação funcional confirmada em aparelho físico; versão renomeada para 0.1.0 Alpha.
