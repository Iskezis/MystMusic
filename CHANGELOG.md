# Changelog

## 0.2.0-dev.19 — 30/10/2026

Pareamento e sincronização com a TV funcionando com o app em segundo plano ou fechado.

### Corrigido
- **Confirmação do código com o app fora da tela:** a sessão que responde ao `/confirm` da TV saiu da `PhonePairingActivity` e passou a viver na `Application`. Fechar a tela, voltar para a TV ou remover o MystMusic dos recentes não fecha mais o servidor temporário nem apaga o código de 5 dígitos. O código continua válido por 2 minutos e a TV consegue concluir o pareamento nesse intervalo.
- **Sincronização TV → celular em segundo plano:** um serviço em primeiro plano (`PairingSyncService`, tipo `connectedDevice`) mantém o servidor HTTP do celular e o ciclo de sincronização ativos depois que a Activity sai da tela. O histórico, as estatísticas e as playlists temporárias da TV chegam ao celular mesmo com o app em segundo plano ou removido dos recentes.
- **Retomada automática:** o serviço usa `START_STICKY` e um receptor de `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` para voltar depois de o Android encerrar o processo, reiniciar o aparelho ou instalar uma atualização. Ao reabrir o app, o canal e o ciclo também são garantidos.
- **Retentativa da TV:** quando o celular está temporariamente inacessível, a TV não descarta o retrato. Ela tenta o `/push` e o aviso `/notify` de novo com espera crescente (5 s até 60 s) enquanto o pareamento existir.
- **Notificação do canal:** uma notificação discreta e sem som mostra "Pareando com a TV" durante o código e "MystMusic conectado à TV" quando o pareamento está ativo. Tocar nela abre o app.
- **Permissões do Android 14:** foram declaradas `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE` e `RECEIVE_BOOT_COMPLETED` para o serviço de rede local funcionar nas versões novas do Android.

### Notas
- O serviço é encerrado ao cancelar o código, quando ele expira, ao desconectar a TV ou ao deslogar. Ele não aparece na TV.
- Continua valendo a limitação de rede: celular e TV precisam estar na mesma rede Wi-Fi/LAN. Forçar a parada do app nas configurações do Android encerra qualquer serviço e interrompe o canal até o app ser aberto de novo.

## 0.2.0-dev.18 — 30/10/2026

Estatísticas interativas, fotos de artistas e navegação por D-pad no player de TV.

### Adicionado
- **Estatísticas clicáveis:** as músicas do Top Músicas agora iniciam a reprodução na hora, a partir daquela posição da lista. As linhas de artistas abrem o perfil do artista e as de álbuns abrem a tela do álbum, com indicador de carregamento enquanto o identificador é resolvido.
- **Fotos de perfil nos artistas:** o Top Artistas passa a buscar a foto oficial de cada artista no YouTube Music e exibi-la em recorte circular. As fotos são atualizadas no lugar, sem reconstruir a lista nem perder o foco da TV, e ficam em cache enquanto a tela de Estatísticas estiver aberta.
- **Fallback seguro:** se a busca do artista/álbum falhar ou não encontrar o perfil, o app abre a busca pelo nome em vez de deixar a linha sem resposta. A thumbnail da faixa continua aparecendo enquanto a foto oficial não chega.
- **Foco visível no player de TV:** todos os controles interativos (voltar, adicionar à playlist, artista, álbum, barra de progresso, aleatório, anterior, play/pause, próxima, repetir e botões de erro) ganharam anel/borda de foco, mudança de cor do ícone/texto e, quando não conflita com a animação do play, leve escala.
- **Ordem de foco do D-pad:** ao abrir o player na TV, o foco começa no play/pause. As ligações entre topo, artista, álbum, seek, controles e botões de erro são explícitas, inclusive quando o álbum não existe ou um controle está desabilitado.

### Corrigido
- **Tempo nas Estatísticas com a precisão certa:** durações abaixo de 1 minuto aparecem só em segundos (ex.: `42 s`); entre 1 minuto e 1 hora aparecem em minutos e segundos (ex.: `3 min 12 s`); a partir de 1 hora aparecem em horas e minutos (ex.: `2 h 7 min`), sem mostrar segundos nesse último caso. A regra vale para o tempo total, o top artistas e as descrições acessíveis dos gráficos.
- **Linhas de estatística sem foco:** as linhas agora usam o mesmo fundo de foco das faixas, com a borda de foco tendo prioridade sobre o estado "selecionado" para nunca desaparecer no D-pad.
- **Player de TV sem estado de erro consistente:** ao faltar o controlador, os controles de reprodução ficam desabilitados e o foco inicial vai para "Tentar novamente", em vez de um play que não faria nada.
- **Acessibilidade:** as linhas clicáveis de Estatísticas anunciam a ação (reproduzir, abrir artista ou abrir álbum) além do rank, nome e contagem.

## 0.2.0-dev.17 — 30/10/2026

Correções de persistência do Recentes e do cancelamento de pareamento.

