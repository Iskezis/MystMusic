---
name: discord-rpc
description: Discord Rich Presence no Android mostrando a música atual do MystMusic (opt-in, com aviso sobre termos do Discord).
---
# Discord RPC
- Android não tem IPC do Discord. Abordagem: cliente Gateway (`wss://gateway.discord.gg/?v=10&encoding=json`) enviando `op 3 (presence update)` com activity `type 2` (Listening), `name: "MystMusic"`, `details` = título, `state` = artista, `timestamps` (start/end), `assets.large_image` = capa (usar `mp:external/` via endpoint de assets externos ou application assets).
- Exige token de usuário obtido por login WebView do Discord → **zona cinzenta dos termos do Discord**. Mostrar aviso claro e exigir ativação opt-in. Alternativa legítima: se houver Application ID próprio e SDK oficial utilizável, preferir.
- Implementação: `DiscordPresenceManager` (OkHttp WebSocket), heartbeat conforme `hello`, reconexão com backoff, desligar quando pausado por >2 min ou ao deslogar. Token em EncryptedSharedPreferences.
- UI: item único "Discord" em Configurações abaixo de "Deslogar" (conectar/desconectar). Sem Discord conectado, o app funciona normalmente.
- Nunca travar o player por falha do RPC (try/catch total).
