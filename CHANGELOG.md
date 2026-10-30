# Changelog

## 0.2.0-dev.9 — 29/10/2026

Aparência do celular na TV ao parear.

### Incluído
- O payload de pareamento agora leva o tema (Sistema/Claro/Escuro) e o Preto AMOLED do celular; a TV aplica a aparência assim que o pareamento termina, antes de abrir a navegação.
- A escolha fica só enquanto o pareamento existir: sobrevive a uma recriação de Activity/processo da TV, mas é apagada ao desconectar ou deslogar, voltando ao padrão do sistema.
- A TV continua sem gravar conta, playlists, capas ou `Mystaccount.json`; apenas a preferência temporária de aparência é mantida para evitar piscar o tema errado entre telas.

## 0.2.0-dev.8 — 29/10/2026

TV temporária e backup automático do `Mystaccount.json`.

### Incluído
- A TV deixou de guardar playlists, histórico e biblioteca em disco: o Room dela é em memória e o banco antigo é apagado na primeira abertura.
- A TV não grava mais `Mystaccount.json`, capas nem avatar. Ao desconectar do celular ou deslogar, as tabelas e os arquivos temporários são limpos e a tela volta ao código de 5 dígitos.
- Ao reiniciar o app da TV, a identidade mínima da sessão é recriada em memória para o pareamento continuar funcionando; as playlists voltam no próximo sync do celular.
- O celular salva o `Mystaccount.json` automaticamente em `/sdcard/MystMusic`. Sem a permissão especial de armazenamento, a cópia vai para `Download/MystMusic` via MediaStore, sem bloquear o uso.
- O app pede o acesso à pasta uma vez na abertura e permite conceder depois em **Configurações → Conta Myst**, que mostra o caminho real da cópia.
- A cópia automática é regravada a cada mudança da conta e antes de qualquer logout; a TV nunca participa desse caminho.
- Ao deslogar com uma TV pareada, o celular tenta avisá-la por até 3 segundos para limpar os dados temporários antes de remover o pareamento local.

## 0.2.0-dev.7 — 29/10/2026

`Mystaccount.json` completo.

### Incluído
- O arquivo passou a incluir as playlists do YouTube Music, o histórico do YouTube e as preferências de aparência (tema e AMOLED).
- A restauração religa esses dados à conta Myst; arquivos antigos continuam aceitos e campos ausentes não sobrescrevem nada.

## 0.2.0-dev.6 — 29/10/2026

Canal persistente celular↔TV.

### Incluído
- Depois do código de 5 dígitos, celular e TV guardam um identificador e um segredo para sincronizar e desconectar sem pedir o código de novo.
- Sincronização bidirecional de playlists, faixas, histórico e capas, com lápides para propagar remoções.
- Botão **Desconectar TV** nas Configurações do celular, com confirmação e erro visível se a TV estiver fora da rede.

## 0.2.0-dev.5 — 29/10/2026

Conta Myst portátil com `Mystaccount.json`.

### Incluído
- Ao criar uma conta Myst, o app grava uma cópia interna do `Mystaccount.json` e oferece salvar uma cópia externa em Downloads, Drive, cartão SD ou outra pasta escolhida pelo usuário.
- Login pelo arquivo: o botão **Entrar com Mystaccount.json** lê o arquivo, pede confirmação e restaura perfil, avatar, playlists, capas, histórico e afinidade de artistas. Se a conta já existir no aparelho, os dados são mesclados sem apagar playlists ou histórico locais.
- Atualização automática: um observador do Room regrava a cópia interna após mudanças em perfil, playlists, faixas, histórico e afinidade. A cópia externa também é atualizada quando o provedor de arquivos concede permissão persistente de escrita; sem essa permissão, a tela avisa que é preciso salvar uma nova cópia.
- Configurações → **Conta Myst** mostra o estado da sincronização e o botão **Salvar Mystaccount.json**. O flavor de TV oculta essa seção.
- As regras de backup do Android excluem a cópia interna e a URI externa, que são específicas do aparelho e podem ser regeneradas.
- Limites de exportação para manter o arquivo importável: 30.000 faixas no total, 4 MB de capas, 20.000 itens de histórico e 5.000 artistas; a importação aceita arquivos de até 32 MB.
- Cookies do YouTube, token do Discord e outras sessões **não** entram no arquivo. Depois de restaurar a conta Myst, o login do YouTube é solicitado novamente.

### Segurança
- O arquivo contém o salt e o hash PBKDF2 da senha e todos os dados da conta. Guarde-o como uma chave: quem tiver o arquivo pode entrar na conta Myst.

## 0.2.0-dev.4 — 29/10/2026

- Assinatura estável do APK com a keystore guardada apenas nos GitHub Secrets: atualizações instalam por cima e preservam a conta Myst, playlists, histórico e estatísticas. APKs anteriores ao dev.4 ainda exigem uma desinstalação única.
- Correção de crash na navegação e na persistência da conta Myst.

## 0.1.0 Alpha — 29/10/2026

Primeira alpha pública do MystMusic, para celular e Android TV 32 bits.

### Incluído
- Login com conta do YouTube pelo WebView, com captura de cookies e sessão InnerTube.
- Conta Myst local, com senha protegida por PBKDF2 e sessão criptografada no aparelho.
- Navegação com busca, Recentes, Em Alta e Recomendados calculados a partir do histórico local.
- Player com Media3/ExoPlayer, mini player, tela cheia, fila, foco de áudio e notificação de mídia.
- Resolução de stream pelo InnerTube com validação da URL e nova tentativa quando a URL expira.
- Playlists locais: criar, renomear, trocar capa, reordenar faixas por drag & drop, remover e tocar.
- Integração do Spotify por link, com mapeamento das faixas para o YouTube Music e aviso de itens não encontrados.
- Discord Rich Presence opt-in, com nome da música, artista, capa e barra de progresso; a presença é removida ao pausar ou trocar de faixa.
- Tema azul céu, navbar flutuante, animações de tela e de elementos, e estados de carregamento/vazio/erro nas telas.
- Flavor de Android TV com layout 10-foot, navegação por D-pad, pareamento por código de 5 dígitos e sincronização de conta.
- Tela de logs e diagnóstico, com cópia e compartilhamento do arquivo de log.

### Correções importantes
- Token do Discord sem as aspas do `localStorage`, validação REST e migração de sessões antigas.
- Capa do Discord registrada no media proxy (`mp:`) em vez de enviar uma URL crua, que deixava o card sem imagem.
- Card do Discord sem campos extras: “Ouvindo MystMusic”, música, artista, capa e progresso.
- Presença do Discord removida imediatamente ao pausar ou trocar de faixa.
- Proteção contra crashes em transições de fragmento, navegação durante a reprodução e carregamento de capas.
- Tratamento de erros de rede/HTTP do player com mensagem visível e botão “Tentar novamente”.

### Limitações conhecidas
- InnerTube e Discord RPC são integrações não oficiais e podem mudar sem aviso.
- O Discord RPC usa token de usuário e fica na zona cinzenta dos termos do Discord; use por sua conta e risco.
- Os APKs da alpha são assinados com a chave de debug do build e não são destinados a lojas de aplicativos.
- O pareamento celular ↔ TV exige a mesma rede Wi-Fi.
- A qualidade e a compatibilidade de áudio dependem dos codecs do aparelho/TV.