### Corrigido
- **Recentes após atualizar o app:** a sincronização do histórico do YouTube Music reescrevia o horário de todas as faixas a cada consulta. Como o cache vence a cada 15 minutos, o Recentes inteiro "pulava" para o topo depois de uma atualização. Agora o horário já conhecido de cada `videoId` é preservado e só as faixas novas recebem uma âncora; a ordem local (histórico e estatísticas) continua intacta no Room.
- **Estatísticas e dados da conta:** reforço de que `play_events`, histórico local, gostos, playlists e preferências são gravados por conta Myst e sobrevivem a atualizações do APK (assinatura estável + migrações Room não destrutivas). O `Mystaccount.json` continua sendo a cópia portátil.
- **Desconectar TV depois de cancelar na TV:** o celular esconde "Desconectar TV" imediatamente quando recebe o aviso de despareamento. Se o aviso não chegar (app fechado ou rede fora do ar), ao abrir Configurações o celular consulta o `/hello` da TV — inclusive a tela de código, que responde com o id do último par e `paired=false` — e remove o pareamento. Pareamentos antigos ganharam a lápide `tv_last_pair_id` no momento em que são desfeitos.
- **Entrega do aviso de despareamento:** os tempos de espera do retrato final e do aviso TV→celular foram ampliados para redes locais lentas, reduzindo o caso em que a TV desfaz o par mas o celular não fica sabendo.

## 0.2.0-dev.16 — 30/10/2026

Correção das capas dentro dos álbuns.

### Corrigido
- **Capas das faixas no álbum:** as linhas de um álbum no YouTube Music não trazem miniatura própria. O app caía na thumbnail do vídeo do YouTube (16:9, fotograma do clipe), diferente do YouTube Music, que mostra a capa quadrada do álbum em todas as faixas. Agora todas as músicas abertas por um álbum usam a capa oficial do álbum, inclusive as páginas seguintes da lista, o player, o mini player, a fila e as playlists Myst que recebem essas faixas.

## 0.2.0-dev.15 — 30/10/2026

Busca ampliada e telas de artista, álbum e playlist.

### Adicionado
- **Busca completa:** chips Tudo / Músicas / Artistas / Álbuns / Playlists, debounce de 350 ms, resultados mistos com layout próprio para faixa, artista, álbum e playlist, paginação por continuação e estados de carregando, vazio e erro.
- **Perfil do artista:** cabeçalho com foto, nome e ouvintes mensais; Tocar/Embaralhar; músicas populares com "Ver todas"; carrosséis de álbuns, singles/EPs, playlists e artistas semelhantes; seção Sobre.
- **Álbum:** capa grande, título, artista, ano, contagem, duração, lista de faixas e Tocar/Embaralhar.
- **Playlist pública:** cabeçalho, lista paginada, Tocar/Embaralhar e **Copiar para Myst**.
- **Navegação:** artista, álbum e playlist abrem por Navigation Component com argumentos, inclusive a partir do player e do menu de faixa (CLEAR_TOP + SINGLE_TOP).
- **Menu de faixa:** Ir para o artista e Ir para o álbum, com fallback para a busca quando o ID não está disponível.
- **Player:** nome do artista e do álbum clicáveis.
- **Modelo:** IDs de artista e álbum preservados em `Track`, na fila do Media3 e nos argumentos de faixa; busca com cache de página de 5 minutos.

## 0.2.0-dev.14 — 30/10/2026

Correção do Discord ao desconectar a TV.

### Corrigido
- **Discord RPC na TV:** ao desconectar o celular da TV, o app agora envia uma presença vazia ao gateway antes de fechar o WebSocket e deixa o OkHttp concluir o fechamento sem cancelar o socket na hora. Antes, o card com a última música podia continuar aparecendo no perfil do Discord porque o frame de presença/close era abortado no meio do envio. O mesmo vale para **Desconectar Discord** nas Configurações.

## 0.2.0-dev.13 — 30/10/2026

Correção emergencial de reprodução.

### Corrigido
- **Áudio:** o VISIONOS 0.1 retornava HTTP 400 quando o visitorData da sessão não servia para o cliente anônimo, e o fallback ANDROID_VR devolvia URLs que passam no teste de 2 bytes mas recusam blocos grandes após o primeiro MiB (403). Agora a validação testa um bloco real de 256 KiB a partir de 1 MiB, rejeita essas URLs, renova o visitorData anônimo e repete a resolução no VISIONOS 1.02 (contexto, modelo e User-Agent atuais), cuja URL aceita Range aberto e intervalos arbitrários. Reprodução e seek restaurados.

## 0.2.0-dev.12 — 30/10/2026

Correções de reprodução, estatísticas, conta e pareamento.

