# Changelog

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
