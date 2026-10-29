---
name: search-artist-album-playlist
description: Busca do MystMusic para músicas, artistas, álbuns e playlists, com perfil do artista, tela de álbum e tela de playlist via InnerTube. Use na fase 15.
---
# Busca ampliada + telas de detalhe

## Busca
- `POST /youtubei/v1/search` (WEB_REMIX). Sem filtro = resultados mistos ("Melhor resultado" + seções). Filtros por `params` (valores atuais do filtro de música: Músicas, Álbuns, Artistas, Playlists da comunidade/ do YT Music) — descubra-os nas respostas (`chipCloud`) em vez de fixar strings; aplique o `params` do chip tocado.
- UI: campo de busca com debounce 350 ms, **chips**: Tudo · Músicas · Artistas · Álbuns · Playlists. Resultados por tipo com layout próprio: música (linha), artista (círculo 72dp + nome + "Artista"), álbum (capa quadrada + título + artista · ano), playlist (capa + título + autor + nº faixas). Paginação por `continuation` ao rolar. Histórico de buscas local (5 últimas) opcional na tela vazia.
- Estados: carregando, vazio ("Nada encontrado"), erro.

## Perfil do artista (`ArtistFragment`)
- `browse` com `browseId` do canal (`UC…`). Mostrar: cabeçalho (foto grande + nome + ouvintes mensais se vier), botões **Tocar** e **Embaralhar**, seções: **Músicas populares** (lista, "Ver todas" → `browse` da lista completa com continuação), **Álbuns**, **Singles**, **Playlists**, **Artistas semelhantes**, **Sobre** (descrição recolhível).
- O botão **Baixar todas as músicas** será adicionado na fase 16 (deixe o espaço no cabeçalho).

## Álbum (`AlbumFragment`)
- `browse` com `MPREb_…` (resolver para a playlist de áudio `OLAK5uy…` para tocar). Capa grande, título, artista (toca → perfil), ano, nº de faixas, duração total, botões Tocar / Embaralhar, lista numerada de faixas. (Baixar tudo: fase 16.)

## Playlist (`PlaylistDetailFragment` para playlists do YT/públicas)
- `browse` com `VL<id>`; cabeçalho com capa, título, autor, contagem; lista com paginação; Tocar / Embaralhar; **Copiar para Myst**.

## Navegação e comportamento
- Todas as telas via Navigation Component com argumentos (`browseId`, `title`, `thumb` para renderização imediata + transição de elemento compartilhado da capa).
- Tocar uma faixa de álbum/playlist coloca **a lista inteira na fila** a partir da faixa tocada.
- Em qualquer faixa: toque longo → menu (Tocar a seguir, Adicionar à playlist, Ir para artista, Ir para álbum; Baixar chega na fase 16).
- Nomes de artista/álbum clicáveis no player e nas linhas de música.
- Parser em `data/youtube` isolado e defensivo, modelos `ArtistPage`, `AlbumPage`, `PlaylistPage`, `SearchResult` (sealed). Cache OkHttp 5 min.