### Corrigido
- **Resolução de áudio:** quando o YouTube responde `LOGIN_REQUIRED`/verificação de bot com o `visitorData` vencido, o app renova o visitante uma vez e repete a resolução nos clientes; a home do YouTube serve de reserva quando a do YouTube Music não entrega o visitante.
- **Recentes do YouTube Music:** depois dos 10 s ouvidos, o app envia a telemetria de reprodução (`videostatsPlaybackUrl`/`videostatsWatchtimeUrl`) com os cookies e o `SAPISIDHASH` da conta, usando as URLs emitidas pelo `WEB_REMIX`. Assim a faixa tocada no MystMusic também aparece no histórico do YouTube Music.
- **Estatísticas aos 10 s:** o evento de reprodução é criado assim que a faixa passa dos 10 s e a mesma linha é atualizada ao pular/finalizar. Trocar de música depois do limiar mantém o tempo exato ouvido e não cria uma segunda reprodução. Retratos da TV e importações do `Mystaccount.json` atualizam o maior tempo em vez de descartar a linha já existente.
- **`Mystaccount.json` v4:** a conexão do Discord (token e nome) agora é salva no arquivo e restaurada na importação, reconectando o Discord na hora. A UI avisa que o arquivo também é uma credencial do Discord; arquivos v1–v3 continuam aceitos.
- **Discord em tempo real:** conectar/desconectar ou atualizar o nome do Discord no celular regrava o `Mystaccount.json` e envia a conexão à TV imediatamente, sem esperar o ciclo periódico.
- **TV → celular:** a TV envia o retrato de histórico, estatísticas, playlists, afinidades e lápides ao endpoint autenticado `/push` do celular; o merge acontece antes da resposta e a TV só apaga os dados temporários depois do ack. A desconexão leva o último retrato no próprio `/disconnect`, então o celular grava antes de limpar a sessão. Remoções de faixas de uma playlist também viajam por lápide (`track:playlist:videoId`), inclusive com aviso de readição para não ressuscitar a faixa.
- **Desconexão:** o celular remove o pareamento local mesmo quando a TV está desligada/fora da rede e pode parear de novo na hora; a TV tem o botão **Desconectar do celular** na tela inicial e limpa a geração antiga sem apagar um pareamento novo.
- **AMOLED e NSD:** o overlay AMOLED existe também na configuração clara e é reaplicado a cada sync, inclusive quando as preferências já eram iguais; o NSD tenta de novo após `FAILURE_ALREADY_ACTIVE` e desregistra registros pendentes antes de um novo pareamento.

## 0.2.0-dev.11 — 29/10/2026

Correções de sincronização, desconexão e Estatísticas.

### Corrigido
- Sincronização bidirecional em tempo real entre celular e TV: o canal TV→celular avisa o celular quando o histórico, as estatísticas ou uma playlist mudam na TV, e o celular envia as novidades ao Room temporário da TV. O primeiro sync logo após o pareamento tenta de novo em 5 s se a TV ainda estiver gravando o payload.
- **Recentes** e a biblioteca do YouTube Music agora fazem parte do pareamento e das sincronizações seguintes: playlists, faixas carregadas, histórico e estado de atualização viajam junto com os dados da conta Myst. Sem isso a TV só recebia o histórico local e ficava sem a parte do YouTube.
- Aparência e Discord são reaplicados a cada sync. O **Preto AMOLED** chega ativo na TV quando o telefone está efetivamente no tema escuro, mesmo que a preferência seja "Sistema"; mudanças em Aparência e Discord disparam um sync imediato.
- Desconexão mais confiável: portas fixas 8765 (TV) e 8766 (celular) com fallback, reconexão pelo último endereço ou NSD, botão **Desconectar do celular** nas Configurações da TV e opção de remover o pareamento só do celular quando a TV estiver inalcançável.
- Estatísticas contam a música depois dos **10 primeiros segundos ouvidos**, inclusive quando a faixa é pulada no meio; o tempo gravado é o trecho exato escutado. O corte antigo de 30 s/50% foi removido de todas as agregações e do histórico.

## 0.2.0-dev.10 — 29/10/2026

Fase 14: conta Myst persistente e Estatísticas.

### Incluído
- Tela **Estatísticas** aberta pelo ícone de gráfico no cabeçalho da Navegação, com filtros Semana, Mês, Ano e Sempre.
- Tempo ouvido (com comparação ao período anterior), reproduções, top músicas, top artistas, top álbuns, gráfico de 24 horas, atividade por dia e sequência atual/recorde.
- Eventos de reprodução gravados na conta Myst em `play_events`, com o tempo realmente ouvido (pausas, buffering e saltos não contam) e corte de 30 s ou 50% da faixa para contar como reprodução.
- Os eventos entram no `Mystaccount.json`, no backup automático e na sincronização com a TV; trocar de conta Myst troca as estatísticas.
- Migração Room 4→5 não destrutiva: `playlist_tracks` ganhou `userId`/FK, `history` e `artist_affinity` ganharam FK para `users`, e as tabelas do YouTube ganharam o álbum.
- Metadados de álbum passaram a ser lidos do InnerTube e guardados em playlists, histórico e recomendações.
- `exportSchema` ativado; o schema do Room é publicado como artefato em cada build.

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
