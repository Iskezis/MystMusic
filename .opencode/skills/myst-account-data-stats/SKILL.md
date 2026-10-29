---
name: myst-account-data-stats
description: Persistência por conta Myst (recentes, gostos, playlists, histórico, preferências, estatísticas), migrações Room não destrutivas, backup e a tela de Estatísticas. Use na fase 14.
---
# Dados da conta Myst + Estatísticas

## Princípio
Tudo que pertence ao usuário fica ligado a `userId` (conta Myst). Ao relogar, **inclusive depois de atualizar o app**, o usuário encontra: recentes, gostos/afinidades, playlists, histórico, estatísticas e preferências.

## Persistência segura
- Auditar todas as tabelas: toda a tabela de dados do usuário tem `userId` (FK `users.id`, índice).
- **Proibido** `fallbackToDestructiveMigration`. Banco com `exportSchema = true` (schemas em `app/schemas/`), `Migration(n, n+1)` explícitas para cada mudança, e teste de migração (`MigrationTestHelper`) se compilar no CI; se não for possível testar no CI, validar escrevendo a migração com `ALTER TABLE`/`CREATE TABLE` cuidadosamente e registrar.
- Escritas de reprodução em transação; ao deslogar não apagar nada. "Excluir conta" não existe (fora de escopo).
- **Backup:** `android:allowBackup="true"` + `dataExtractionRules`/`fullBackupContent` incluindo o banco e `avatars/` (excluir cookies/tokens e `downloads/`). Isso permite restaurar após reinstalar via backup do Google, quando disponível.
- Preferências por conta (ex.: histórico de afinidade) em Room; as do aparelho (tema) continuam locais.

## Eventos de reprodução (base das estatísticas)
Tabela `play_events(id, userId, videoId, title, artist, album, thumb, startedAt, playedMs, durationMs, completed, source)`. Gravar quando uma reprodução termina/troca/pausa por > 30s: `playedMs` é o tempo realmente ouvido (somar pausas corretamente via `Player.Listener`). Contar "reprodução" a partir de 30s ou 50% da faixa.
Índices: `(userId, startedAt)`, `(userId, artist)`. Manter agregados em views/queries; não armazenar valores redundantes.

## Aba Estatísticas
- Acesso: **botão no canto superior direito da aba Navegação** (ícone de gráfico, 48dp, mesmo header da barra de busca/avatar) → abre `StatsFragment` (empilhado com botão voltar; navbar visível).
- Filtro de período (chips): Semana · Mês · Ano · Sempre.
- Blocos (cards): **Tempo ouvido** (h/min, comparação com período anterior ↑↓), **Reproduções** (total), **Top músicas** (5, com contagem), **Top artistas** (5, com avatar/capa), **Top álbuns**, **Ouvido por horário do dia** (barras 24h), **Atividade por dia** (barras da semana/mês), **Sequência de dias** (streak atual e recorde).
- Gráficos desenhados com `Custom View`/Canvas próprias (sem dependência nova, que pode falhar no CI). Animar entrada (barras crescendo 400ms).
- Estados: carregando (shimmer), vazio ("Ouça algumas músicas para ver suas estatísticas"), erro.
- Dados 100% da conta Myst logada. Trocar de conta Myst troca as estatísticas.
- TV: tela legível, navegação D-pad pelos chips; sem gráficos minúsculos.

## Verificação
1. Tocar músicas, ver Estatísticas. 2. Gerar APK novo com versionCode maior, instalar por cima, relogar → tudo intacto. 3. Duas contas Myst no mesmo aparelho → dados separados.
