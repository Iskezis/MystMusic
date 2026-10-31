versionCode: 43
## MystMusic 0.1.8 Beta 4

Correção do despareamento celular ↔ TV. Agora a TV para a música antes de sair do pareamento e todos os dados temporários dela chegam ao celular, inclusive quando a rede falha no primeiro instante.

### Correções desta build
- **A TV para de tocar ao desconectar:** o celular pede a parada antes de montar o retrato final. A TV libera o `MediaController`, envia uma ação direta ao `PlaybackService`, grava o último histórico/estatística e só então se desliga. Antes, um controller ainda vinculado mantinha o serviço vivo e a música continuava depois de o pareamento sumir da tela.
- **Despareamento confirmado nos dois lados:** a TV responde com o retrato final e espera o celular confirmar que mesclou os dados antes de apagar o Room em memória, o tema temporário e os arquivos da sessão. Se a confirmação não chegar, a TV preserva o retrato completo no outbox e não perde dados.
- **Outbox completo da TV:** além das estatísticas, ficam em disco no outbox as playlists, faixas, histórico, afinidades, lápides e buscas recentes da TV. O retrato é reaplicado na próxima abertura ou no próximo pareamento com a mesma conta Myst e reenviado antes de qualquer novo play.
- **Celular tenta de novo:** se a TV estiver desligada ou fora da rede quando o usuário desconectar, o pareamento some da interface imediatamente, mas o celular guarda as credenciais por um período curto e continua tentando parar a TV e buscar o retrato final. O aviso de força agora explica esse comportamento.
- **Dados em tempo real:** buscas recentes da TV passaram a viajar no snapshot; o `PlaybackService` avisa o sincronizador após gravar histórico, álbum e eventos de reprodução, e o retrato final é mesclado antes de a TV limpar os dados.

### Compatibilidade
- Atualiza por cima da Beta 3 (`versionCode 42`) e anteriores com a mesma assinatura, mantendo conta Myst, playlists, histórico e estatísticas.
- A TV usa o mesmo APK do flavor `tv` (`armeabi-v7a`, 32 bits). Para o despareamento novo funcionar por completo, atualize **celular e TV** para a Beta 4.
- Nenhuma migração do Room foi necessária nesta build.

### Build e assinatura
- `versionCode: 43`, `versionName "0.1.8 Beta 4"`, compilado pelos workflows do GitHub Actions com a keystore estável dos Secrets.
- Assets desta release: `app.apk` (celular), `app-tv.apk` (Android TV) e `SHA256SUMS.txt`.
- A release é uma pré-release por ser um beta; o app lê o `versionCode` da primeira linha do corpo e aceita a atualização.

### Como testar
1. Instale a Beta 4 no celular e na TV.
2. Pareie a TV, toque uma música e, com ela tocando, use **Configurações → Desconectar TV** no celular.
3. A música da TV deve parar na hora, a TV deve voltar à tela do código e o celular deve esconder a TV.
4. Repita o teste desconectando pela própria TV e confira que o celular também esconde a TV.
5. Na TV, antes de desconectar, gere histórico/estatísticas e confira no celular depois da desconexão que Recentes, Estatísticas e playlists continuam iguais.
6. Se a TV estiver desligada ao desconectar, abra o app do celular depois com a TV ligada; ele deve concluir a parada e buscar o retrato final.
