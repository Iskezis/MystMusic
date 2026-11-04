versionCode: 49
## MystMusic 0.2.5 Beta 5 — fila em tela cheia

A fila de reprodução deixou de ser uma folha inferior e passou a ocupar a tela inteira, com o fundo da capa atual borrado, cores adaptativas e abertura instantânea quando o player já calculou o fundo. Esta é a fase 22 do roadmap.

### Fila em tela cheia
- **Tela inteira:** o botão de fila do player abre a nova `QueueFragment` sobre o player, com botão de voltar, título, contagem de faixas e cartão **Tocando agora** com capa, título, artista e equalizador animado.
- **Fundo compartilhado:** player e fila usam o mesmo `CoverBackdropView` e o mesmo `BackdropCache` por `videoId` + URL da capa. Abrir a fila com a capa em cache não baixa nem desfoca a imagem de novo.
- **Troca de faixa:** com a fila aberta, o fundo faz crossfade de 300 ms e as cores acompanham a nova capa. Faixas sem capa usam o gradiente do placeholder.
- **Cores adaptativas:** o topo e a lista medem a luminância do fundo separadamente, com a mesma histerese e o mesmo contraste WCAG da fase 21. Textos, ícones, fundo da linha atual, foco do D-pad e botão de repetir usam preto ou branco conforme a capa, com transição de 300 ms.
- **Linha atual e próxima:** a lista começa na faixa tocada, mostra o cartão **Tocando agora**, a seção **A seguir** e mantém o comportamento de tocar em uma linha para pular para ela. Nenhuma ação existente da fila foi removida.
- **Estados completos:** carregando com shimmer, fila vazia com mensagem e erro com **Tentar novamente**. O mesmo layout é usado no celular e na TV.
- **Abertura e fechamento:** entrada deslizando de baixo para cima no celular, arrastar o cabeçalho ou o cartão para baixo para fechar, botão de voltar e transição suave ao retornar ao player. A fila pode ser aberta e fechada várias vezes.
- **TV 32 bits:** foco inicial na faixa atual, linhas focáveis por D-pad com anel de 2 dp, fundo com blur mais leve (160 px, duas passadas) e transições mais curtas. O equalizador fica estático para economizar CPU.

### Compatibilidade
- Atualiza por cima da Beta 4 (`versionCode 48`) e anteriores com a mesma assinatura, mantendo conta Myst, conta YouTube, playlists, histórico, downloads e estatísticas.
- Nenhuma migração do Room nesta build.
- A TV continua usando `app-tv.apk` (`armeabi-v7a`, 32 bits).
- Esta Beta 5 é uma **pré-release**: aparece no canal **Estáveis e betas** e não é oferecida no canal **Somente estáveis**.

### Como testar
1. Abra o player com uma música, toque no botão de fila e confirme que ela ocupa a tela inteira com o fundo da capa borrado.
2. Confira o cartão **Tocando agora**, o equalizador animado, a seção **A seguir** e se a lista começa na faixa atual.
3. Toque em outra faixa e confirme o crossfade do fundo e das cores; repita com capas claras, escuras, brancas, pretas e coloridas nos temas claro, escuro e Preto AMOLED.
4. Abra e feche a fila várias vezes, inclusive enquanto o player continua tocando, e confirme que o fundo aparece desde o primeiro frame.
5. Feche pelo botão de voltar, pelo gesto de arrastar para baixo e pelo botão do sistema.
6. Teste uma fila vazia, uma faixa sem capa e uma falha de rede/capa para conferir os estados vazio, placeholder e erro com **Tentar novamente**.
7. Na TV, abra a fila pelo D-pad, confira o foco inicial na faixa atual, navegue pelas linhas e volte ao player.

### Detalhes
- `QueueSheet` e `sheet_queue.xml` foram removidos; não há mais bottom sheet de fila.
- O fundo borrado é mantido em um cache LRU de até 8 capas, sem reciclar bitmaps que ainda podem estar em uso.
- Todo texto novo foi adicionado em `values/`, `values-en/` e `values-es/`.
