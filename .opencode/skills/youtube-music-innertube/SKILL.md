---
name: youtube-music-innertube
description: Integração com YouTube Music via InnerTube (login por WebView/cookies, busca, recentes, em alta, recomendados, resolução de stream) isolada em data/youtube.
---
# YouTube Music (InnerTube)
API não oficial. Isole tudo em `data/youtube` atrás de uma interface `MusicSource` (search, home, trending, related, streamUrl) para trocar implementação.

## Opções (decidir e registrar)
1. Usar biblioteca madura (ex.: NewPipeExtractor ou cliente InnerTube open source em Kotlin) — **preferida**: verifique versão atual e se resolve JitPack/Maven no CI.
2. Cliente próprio: `POST https://music.youtube.com/youtubei/v1/{search|browse|next|player}?prettyPrint=false` com `context.client` `WEB_REMIX`, `User-Agent` de navegador, cabeçalhos `Origin`/`Referer`, e quando logado `Cookie` + `Authorization: SAPISIDHASH <ts>_<sha1(ts + " " + SAPISID + " " + origin)>`.

## Login
`WebView` em `https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com` (JS ativo). Ao chegar em `music.youtube.com`, ler `CookieManager.getCookie(url)`, extrair `SAPISID`; salvar criptografado. Visitor data/`dataSyncId` via `browse` inicial. Foto/nome da conta via `account/account_menu`.

## Dados do app
- **Busca:** `search` com filtro de músicas; debounce 350ms; sugestões opcionais.
- **Recentes:** histórico local (Room `history`) — o que o usuário acabou de tocar; quando logado, mesclar com histórico do YTM se disponível.
- **Em Alta:** `browse` de charts/tendências + reordenar por afinidade (artistas/gêneros mais ouvidos local) — skill `recommendations-local`.
- **Recomendados:** `next`/`related` a partir das últimas faixas + `browse` home logado; sem login, usar semente do histórico.
- **Stream:** `player` → escolher formato áudio (itag 140/251). URLs expiram: reresolver antes de tocar e em erro 403. Considere cliente `ANDROID_VR`/`IOS` para URLs sem cifra se a lib recomendar.

## Robustez
Timeouts 15s, retry exponencial 2x, cache OkHttp 5 min para listas, tratamento de mudança de JSON (campos opcionais; parser defensivo com `JsonObject`, nunca `!!`). Erros mapeados em `Result.failure` e exibidos na UI.
