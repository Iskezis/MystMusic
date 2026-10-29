---
name: youtube-music-library-sync
description: Ler dados da conta do YouTube Music do usuário via InnerTube autenticado (playlists da biblioteca, histórico, recomendações) e exibi-los em Playlists, Recentes e Recomendados. Use na fase 13.
---
# Biblioteca do YouTube Music da conta

Tudo na camada `data/youtube` (interface `MusicSource`), usando as credenciais da sessão (cookies + SAPISIDHASH, ver `youtube-music-innertube`). Parser defensivo (JSON muda); sem `!!`.

## Endpoints (`POST /youtubei/v1/browse`, cliente WEB_REMIX autenticado)
- **Playlists da biblioteca:** `browseId = "FEmusic_liked_playlists"` (e `FEmusic_library_landing`). Resultado em grade; paginar com `continuation`. Incluir "Músicas curtidas" (`VLLM`) como playlist especial.
- **Histórico:** `browseId = "FEmusic_history"` → seções por dia ("Hoje", "Ontem"…); achatar mantendo ordem decrescente; limitar às ~50 mais recentes distintas.
- **Home personalizada:** `browseId = "FEmusic_home"` autenticado → carrosséis ("Escutar de novo", "Mixes para você", "Quick picks"). Mapear para Recomendados; paginar continuação.
- **Faixas de uma playlist:** `browse` com `VL<playlistId>` (+ continuação).
- Verifique os identificadores atuais no tráfego/doc da lib usada; se mudou, adapte e registre.

## Modelo e cache
- Room: `yt_playlists(ytId, userId, title, thumb, trackCount, syncedAt)`, `yt_playlist_tracks(ytId, position, videoId, …)` (carregadas sob demanda ao abrir), `yt_history(userId, videoId, title, artist, thumb, playedAt ordem)`.
- Cache: dados exibidos imediatamente do Room e atualizados em background (stale-while-revalidate); TTL 15 min; pull-to-refresh força.

## Integração nas telas
- **Playlists:** duas seções com cabeçalho: "Minhas playlists (Myst)" e "YouTube Music". As do YouTube são **somente leitura** no app (tocar, embaralhar, baixar na fase 16). Ação "Copiar para Myst" cria uma playlist Myst editável com as mesmas faixas. Nunca escrever na conta do YouTube.
- **Recentes:** mesclar histórico do YTM + histórico local da conta Myst, ordenar por `playedAt` (YTM só traz ordem/dia: usar ordem relativa e intercalar com `playedAt` local), remover duplicatas por `videoId`, máx. 20.
- **Recomendados:** vêm da home do YTM; complementar com `next`/related das últimas faixas se vier vazio. Sem o feed, cair para o algoritmo local (`recommendations-local`).
- **Em Alta:** continua com charts/trending, reordenado pela afinidade (que agora também considera as faixas do histórico do YTM).

## Robustez
- Sessão expirada (`LOGIN_REQUIRED`, 401/403): mostrar banner "Sessão do YouTube expirou — entrar de novo" que leva ao login do YouTube; nunca deslogar sozinho nem perder dados locais.
- Conta sem dados (nova): estados vazios amigáveis.
- Rede: timeout 15s, retry 2x, erro com "Tentar novamente".
- Sem registrar cookies/tokens em log.
