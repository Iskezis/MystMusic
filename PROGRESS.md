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

## Fases 0.2.0
Marque cada subitem só com build verde + verificação. Versão durante o desenvolvimento: `0.2.0-dev.N` (versionCode crescente a partir de 10); a final será `0.2.0 Alpha`.

- [x] 11. Tema do sistema + Aparência
  - [x] Segue o tema do sistema (claro/escuro) em todas as telas, celular e TV
  - [x] Configurações → Aparência: Escuro / Claro / Sistema (padrão Sistema), persistido
  - [x] Interruptor "Preto AMOLED" só aparece quando o tema efetivo é escuro; fundo #000000
  - [x] Sem cores fixas nos layouts (tudo por atributos de tema), sem piscar ao trocar
- [x] 12. Login obrigatório duplo + foto de perfil
  - [x] Navegação só abre com YouTube **e** Myst logados
  - [x] Criar conta Myst com foto de perfil (galeria + recorte circular, 512 px)
  - [x] Avatar/nome da Myst no cabeçalho e nas Configurações (nunca o do YouTube)
  - [x] Migração de sessões da 0.1.0 com só uma conta
  - [x] Pareamento da TV leva as duas contas + foto
- [x] 13. Dados do YouTube Music da conta
  - [x] Playlists do YTM na aba Playlists (seção "YouTube Music")
  - [x] Histórico do YTM em Recentes (mesclado, sem duplicatas)
  - [x] Recomendados/Em Alta baseados na conta do YTM
  - [x] Cache + pull-to-refresh + estados de erro
- [x] 14. Conta Myst: dados persistentes + Estatísticas
  - [x] Dados isolados por conta Myst (recentes, gostos, playlists, histórico, preferências)
  - [x] Migrações Room não destrutivas; schema 5 conferido coluna a coluna contra a migração (teste de atualização em aparelho pendente)
  - [x] Botão no canto superior direito da Navegação → tela Estatísticas
  - [x] Estatísticas armazenadas na conta Myst (eventos de reprodução)
- [x] Correções da fase 14 (0.2.0-dev.11)
  - [x] Sincronização bidirecional em tempo real; histórico local e do YouTube Music em Recentes
  - [x] Playlists, faixas e histórico do YouTube viajam no pareamento e nos syncs seguintes
  - [x] AMOLED efetivo e conexão do Discord reaplicados na TV; mudanças disparam sync imediato
  - [x] Botão de desconexão na TV, portas fixas com fallback e remoção local quando a TV não responde
  - [x] Estatísticas contam depois dos primeiros 10 s, inclusive com pulo, usando o tempo exato
- [ ] 15. Busca: artistas, álbuns e playlists
  - [ ] Chips Tudo / Músicas / Artistas / Álbuns / Playlists
  - [ ] Perfil do artista, tela do álbum, tela da playlist
- [ ] 16. Downloads offline
  - [ ] Toque longo na música → Baixar
  - [ ] Baixar tudo em álbum/playlist
  - [ ] Baixar todas as músicas do artista
  - [ ] Reprodução offline, gerenciar/remover, espaço usado
- [ ] 17. Novo visual completo
  - [ ] Referências estudadas e resumidas em Decisões (sem cópia de código/asset)
  - [ ] Navegação, busca, perfil/álbum/playlist, player, playlists, configurações, estatísticas redesenhados
  - [ ] Funciona nos temas claro/escuro/AMOLED e na TV
- [ ] 18. Atualização pelo GitHub
  - [ ] Workflow de release publica `app.apk` e `app-tv.apk` na Release
  - [ ] Assinatura estável (keystore em GitHub Secrets)
  - [ ] Pop-up de atualização ao entrar, com "Atualizar" e "Agora não"
  - [ ] Download com progresso + instalação (permissão de fontes desconhecidas tratada)
- [ ] 19. Revisão final da 0.2.0 (checklist do AGENTS.md, APKs finais, CHANGELOG, tag `v0.2.0-alpha`)

