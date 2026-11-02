versionCode: 47
## MystMusic 0.2.5 Beta 3 — cores adaptativas no player

O player agora escolhe preto ou branco olhando para o fundo real: a própria capa borrada, sem usar as cores do tema.

### Novidades desta beta
- **Contraste real:** cada área do player mede a luminância do fundo e escolhe a cor de conteúdo com maior contraste WCAG, com histerese de 15% para capas de luminância média não ficarem piscando.
- **Três zonas independentes:** topo (voltar, título da tela), bloco de título/artista e controles (progresso, tempos, play/pause, anterior/próximo, repetição e fila) podem usar preto ou branco separadamente, porque o blur pode ser claro em cima e escuro embaixo.
- **Transição suave:** as cores mudam em 250 ms e o fundo faz crossfade junto, sem piscar entre faixas.
- **Play/pause circular:** o botão vira um círculo preenchido com a cor do conteúdo e o ícone na cor oposta; na TV, o anel de foco do D-pad é desenhado dentro do círculo e continua visível em qualquer fundo.
- **Barra de progresso adaptativa:** trilho com 25% de alpha, preenchimento sólido e bolinha na cor do conteúdo.
- **Capa ausente:** o placeholder também é medido de verdade; se uma capa falhar, o player cai no placeholder sem repetir a requisição a cada atualização de estado.
- **Temas e capas:** funciona nos temas claro, escuro e Preto AMOLED, com qualquer cor de destaque, em capas claras, escuras, brancas, pretas e coloridas.
- **TV:** a mesma lógica de zonas e contraste, com o cabeçalho agora exibindo o nome do app abaixo de "Tocando agora".
- **Canal de atualização:** esta Beta 3 é uma **pré-release**; ela aparece para quem escolheu **Estáveis e betas** e não é oferecida no canal **Somente estáveis**.

### Compatibilidade
- Atualiza por cima da Beta 2 (`versionCode 46`) e anteriores com a mesma assinatura, mantendo conta Myst, conta YouTube, playlists, histórico, downloads e estatísticas.
- Nenhuma migração do Room nesta build.
- A TV continua usando `app-tv.apk` (`armeabi-v7a`, 32 bits).

### Como testar
1. Abra o player com capas claras, escuras, brancas, pretas e coloridas.
2. Confira ícones, textos, tempos, barra de progresso e play/pause em cada faixa; use pausar/continuar e arraste a barra.
3. Troque entre tema claro, escuro e Preto AMOLED, e entre as cores de destaque.
4. Role o player: as cores devem se ajustar à área visível da capa borrada.
5. Na TV, navegue com o D-pad e confira o anel de foco no play.
6. Teste uma faixa sem capa ou com capa que falhe; o placeholder deve manter tudo legível.

### Detalhes
- A escolha é por contraste relativo WCAG, não por um limiar fixo; a histerese de 15% evita piscar em capas de luminância média.
- A medição usa uma amostra reduzida do blur já posicionado na tela (mesmo recorte `centerCrop` do fundo), sempre fora da thread principal.
- O helper `AdaptiveContentColors` fica em `ui/common` e será reutilizado na fila em tela cheia da fase 22.
- As barras de status e navegação continuam seguindo o tema escuro/claro do app, porque o fundo do player é desenhado abaixo delas; os controles do player seguem a capa.
