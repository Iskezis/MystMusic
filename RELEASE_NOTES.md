versionCode: 41
## MystMusic 0.1.8 Beta 2

Atualização de correção da beta, focada nas estatísticas reproduzidas na TV e na atualização em tempo real da tela de Estatísticas.

### Correção desta build
- **Estatísticas da TV:** cada reprodução feita na TV agora é confirmada pelo celular antes de os dados temporários serem descartados. Se o celular estiver dormindo, fora da rede ou com o app fechado, a TV guarda os eventos pendentes e os reenvia na próxima abertura ou no próximo pareamento com a mesma conta Myst. Nenhuma reprodução é perdida ao desligar a TV.
- **Estatísticas ao vivo no celular:** se a tela de Estatísticas estiver aberta quando a TV sincronizar, os números, gráficos e tops são atualizados automaticamente, sem precisar sair e voltar da tela.
- **Teclado da TV:** mantido o layout em duas colunas com rolagem e teclas de 52 dp do hotfix anterior; os cinco dígitos continuam visíveis em telas de 540 dp.

### Destaques
- **Atualização pelo GitHub:** ao abrir o app logado, a MystMusic verifica se há uma release mais nova. O pop-up mostra a versão atual → nova, o tamanho e as novidades, com **Atualizar** e **Agora não**. Se você escolher "Agora não", o aviso volta na próxima abertura. O botão do GitHub no cabeçalho também verifica manualmente.
- **Download e instalação:** o APK é baixado com barra de progresso e opção de cancelar; quando a release publica `SHA256SUMS.txt`, a integridade é conferida. O app abre o instalador do Android e explica como liberar "instalar apps desconhecidos" quando necessário.
- **Conta dupla:** login obrigatório com YouTube **e** conta Myst, foto de perfil, dados separados por conta e backup portátil `Mystaccount.json`.
- **YouTube Music:** playlists, histórico e recomendações da sua conta aparecem na Navegação, em Playlists e em Recentes.
- **Busca completa:** músicas, artistas, álbuns e playlists, com perfil do artista e telas de álbum/playlist; playlists públicas podem ser copiadas para a Myst.
- **Downloads offline:** baixe uma música, um álbum, uma playlist ou toda a discografia de um artista; gerencie o espaço e ouça sem rede.
- **Aparência e estatísticas:** temas Sistema, Claro, Escuro e Preto AMOLED; Estatísticas com tempo ouvido, tops e gráficos.
- **Player, Discord e TV:** player completo com fila e repetição, Discord RPC opt-in e flavor de TV 32 bits com pareamento por código de 5 dígitos.

### Build e assinatura
- `versionCode: 41`, compilado pelos workflows do GitHub Actions com a keystore estável dos Secrets.
- Instala por cima da build 40 mantendo conta Myst, playlists, histórico e estatísticas.
- Assets desta release: `app.apk` (celular), `app-tv.apk` (Android TV, armeabi-v7a) e `SHA256SUMS.txt`.

### Observação
- Se o Android recusar a instalação por diferença de assinatura, desinstale a versão antiga uma única vez. A partir da `0.2.0-dev.4`, as atualizações instalam por cima e mantêm os dados da conta Myst.
