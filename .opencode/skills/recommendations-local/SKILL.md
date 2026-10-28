---
name: recommendations-local
description: Histórico de reprodução em Room e algoritmo simples de gosto do usuário para ordenar Em Alta e gerar Recomendados.
---
# Recomendações locais
- Room: `history(id, videoId, title, artist, thumb, playedAt, playedMs)`; `artist_affinity(artist, score)`.
- Registrar play após 20s ou 30% da faixa. Score de artista: `score = score*0.95 + 1` por play (decay), +2 se completou, −1 se pulou antes de 10s.
- **Recentes:** últimas 20 faixas distintas por `playedAt desc`.
- **Em Alta:** itens de trending do YTM; ordenar por `0.6*afinidade(artista) + 0.4*posição_normalizada`. Se histórico vazio, ordem original.
- **Recomendados:** para as 3 últimas faixas e top 3 artistas, pedir relacionados (`next`), unir, remover já ouvidas recentemente e duplicatas, embaralhar levemente (seed diário), limitar a 30.
- Tudo em coroutine `Dispatchers.IO`, exposto via `StateFlow<SectionState>` (Loading/Data/Empty/Error). Cache em memória por 5 min.
- Usuário novo: mostrar Em Alta genérico e Recomendados populares com aviso discreto "Ouça músicas para personalizar".
