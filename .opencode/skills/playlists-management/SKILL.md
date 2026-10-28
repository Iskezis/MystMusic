---
name: playlists-management
description: CRUD de playlists do MystMusic em Room: criar, renomear, imagem (galeria), reordenar músicas por drag & drop, remover, tocar.
---
# Playlists
- Room: `playlists(id, userId, name, coverUri, createdAt)`, `playlist_tracks(playlistId, videoId, title, artist, thumb, position)` (índice por `playlistId, position`).
- Criar: pop-up nome (obrigatório, ≤ 40). Renomear: pop-up. Imagem: `ActivityResultContracts.PickVisualMedia`; copiar para `filesDir/covers/<id>.jpg` (redimensionar 512px) e salvar uri interna. Sem imagem → gradiente azul com inicial.
- Reordenar: `ItemTouchHelper` (drag por alça); ao soltar, regravar `position` em transação. Remover com Snackbar desfazer.
- Adicionar música: menu "⋮" em qualquer faixa → "Adicionar à playlist" (bottom sheet com listas).
- Ações: tocar tudo, embaralhar, excluir playlist (confirmação).
- Todas as operações em `Dispatchers.IO`; UI observa `Flow` do Room.