## Riscos conhecidos da 0.2.0
- Atualização por cima só instala se a assinatura for a mesma. As APKs até a 0.2.0-dev.3 usam a chave de debug do runner; a keystore fixa entrou no 0.2.0-dev.4. Quem tem uma APK anterior ao dev.4 precisa **desinstalar e reinstalar uma vez**; isso apaga os dados locais, então salve um `Mystaccount.json` antes. Do dev.4 em diante as atualizações instalam por cima.
- Downloads de áudio do YouTube Music podem violar os Termos do YouTube; uso pessoal, arquivos privados do app, aviso na primeira vez.
- API de atualização do GitHub só funciona sem token se o repositório for público.

## Release
- **0.2.0-dev.10** (`versionCode 19`, `versionName "0.2.0-dev.10"`): conta Myst persistente com dados isolados por conta e tela Estatísticas (tempo ouvido, reproduções, tops de músicas/artistas/álbuns, gráficos de 24 h e por dia, sequência atual/recorde), eventos em `play_events`, migração Room 4→5 não destrutiva e schema exportado; run `40987101834`. APKs em `apks/app.apk` e `apks/app-tv.apk`.
- **0.2.0-dev.9** (`versionCode 18`, `versionName "0.2.0-dev.9"`): a TV recebe o tema e o AMOLED do celular no pareamento e usa essa aparência temporariamente até desconectar ou deslogar; run `40978889454`. APKs em `apks/app.apk` e `apks/app-tv.apk`.
- **0.2.0-dev.8** (`versionCode 17`, `versionName "0.2.0-dev.8"`): TV como terminal temporário (Room em memória, sem `Mystaccount.json`, limpeza ao desconectar/deslogar) e backup automático do `Mystaccount.json` no celular em `/sdcard/MystMusic` (fallback `Download/MystMusic` sem a permissão especial); run `40973178702`. APKs em `apks/app.apk` e `apks/app-tv.apk`.
- **0.2.0-dev.7** (`versionCode 16`, `versionName "0.2.0-dev.7"`): o `Mystaccount.json` passou a levar playlists do YouTube Music, histórico do YouTube e as preferências de aparência, e a restauração religa esses dados; run `40966779743`.
- **0.2.0-dev.6** (`versionCode 15`, `versionName "0.2.0-dev.6"`): canal persistente celular↔TV, desconexão com confirmação, sincronização bidirecional e lápides de playlists; run `40964571526`. Os APKs do dev.6 foram substituídos pelos do dev.8.
- **0.2.0-dev.5** (`versionCode 14`, `versionName "0.2.0-dev.5"`): conta Myst portátil (`Mystaccount.json`) gerada no cadastro, restauração pelo arquivo com mesclagem segura, atualização automática da cópia interna/externa e seção em Configurações; run `40958020423`. APKs em `apks/app.apk` e `apks/app-tv.apk` (restaurados a partir do run `40959796937`, assinatura `6164b272…a5b9`).
- **0.2.0-dev.4** (`versionCode 13`, `versionName "0.2.0-dev.4"`): assinatura estável com a keystore dos GitHub Secrets e correção de crash na navegação/persistência da conta Myst; run `40948817158`. APKs não foram commitados nesta etapa (a pasta `apks/` estava removida); o dev.5 já usa a mesma assinatura.
- **0.2.0-dev.3** (`versionCode 12`, `versionName "0.2.0-dev.3"`): build de desenvolvimento da fase 13 (biblioteca, histórico e recomendações do YouTube Music, cache Room com atualização por gesto e aviso de sessão expirada), run `40944696111`. APKs em `apks/app.apk` e `apks/app-tv.apk`.
- **0.2.0-dev.2** (`versionCode 11`, `versionName "0.2.0-dev.2"`): build de desenvolvimento da fase 12 (login duplo obrigatório, foto de perfil da conta Myst, migração da sessão 0.1.0 e pareamento TV com as duas contas + avatar), run `40939083129`. APKs em `apks/app.apk` e `apks/app-tv.apk`.
- **0.2.0-dev.1** (`versionCode 10`, `versionName "0.2.0-dev.1"`): build de desenvolvimento da fase 11 (tema Sistema/Claro/Escuro + Preto AMOLED + tela Aparência), run `40930983204`. APKs em `apks/app.apk` e `apks/app-tv.apk`.
- **0.1.0 Alpha** (`versionCode 9`, `versionName "0.1.0 Alpha"`): primeira alpha pública para celular e Android TV. A numeração anterior (1.0.x) era de desenvolvimento; o `versionCode` continua crescendo (9) para permitir atualização sobre a v1.0.7.
- APKs: `apks/app.apk` (mobile) e `apks/app-tv.apk` (TV, armeabi-v7a), assinados com a keystore estável dos GitHub Secrets a partir do dev.4 (antes disso, chave de debug do runner).
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
| 40905793966 | 918715c | verde | Preparação da 0.1.0 Alpha: versionCode 9, README/CHANGELOG e APKs da release | `apks/app.apk`, `apks/app-tv.apk` |
| 40930501819 | 33e6d20 | falha | `this` dentro de `lifecycleScope.launch` era o `CoroutineScope`, não a Activity, em `MystTheme.color(...)` no pareamento | — |
| 40930983204 | a893a44 | verde | Fase 11: tema Sistema/Claro/Escuro, Preto AMOLED, tela Aparência e tokens de tema em todas as telas; correção do contexto de Activity no pareamento (0.2.0-dev.1) | `apks/app.apk`, `apks/app-tv.apk` |
| 40938511745 | bf4f36d | falha | Fase 12: `completeMystAuth` chamava DAO suspenso fora de coroutine e a limpeza do cache do Coil usava `String` em vez de `MemoryCache.Key` | — |
| 40939083129 | c38599c | verde | Fase 12: login duplo obrigatório, foto de perfil com recorte 512 px, migração 0.1.0 e pareamento TV com as duas contas + avatar (0.2.0-dev.2) | `apks/app.apk`, `apks/app-tv.apk` |
| 40944696111 | 2436592 | verde | Fase 13: playlists/histórico/recomendações do YouTube Music, cache Room (TTL 15 min), pull-to-refresh, cópia para Myst e banner de sessão expirada (0.2.0-dev.3) | `apks/app.apk`, `apks/app-tv.apk` |
| 40948817158 | b51cf02 | verde | Assinatura estável (keystore dos Secrets) e correção de crash na navegação/persistência da conta Myst (0.2.0-dev.4) | — (pasta `apks/` removida) |
| 40957679150 | fd3a0c3 | falha | `existingByName` era `Map` imutável; trocado por `toMutableMap()` ao mesclar playlists do `Mystaccount.json` | — |
| 40958020423 | ac4716b | verde | Conta Myst portátil: geração/restauração do `Mystaccount.json`, sincronização automática, Configurações e proteção das sessões (0.2.0-dev.5) | `apks/app.apk`, `apks/app-tv.apk` |
| 40959796937 | 0f03047 | verde | Build após o commit "Delete apks directory"; APKs release do dev.5 baixados e restaurados em `apks/` | `apks/app.apk`, `apks/app-tv.apk` |
| 40964571526 | 65331d9 | verde | Canal persistente celular↔TV, desconexão e sincronização bidirecional (0.2.0-dev.6) | — |
| 40966779743 | 55b18d2 | verde | `Mystaccount.json` com playlists do YouTube, histórico e aparência (0.2.0-dev.7) | — |
| 40973178702 | 1c6fe94 | verde | TV temporária com Room em memória e backup automático do `Mystaccount.json` no celular (0.2.0-dev.8) | `apks/app.apk`, `apks/app-tv.apk` |
| 40978889454 | d02bc70 | verde | TV adota tema/AMOLED do celular no pareamento, com aparência temporária apagada na desconexão/logout (0.2.0-dev.9) | `apks/app.apk`, `apks/app-tv.apk` |
| 40987101834 | 2ed8ca2 | verde | Fase 14: estatísticas da conta Myst, eventos `play_events`, dados isolados por conta, migração Room 4→5 e schema exportado (0.2.0-dev.10) | `apks/app.apk`, `apks/app-tv.apk` |
| 40988066201 | 14bc43b | verde | Publicação do schema Room 5, dos APKs do dev.10 e das notas da fase 14 no repositório | `apks/app.apk`, `apks/app-tv.apk` |
| 40988591943 | 3d39cfa | verde | APKs do dev.10 e registro do build no repositório; build final da fase 14 verde | — |

