versionCode: 50
## MystMusic 0.2.5 Beta 6 — capas animadas

O player de tela cheia agora pode exibir a **capa animada** da música, em loop e sem som, quando o artista disponibiliza uma arte em movimento. A capa estática continua sendo a base: se o vídeo não existir, falhar ou a rede não permitir, nada muda na reprodução. Esta é a fase 23 do roadmap.

### Capas animadas
- **Provedor funcional sem chave:** a busca pública do iTunes localiza o álbum e a página pública da Apple Music fornece a arte em movimento (HLS). Nenhuma conta, cookie ou chave é usada.
- **Correspondência conservadora:** título, artista, álbum e duração (tolerância de 8 s) precisam bater. A versão exata do título é priorizada quando o álbum não vem do YouTube Music. Uma capa errada nunca é mostrada por engano.
- **Player dedicado:** um segundo `ExoPlayer` exclusivo para o vídeo, sempre **mudo**, em **loop**, limitado a **720p**, usando cache de mídia de até 60 MB. O player de áudio, a MediaSession, a notificação e o Discord não são afetados.
- **Entrada suave:** a capa estática aparece primeiro; o vídeo só entra com crossfade de 300 ms depois do **primeiro frame** renderizado. Qualquer erro esconde o vídeo e mantém a capa.
- **Sincronização:** o vídeo acompanha play/pause, pausa ao abrir a fila, ao sair do player e com a tela desligada. A busca tem debounce de **600 ms** para não baixar capas de faixas puladas rapidamente.
- **Uso de dados controlado:** só busca/reproduz em rede **não medida** (Wi-Fi), com bateria normal e sem Economia de dados. Na TV e em aparelhos de baixa memória o recurso fica desligado e o interruptor não aparece.
- **Cache Room:** nova tabela `animated_cover` com migração **6 → 7 não destrutiva**. Resultado positivo vale 30 dias, negativo vale 7 dias e uma URL que falha na reprodução é invalidada para revalidar na próxima vez.
- **Aparência:** novo interruptor **Capas animadas** em Configurações → Aparência, no celular. A preferência é do aparelho e não altera a conta Myst nem a TV.

### Compatibilidade
- Atualiza por cima da Beta 5 (`versionCode 49`) e anteriores com a mesma assinatura, mantendo conta Myst, conta YouTube, playlists, histórico, downloads, estatísticas e pareamento.
- A migração do Room apenas cria a tabela de cache; nenhum dado do usuário é apagado.
- A TV continua usando `app-tv.apk` (`armeabi-v7a`, 32 bits) e segue com a capa estática.
- Esta Beta 6 é uma **pré-release**: aparece no canal **Estáveis e betas** e não é oferecida no canal **Somente estáveis**.

### Como testar
1. Conecte o celular ao Wi-Fi, abra o player de uma música que tenha arte em movimento na Apple Music (por exemplo, faixas de *1989 (Taylor's Version)*) e confirme que a capa começa a se mover depois da capa estática.
2. Confira que o vídeo está mudo, entra em loop e acompanha play/pause, abertura da fila, saída do player e bloqueio/desbloqueio da tela.
3. Troque de faixa rapidamente e confirme que nada trava; a busca só acontece depois de 600 ms e o áudio nunca para.
4. Teste uma música sem arte em movimento, uma faixa sem álbum e uma falha de rede: a capa estática deve continuar normalmente, sem mensagem de erro.
5. Desligue e ligue **Configurações → Aparência → Capas animadas** e confirme que o vídeo para/volta sem reiniciar a faixa.
6. Ative a Economia de bateria ou a Economia de dados e confirme que nenhum vídeo é baixado; volte ao normal e abra o player para o recurso voltar.
7. Na TV, confirme que a capa continua estática e que a opção **Capas animadas** não aparece em Aparência.

### Detalhes técnicos
- O provedor usa timeout total de 5,5 s por faixa, User-Agent de navegador, apenas URLs HTTPS e nenhuma credencial embutida.
- O cache de mídia usa `SimpleCache` com evicção LRU; o cache de resultados fica no Room e é podado após 30 dias.
- O schema 7 do Room foi exportado pelo CI e versionado em `app/schemas/`.
- Todo texto novo foi adicionado em `values/`, `values-en/` e `values-es/`.
