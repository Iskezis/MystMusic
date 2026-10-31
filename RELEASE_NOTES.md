versionCode: 38
## MystMusic 0.2.0-dev.29

Atualização pelo GitHub, assinatura estável e aviso de nova versão.

### Novo
- **Verificação de atualizações:** ao abrir o app já logado, o MystMusic consulta a release mais recente do GitHub. Se houver uma versão nova, aparece o pop-up "Nova versão disponível" com a versão atual → nova, o tamanho do download, as novidades e os botões **Atualizar** e **Agora não**. "Agora não" mantém a versão atual; o aviso volta na próxima abertura do app.
- **Botão do GitHub:** o cabeçalho da Navegação ganhou um botão ao lado de Estatísticas para verificar atualizações manualmente, com mensagem quando já está na versão mais recente e opção de tentar de novo em caso de falha.
- **Download com progresso:** o APK é baixado em segundo plano para o cache do app, com barra de progresso, porcentagem, tamanho e botão Cancelar. Quando a release publica `SHA256SUMS.txt`, o arquivo é conferido antes de ser entregue ao instalador.
- **Instalação pelo app:** o MystMusic abre o instalador do Android com a permissão de leitura temporária do APK. Se a instalação de fontes desconhecidas ainda não estiver liberada, o app explica e leva às configurações; ao voltar, a instalação é retomada.
- **Celular e TV:** o mesmo fluxo funciona no flavor `tv`, com o APK `app-tv.apk` e foco inicial no botão **Atualizar** no controle remoto.

### Build e assinatura
- Novo workflow `Release`: ao enviar uma tag `v*`, compila os dois flavors em release, verifica a assinatura, publica `app.apk`, `app-tv.apk` e `SHA256SUMS.txt` na GitHub Release e usa a keystore estável dos Secrets.
- O corpo da release começa com `versionCode: 38`, usado pelo app para comparar versões mesmo quando a tag não segue SemVer.

### Observação
- Se o Android recusar a instalação por diferença de assinatura, desinstale uma única vez e instale esta versão; daqui em diante as atualizações instalam por cima e mantêm os dados da conta Myst.
