versionCode: 48
## MystMusic 0.2.5 Beta 4 — correção do fundo do player

Corrige o fundo da capa borrada que desaparecia ao abrir o player pela segunda vez. Os controles adaptativos e todos os recursos da Beta 3 continuam iguais.

### Correções desta beta
- **Fundo na segunda abertura:** ao reabrir o player com a capa já em cache, o Coil entrega a imagem no mesmo frame em que a tela nasce. A medição do placeholder (feita para faixas sem capa) podia cancelar o desfoque real e deixar o player só com a cor de fundo do tema. Agora o pedido da capa sempre vence, a medição do placeholder é cancelada quando chega uma capa e o desfoque é aplicado normalmente em toda abertura.
- **Sem corrida entre fundo e placeholder:** o desfoque real e a medição do placeholder usam jobs separados, com uma guarda que impede uma medição antiga de sobrescrever a paleta ou o fundo novos.
- **Diagnóstico:** o log interno registra quando o fundo da capa é aplicado (`Fundo do player aplicado: ...`), sem gravar a URL ou dados da conta.

### Cores adaptativas (mantidas da Beta 3)
- **Contraste real:** cada área do player mede a luminância do fundo e escolhe a cor de conteúdo com maior contraste WCAG, com histerese de 15% para capas de luminância média não ficarem piscando.
- **Três zonas independentes:** topo (voltar, título da tela), bloco de título/artista e controles (progresso, tempos, play/pause, anterior/próximo, repetição e fila) podem usar preto ou branco separadamente.
- **Transição suave:** as cores mudam em 250 ms e o fundo faz crossfade junto, sem piscar entre faixas.
- **Play/pause circular:** círculo preenchido com a cor do conteúdo, ícone na cor oposta e anel de foco do D-pad desenhado por dentro na TV.
- **Barra de progresso adaptativa:** trilho com 25% de alpha, preenchimento sólido e bolinha na cor do conteúdo.
- **Capa ausente:** o placeholder também é medido de verdade; se uma capa falhar, o player cai no placeholder sem repetir a requisição a cada atualização de estado.
- **Temas e capas:** funciona nos temas claro, escuro e Preto AMOLED, com qualquer cor de destaque, em capas claras, escuras, brancas, pretas e coloridas.
- **Canal de atualização:** esta Beta 4 é uma **pré-release**; ela aparece para quem escolheu **Estáveis e betas** e não é oferecida no canal **Somente estáveis**.

### Compatibilidade
- Atualiza por cima da Beta 3 (`versionCode 47`) e anteriores com a mesma assinatura, mantendo conta Myst, conta YouTube, playlists, histórico, downloads e estatísticas.
- Nenhuma migração do Room nesta build.
- A TV continua usando `app-tv.apk` (`armeabi-v7a`, 32 bits).

### Como testar
1. Abra o player com uma música de capa clara; confirme o fundo borrado e os botões escuros.
2. Volte, abra o player novamente com a mesma música (capa em cache) e confirme que o fundo borrado continua aparecendo.
3. Repita com capas escuras, brancas, pretas e coloridas nos temas claro, escuro e Preto AMOLED.
4. Troque rapidamente de faixa e role o player; o fundo e as cores devem acompanhar sem piscar.
5. Teste uma faixa sem capa ou com capa que falhe; o placeholder deve manter tudo legível.
6. Na TV, abra e reabra o player e confira o fundo e o anel de foco do play com o D-pad.

### Detalhes
- A capa é medida fora da thread principal em uma amostra reduzida do blur já posicionado na tela (mesmo recorte `centerCrop` do fundo).
- O helper `AdaptiveContentColors` fica em `ui/common` e será reutilizado na fila em tela cheia da fase 22.
- As barras de status e navegação continuam seguindo o tema escuro/claro do app, porque o fundo do player é desenhado abaixo delas; os controles do player seguem a capa.