## Decisões
- YouTube Music (fase 13): a biblioteca da conta é **somente leitura**. O cache fica em tabelas próprias do Room (`yt_playlists`, `yt_playlist_tracks`, `yt_history`, `yt_sync_state`), com validade de 15 minutos e estratégia *stale-while-revalidate*: a tela mostra o cache na hora e atualiza em segundo plano. O botão/gesto de atualizar força a rede. Nenhuma escrita é enviada ao YouTube.
- Cache do YouTube: a migração Room 2→3 apenas cria as tabelas novas; `fallbackToDestructiveMigration` continua proibido. Se a sessão expirar (HTTP 401/403 ou cookies ausentes), a UI mostra o banner **"Sessão do YouTube expirou"** com "Entrar de novo"; o app não desloga nem apaga dados locais. O cache antigo continua visível.
- Histórico mesclado: o YouTube Music informa apenas o dia, não o horário. As faixas recebem timestamps relativos espaçados em 60 s para preservar a ordem, e a mesclagem com o histórico local usa `videoId` como chave (sem duplicatas); a metadata local tem prioridade.
- Recomendados/Em Alta (fase 13): Recomendados usa a home personalizada autenticada (`FEmusic_home`) e cai para o algoritmo local quando ela está vazia ou falha. Em Alta combina as paradas públicas com a afinidade de artistas do histórico local e do histórico do YouTube Music.
- Playlists do YouTube são abertas em uma tela de detalhe **somente leitura** (sem arrastar, renomear, remover ou trocar capa). O botão "Copiar para Myst" cria uma playlist local editável com as faixas e a capa, sem alterar a playlist original.
- A aba Playlists é uma grade única com cabeçalhos de largura total: "Minhas playlists (Myst)" e "YouTube Music". Cada seção tem carregamento, vazio e erro próprios; a falha do YouTube não esconde as playlists locais.
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
- Tema (fase 11): as cores de UI vivem em atributos `myst*` (`attrs.xml`) definidos em `values/themes.xml` (claro) e `values-night/themes.xml` (escuro). Nenhum layout usa cor fixa para fundo/texto; seletores de cor (`nav_text_color`, `appearance_card_stroke` etc.) também usam `?attr/...`.
- Aparência: `AppearancePreferences` guarda `theme_mode` e `amoled` em `SharedPreferences` no nível do aparelho (não da conta Myst). `MystApp.onCreate` aplica `AppCompatDelegate.setDefaultNightMode` de forma síncrona, antes de qualquer Activity, para não piscar o tema errado.
- AMOLED: `BaseActivity` aplica `ThemeOverlay.MystMusic.Amoled` antes de `super.onCreate`/`setContentView`; o overlay só é ativo quando o tema efetivo é escuro e a flag está ligada. Ao alternar o switch, a Activity é recriada (mesmo destino), sem reiniciar o processo. A flag é preservada ao voltar para o tema claro, mas fica inativa.
- Barras do sistema: `MystTheme.applySystemBars` centraliza ícones claros/escuros e é chamado no `BaseActivity` e novamente depois de `setDecorFitsSystemWindows` nas telas que usam edge-to-edge.
- A tela Aparência usa cards + `RadioGroup` com seleção controlada em código (os cards são os alvos de toque; os radios são apenas indicadores). A linha AMOLED entra/sai com `AutoTransition` (fade + altura). O estado é relido em `refresh()` porque o ViewModel sobrevive à recriação por mudança de configuração.
- Miniaturas claro/escuro da tela Aparência usam cores fixas de propósito: elas representam os temas, não superfícies da UI atual.
- Login duplo (fase 12): `SessionManager` passou a guardar a identidade Myst e a identidade YouTube em chaves separadas. `isLoggedIn` exige as duas, e a navegação principal só abre nesse estado; um conflito de vínculo também bloqueia a entrada. O `MainActivity` e o `AuthActivity` usam a mesma regra, então não há caminho que abra a navegação com só uma conta.
- Migração 0.1.0: a sessão antiga de uma conta é lida uma única vez no construtor do `SessionManager`. Se era Myst, o id vira a conta Myst dona dos dados; se era YouTube, o id antigo fica em `legacy_data_user_id` e a tela de login oferece **Trazer meus dados**. A transferência reatribui playlists, histórico e afinidade em uma transação Room; **Agora não** limpa só a oferta, sem apagar nada.
- Foto de perfil: o seletor entrega a URI ao `AvatarCropActivity`, o `PhotoLoader` decodifica com downscale e corrige a orientação EXIF (androidx EXIF, pois `android.media.ExifInterface` só existe a partir da API 24), o `AvatarCropView` recorta 1:1 com máscara circular e o resultado é gravado em `filesDir/avatars/<userId>.jpg` (JPEG 512×512). O caminho fica no Room, nunca a URI do provedor, que pode expirar.
- Perfil: cabeçalho da Navegação e Configurações leem sempre o nome/avatar da conta Myst. O YouTube aparece apenas como informação secundária ("YouTube conectado · nome"), nunca como identidade principal. Na TV a troca de foto fica desabilitada; a imagem chega pelo pareamento.
- Conflito de YouTube: se a conta Myst já está vinculada a outra conta e o usuário entra com uma diferente, a sessão anterior é restaurada e a nova fica pendente. O diálogo oferece **Trocar vínculo** (confirma a nova) ou **Usar a vinculada** (mantém a antiga; se a ativa for outra, desconecta para o usuário entrar de novo). No player, o login é explícito e `linkYouTube` troca o vínculo automaticamente para não deixar a reprodução em um beco sem saída.
- Room v2: a migração 1→2 apenas adiciona `avatarPath`, `youtubeAccountId` e `youtubeDisplayName` em `users` com `ALTER TABLE`. Nenhuma tabela é recriada e `fallbackToDestructiveMigration` continua proibido.
- Pareamento TV (fase 12): o payload cifrado leva `avatarBase64` (JPEG 512) e a identidade YouTube (`youtubeAccountId`/`youtubeDisplayName`) além dos cookies. A exportação recusa sem as duas contas; a TV recusa payload sem cookies, força o tipo Myst mesmo em payload antigo, grava as duas sessões, o vínculo no Room e o avatar. A TV não recebe senha.
- Configurações: **Deslogar** abre a escolha Myst / YouTube / ambas. Sair só do YouTube mantém o perfil e as playlists; sair do Myst mantém a sessão YouTube para a próxima conta Myst. Na TV, qualquer logout volta ao pareamento.
- Versão de desenvolvimento da fase 12: `0.2.0-dev.2`, `versionCode 11`.
- Documentação: o dono do repositório traduziu o README para inglês e apagou o `PROGRESS.md` no remoto durante a fase 11. O README em inglês foi preservado; o `PROGRESS.md` foi restaurado porque o `AGENTS.md` exige o registro de fases/builds. A referência a "Roadmap no README" foi ajustada para apontar ao `PROGRESS.md`.
- Versão de desenvolvimento da fase 11: `0.2.0-dev.1`, `versionCode 10`.
- Conta portátil (0.2.0-dev.7): o `Mystaccount.json` é um backup **e** uma chave de login. O esquema v2 guarda perfil, salt/hash PBKDF2, avatar em base64, playlists Myst com capas em base64 (quando disponíveis), faixas, histórico, afinidade, o cache da biblioteca do YouTube Music (playlists, faixas já carregadas, recentes e marcas de sincronização) e as preferências de aparência (tema e AMOLED). Arquivos v1 continuam aceitos; campos ausentes não sobrescrevem nada. Cookies do YouTube, token do Discord e outras sessões ficam de fora de propósito: são sessões, não dados da conta, e o login do YouTube é pedido depois da restauração.
- Cópia interna em `filesDir/account/Mystaccount-<userId>.json`; cópia externa escolhida pelo usuário via SAF. A URI externa só é guardada quando o provedor concede escrita persistente; nesse caso a cópia é atualizada junto com a interna. Sem permissão persistente, a tela mostra "atualização automática indisponível" e permite salvar outra cópia. Ao importar, o app tenta religar o próprio arquivo escolhido como cópia externa, para ele continuar sendo atualizado depois de uma reinstalação.
- Atualização automática: um `InvalidationTracker.Observer` observa `users`, `playlists`, `playlist_tracks`, `history`, `artist_affinity`, `yt_playlists`, `yt_playlist_tracks`, `yt_history` e `yt_sync_state`; o pedido é debounced em 1,5 s e roda em `Dispatchers.IO`. O observer é registrado fora da thread principal porque `addObserver` executa SQL no Room. Mudanças de aparência também chamam `requestSync()`, já que ficam em SharedPreferences. Cadastro, login, importação, avatar e vínculo do YouTube chamam `requestSync()` explicitamente.
- Importação: se o usuário já existe (mesmo username), o arquivo é mesclado sem apagar playlists/histórico locais; playlists Myst são casadas por nome, faixas por `videoId`, histórico por `videoId|playedAt` e afinidade usa o maior score. Playlists do YouTube são mescladas por `ytId`; faixas em cache só entram quando a playlist ainda está vazia, e histórico/sincronização preservam o registro mais recente. Se a conta não existe, é recriada com o mesmo `createdAt` (quando válido). O arquivo é validado antes de qualquer escrita e o limite de leitura é 32 MB.
- Limites de exportação: 30.000 faixas Myst, 30.000 faixas de playlists do YouTube, 4 MB de capas, 20.000 itens de histórico local, 200 itens de histórico do YouTube e 5.000 artistas. Eles mantêm o arquivo abaixo do limite de importação e evitam OOM em aparelhos pequenos; o que for omitido fica registrado no log.
- Aparência restaurada: tema Claro/Escuro/Sistema e AMOLED são aplicados ao final da importação na thread principal; o AppCompat recria a tela de login e o ViewModel continua o fluxo. Arquivos antigos (v1) não têm esses campos e não alteram a preferência local.
- Backup do Android: `myst_account_file.xml` (URI externa) e `account/` (cópia interna) são excluídos das regras de backup/transferência, pois a URI não vale em outro aparelho e a cópia interna é regenerada do Room. As sessões (`myst_session*.xml`) continuam excluídas.
- Segurança do arquivo: ele contém o hash PBKDF2 e os dados da conta, então a UI avisa que deve ser guardado como uma chave. Não há criptografia própria no JSON; a proteção é o armazenamento escolhido pelo usuário e o aviso explícito. O avatar e as capas têm limites de tamanho antes de decodificar base64.
- TV: a seção de arquivo em Configurações fica oculta porque o seletor de documentos não é confortável no controle remoto; a conta chega pelo pareamento. O botão de importação no login continua disponível para quem usa teclado/controle compatível, com erro visível se não houver seletor.
- TV temporária (0.2.0-dev.8): o flavor `tv` usa `Room.inMemoryDatabaseBuilder`, apaga o `mystmusic.db` antigo no primeiro acesso e não grava `Mystaccount.json`, capas ou avatar em disco. Ao desconectar pelo celular ou deslogar na TV, as tabelas e os diretórios `account`, `covers` e `avatars` são limpos; a tela volta ao código de 5 dígitos. Ao reiniciar o processo, a linha mínima do usuário é recriada em memória para o canal de pareamento continuar aceitando o celular; as playlists voltam no próximo sync. Nada disso é persistido na TV.
- Sincronização em tempo real (0.2.0-dev.11): `PairSnapshot` carrega também `yt_playlists`, `yt_playlist_tracks`, `yt_history` e `yt_sync_state`, então Recentes e a biblioteca do YouTube chegam à TV no pareamento e a cada sync. A TV mantém um servidor autenticado (porta 8766 no celular, 8765 na TV, com fallback) e um observador do Room; ao mudar qualquer tabela pareada, o lado local avisa o outro em até ~700 ms, sem esperar o ciclo de 30 s. O ciclo de 30 s permanece como segurança para perda de rede. Endpoints ficam em cache e o NSD descobre o outro aparelho quando o IP muda.
- Aparência e Discord na TV (0.2.0-dev.11): `PairExtras` leva a aparência e o Discord em cada sync. A TV aplica o resultado efetivo do celular (Claro/Escuro), então o AMOLED ativo chega ligado mesmo quando a preferência do celular é "Sistema"; se o celular fica claro, o AMOLED é desligado na TV. Mudanças em Aparência e Discord disparam um sync imediato, e não apenas no próximo pareamento. O token do Discord é cifrado no canal e aplicado pela TV; desconectar no celular também desconecta na TV. A preferência de aparência continua temporária e é apagada na desconexão/logout.
- Backup automático (0.2.0-dev.8): o celular grava o `Mystaccount.json` em `/sdcard/MystMusic` quando tem `MANAGE_EXTERNAL_STORAGE` (Android 11+) ou `WRITE_EXTERNAL_STORAGE` (Android 6–10). Sem essa permissão, usa `Download/MystMusic` via MediaStore sem pedir nada; em último caso, o armazenamento privado do app (some ao desinstalar). O app pede o acesso uma vez na abertura e oferece de novo em Configurações → Conta Myst, mostrando o caminho real. O arquivo é regravado a cada mudança do Room e antes de qualquer logout; a TV nunca chama esse caminho.
- Logout com TV pareada: o celular tenta avisar a TV por até 3 s antes de apagar o segredo local. Se a TV estiver desligada/fora da rede, o logout continua e a TV limpa os dados temporários quando o processo terminar; o pareamento local é removido de qualquer forma.
- Aparência no `Mystaccount.json`: tema e AMOLED continuam sendo preferências do aparelho, mas são exportados/restaurados junto com a conta a pedido do usuário; campos ausentes (arquivos v1) não sobrescrevem a escolha local.
- Dados por conta Myst (fase 14): `playlists`, `history`, `artist_affinity`, `pair_tombstones` e as tabelas `yt_*` já eram filtradas por `userId`. A migração 4→5 passou a levar `userId` também para `playlist_tracks`, com FK para `users` e cópia do dono da playlist; assim nenhuma faixa órfã fica visível para outra conta. Aparência continua por aparelho (decisão da fase 11); os dados de gosto/afinidade, histórico, playlists e estatísticas são da conta. Ao trocar de conta Myst, as consultas usam o `userId` da sessão ativa, sem misturar nada.
- Migração Room 4→5: `yt_playlist_tracks` e `yt_history` ganham `album` com `ALTER TABLE`; `history`, `artist_affinity` e `playlist_tracks` são recriadas em uma transação, copiando os dados e ligando `userId` ao dono existente (`JOIN users`), e `play_events` é criada. `fallbackToDestructiveMigration` continua proibido. `exportSchema = true` e o schema 5 fica em `app/schemas/com.iskezis.myst.data.db.AppDatabase/5.json`; o workflow publica `myst-room-schema` como artefato. O SQL gerado pelo Room foi comparado coluna a coluna com o da migração (tabelas, defaults, FKs e índices) antes de fechar a fase; o teste de instalação por cima do dev.9 em aparelho ainda depende do usuário.
- Eventos de reprodução: `play_events` guarda `userId`, `videoId`, título, artista, álbum, capa, `startedAt`, `playedMs`, `durationMs`, `completed` e `source`, com índices `(userId, startedAt)` e `(userId, artist)`. O tempo é medido por deltas da posição do player: pausa, buffering e salto para frente/trás não contam. O evento é gravado na troca de faixa, no fim, após 30 s de pausa ou na destruição do serviço, sempre em `Dispatchers.IO` e na `backgroundScope` do app para sobreviver ao fim do `PlaybackService`. Uma reprodução passa a contar depois dos **10 primeiros segundos ouvidos**, inclusive se a faixa for pulada no meio, e o evento guarda o trecho exato escutado (`playedMs`), não a duração inteira; o limite de um evento é 24 h.
- Estatísticas (fase 14): a tela abre pelo ícone de gráfico no cabeçalho da Navegação, empilhada com voltar e navbar visível. Os chips Semana/Mês/Ano/Sempre consultam só a conta Myst ativa. Tempo ouvido compara com o período anterior de mesmo tamanho (Sempre não compara), reproduções usam o corte de 10 s e todas as agregações (tempo, contagem, gráficos, sequência e tops) filtram os eventos abaixo disso; tops limitam a 5 itens e os gráficos de 24 h e por dia são desenhados em `BarChartView` (Canvas, sem dependência nova, animação de 400 ms). Carregando usa shimmer; vazio mostra "Ouça algumas músicas para ver suas estatísticas"; erro mostra a mensagem e "Tentar novamente". A tela usa os tokens de tema e os tamanhos do flavor TV, com chips e botão de voltar focáveis por D-pad.
- Álbuns: o parser do InnerTube passou a extrair o álbum; ele viaja em `Track`, playlists, histórico e cache do YouTube e é gravado em `play_events`, permitindo o top álbuns. Eventos sem álbum ficam fora do ranking (`TRIM(album) != ''`); se nenhum evento do período tiver metadado, o card mostra a mensagem de vazio em vez de inventar nome. O top artistas usa o mesmo cuidado com artista vazio.
- `Mystaccount.json` v3: inclui `playEvents` (até 20.000, com o mesmo corte da importação), além dos dados das versões anteriores. A importação mescla por `videoId|startedAt`, aceita arquivos v1/v2 sem os eventos e não apaga eventos locais. O pareamento celular↔TV também transporta os eventos, então as estatísticas da conta acompanham a TV temporária e voltam ao celular.

