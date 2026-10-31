versionCode: 42
## MystMusic 0.1.8 Beta 3

Atualização de correção da instalação do APK pelo próprio app. O APK e a assinatura continuam os mesmos; esta build corrige o caminho até o instalador do Android.

### Correção desta build
- **Fim do “erro ao analisar o pacote”:** o APK baixado agora é entregue ao Android por uma sessão do `PackageInstaller`, em vez de uma URI temporária do `FileProvider` em `cacheDir`. O instalador do próprio sistema grava o arquivo na sessão e não depende de permissão temporária, de cache do app ou do suporte do instalador de fábrica a `content://`.
- **APK validado antes de instalar:** o download confere o tamanho informado pelo GitHub, o SHA-256 quando a release publica `SHA256SUMS.txt`, a assinatura ZIP, o pacote `com.iskezis.myst` e se o `versionCode` é maior que o instalado. Se o arquivo chegou truncado ou o servidor devolveu outra coisa, o app mostra “APK inválido” e oferece **Tentar novamente**, sem abrir o instalador.
- **Falhas visíveis:** se o Android recusar a sessão de instalação, o motivo aparece em uma notificação: permissão de fontes desconhecidas bloqueada, assinatura diferente, APK inválido, incompatibilidade ou falta de espaço.
- **Sem regressão de dados:** a atualização instala por cima da Beta 2 (versionCode 41), da Beta 1 (40) e das versões anteriores com a mesma assinatura, mantendo conta Myst, playlists, histórico e estatísticas.

### Destaques que continuam
- **Atualização pelo GitHub:** verificação automática ao abrir, pop-up com versão atual → nova, tamanho e novidades, além do botão do GitHub para verificar manualmente e **Agora não** para adiar.
- **Estatísticas da TV:** as reproduções da TV ficam em um outbox em disco até o celular confirmar a gravação e a tela de Estatísticas do celular atualiza ao vivo.
- **Conta dupla, YouTube Music, busca completa, downloads offline, player Media3, Discord RPC, temas Sistema/Claro/Escuro/AMOLED e TV de 32 bits com pareamento por código.**

### Build e assinatura
- `versionCode: 42`, compilado pelos workflows do GitHub Actions com a keystore estável dos Secrets.
- Assets desta release: `app.apk` (celular), `app-tv.apk` (Android TV, `armeabi-v7a`) e `SHA256SUMS.txt`.
- A release é uma pré-release por ser um beta; o app lê o `versionCode` da primeira linha do corpo e aceita a atualização.

### Observação
- Se o Android recusar a instalação por diferença de assinatura, desinstale a versão antiga uma única vez. A partir da `0.2.0-dev.4`, as versões publicadas usam a mesma keystore e instalam por cima, mantendo os dados da conta Myst.
