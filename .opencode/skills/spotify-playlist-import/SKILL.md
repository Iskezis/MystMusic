---
name: spotify-playlist-import
description: Importar playlist do Spotify a partir de link colado em pop-up, extrair faixas e mapear cada uma para o YouTube Music, criando uma playlist Myst.
---
# Integrar do Spotify
1. Pop-up com campo de texto (ver `android-music-ui-xml`). Aceitar `https://open.spotify.com/playlist/<id>?si=...`, `open.spotify.com/intl-xx/playlist/<id>`, `spotify:playlist:<id>`. Regex do id: `[A-Za-z0-9]{22}`. Inválido → erro inline "Link de playlist inválido".
2. Obter faixas (tentar na ordem; registrar qual funcionou):
   - **A)** Web API: token por Client Credentials (`client_id/secret` via BuildConfig/GitHub Secrets) → `GET /v1/playlists/{id}` e itens paginados (limit 100). Playlists públicas. Atenção: Spotify restringiu alguns endpoints para apps em modo dev; verifique a doc atual.
   - **B)** Fallback sem chave: `https://open.spotify.com/embed/playlist/{id}` e ler o JSON `__NEXT_DATA__` embutido (nome, capa, lista de faixas/artistas). Parser defensivo.
   - Playlists privadas/algorítmicas: mostrar mensagem clara de que só playlists públicas são suportadas.
3. Mapear cada faixa → YouTube Music: busca `"<título> <artista>"` (filtro música), escolher melhor por similaridade de título/artista e duração (±5s). Executar com concorrência 4, progresso na UI ("Importando 12/48").
4. Criar playlist Myst (nome e capa do Spotify), salvar faixas encontradas na ordem original; relatar não encontradas ("3 faixas não encontradas").
5. Estados: carregando com progresso, sucesso (navega para a playlist), erro de rede (tentar novamente). Cancelável.