## Problemas em aberto
- Fase 14: falta a confirmação em aparelho de que instalar o dev.10 por cima do dev.9, relogar e encontrar playlists/histórico/estatísticas intactos. A migração 4→5 e o schema exportado foram conferidos no CI e o build `40987101834` está verde; o ambiente do agente não tem ADB/aparelho para executar o teste de atualização.
- Validação funcional em aparelho físico confirmada pelo usuário em 29/10/2026: login YouTube, reprodução, navegação, Discord RPC com capa/progresso e pareamento TV. Nenhum bloqueador conhecido para a 0.1.0 Alpha.
- Discord: se o aplicativo público de capas for recusado, o log mostra `Discord recusou o aplicativo de capas: HTTP ...`; nesse caso, criar um aplicativo no Discord Developer Portal e definir `DISCORD_APP_ID` em `local.properties`/GitHub Secrets. Sem isso, o app continua funcionando e a presença aparece sem capa.
- Crash corrigido: o log de 28/10 mostrou `IllegalArgumentException: Software rendering doesn't support hardware bitmaps` em `TransitionUtils.createViewBitmap` ao sair da Busca com capas carregadas. A causa era o bitmap de hardware padrão do Coil; `MystApp.newImageLoader()` usa `allowHardware(false)` e o build 40795596184 inclui a correção.
- Logcat Reader: qualquer leitor externo precisa receber `android.permission.READ_LOGS` por ADB/root; sem isso o Android só mostra ao leitor os próprios logs. Exemplo (troque pelo pacote do leitor): `adb shell pm grant com.dp.logcatapp android.permission.READ_LOGS`. Os logs internos e o botão Compartilhar funcionam sem ADB.
- Crash: as lambdas de animação de lista em Busca/Playlists usavam `binding` depois de `onDestroyView`; agora são canceladas com o `viewLifecycleOwner`. O handler persistente grava a pilha em `myst_last_crash.txt`, carrega no diagnóstico após reiniciar e agora também grava a pilha completa no arquivo de log de forma síncrona.
- Discord: a causa do 401 era o token salvo com as aspas do `localStorage` (74 caracteres em vez de 72). A v1.0.4 remove as aspas no login, sanitiza tokens antigos no `DiscordPresenceManager` e valida pela REST v9. Se ainda falhar, a tela de logs mostra `MystDiscord` com o HTTP/close code exato; o token nunca aparece.
- Áudio: o APK não contém bibliotecas nativas próprias; a compatibilidade de codecs AAC/Opus depende do decodificador do aparelho/TV, por isso a ordem prioriza AAC.
- Fase 10: checklist estrutural concluída (pacote/nome, flavors, telas com estados, APKs) e validação funcional confirmada em aparelho físico; versão renomeada para 0.1.0 Alpha.
