---
name: offline-downloads
description: Downloads offline no MystMusic: música individual (toque longo), álbum/playlist inteira e todas as músicas de um artista, com fila, reprodução sem rede e gerenciamento. Use na fase 16.
---
# Downloads offline

## Funcionalidades (todas obrigatórias)
1. **Uma música:** toque longo na música (em qualquer lista) → menu/bottom sheet com **Baixar** (vira **Remover download** se já baixada).
2. **Álbum/playlist inteira:** botão **Baixar tudo** na tela do álbum e da playlist (Myst, YouTube e públicas). Mostra progresso agregado "12/40".
3. **Todas as músicas de um artista:** no perfil do artista, botão **Baixar todas as músicas** → diálogo de confirmação com **quantidade de faixas e espaço estimado** → baixa as músicas do artista (lista completa de "Músicas", com continuação; evita duplicatas).
4. **Reprodução offline:** faixa baixada toca sem rede. Sem rede, Navegação mostra baixadas/estado offline amigável em vez de erro.
5. **Gerenciar:** seção **Baixadas** dentro da aba Playlists (chip/seção "Baixadas"): lista, remover uma, remover todas, espaço usado.

## Arquitetura
- Armazenamento: `filesDir/offline/<videoId>.m4a` (privado do app; **não** em pasta pública). Escrita em `<videoId>.part` + `rename` ao concluir; retomar com `Range` se o `.part` existir.
- Room: `downloads(userId, videoId, title, artist, album, thumb, status[QUEUED|DOWNLOADING|DONE|FAILED], bytes, totalBytes, path, addedAt)`. Capa baixada junto (miniatura 512) para funcionar offline. Os downloads pertencem à conta Myst.
- Fila: `DownloadService` (foreground, tipo `dataSync`; declarar permissão `FOREGROUND_SERVICE_DATA_SYNC` no targetSdk 34) ou WorkManager com `setForeground`. Concorrência **2** (1 na TV). Notificação com progresso agregado e ação cancelar. Retentativas 3x com backoff; URL expirada → reresolver via `InnerTubeX` (mesma lógica do player, formato AAC/itag 140 primeiro).
- Baixar usando o mesmo `OkHttpClient`/headers do player. Validar `Content-Length` e tamanho final.
- Reprodução: no `ResolvingDataSource`, se existir arquivo `DONE` para o `videoId` → `Uri.fromFile`, sem rede. Senão, fluxo normal.
- Espaço: checar `StatFs` antes (exigir folga ≥ 200 MB; avisar). Se acabar espaço, pausar com mensagem.
- Cancelar/pausar: tocar na notificação ou na tela Baixadas; sem crash ao perder rede (pausa e retoma quando voltar).
- Indicadores: ícone ✓ pequeno em faixas baixadas; anel de progresso ao baixar.
- Primeiro download: aviso "Para uso pessoal; os arquivos ficam privados no app. Respeite os termos do serviço." (uma vez).
- TV: mesmos fluxos com D-pad; armazenamento é pequeno, mostrar espaço livre antes de grandes lotes.
- Backup do Android: excluir `offline/` do backup.

## Casos de borda
Música indisponível/restrita → marcar FAILED com motivo e seguir a fila. Duplicada → ignorar. Remover playlist não remove arquivos usados por outra. Deslogar não apaga arquivos; trocar de conta Myst mostra os da conta ativa.
