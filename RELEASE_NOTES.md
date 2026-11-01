versionCode: 44
## MystMusic 0.1.8 Beta 5

Correção das estatísticas da TV no celular. A música ouvida na TV agora tem um canal próprio para as Estatísticas, sem depender do envio de playlists, histórico ou cache do YouTube.

### Correções desta build
- **Canal exclusivo de estatísticas:** a TV envia os eventos de reprodução para o novo endpoint `/stats` do celular, separado do retrato completo. Se o merge de playlists/cache falhar ou demorar, a reprodução da TV ainda entra nas Estatísticas.
- **Outbox próprio:** os eventos ficam em `filesDir/pairing/tv_pending_stats.json` antes da rede e só são apagados depois do `ack` do celular. Se o celular estiver dormindo, fora da rede ou com o app fechado, a TV tenta de novo com espera crescente e também restaura o outbox na próxima abertura/pareamento.
- **Entrega imediata:** o `PlaybackService` da TV avisa o sincronizador assim que o evento dos 10 s é gravado e quando o tempo final é atualizado. O retry do canal de estatísticas roda antes do retrato completo.
- **Celular puxa ao abrir as Estatísticas:** a tela pede uma sincronização imediata ao abrir e ao atualizar, além de continuar recarregando ao vivo pelo observer de `play_events`.
- **Sem perda por limpeza indevida:** o retrato completo só apaga o outbox de estatísticas quando todos os eventos pendentes estão incluídos no envio. Uma reprodução criada durante a requisição continua guardada.
- **Release completa:** a Beta 5 é publicada como Release normal no GitHub, não como pré-release.

### Compatibilidade
- Atualiza por cima da Beta 4 (`versionCode 43`) e anteriores com a mesma assinatura, mantendo conta Myst, playlists, histórico e estatísticas.
- Atualize **celular e TV** para a Beta 5: o canal `/stats` é novo nos dois lados.
- Nenhuma migração do Room foi necessária nesta build.
- A TV continua usando o APK `app-tv.apk` (`armeabi-v7a`, 32 bits).

### Build e assinatura
- `versionCode: 44`, `versionName "0.1.8 Beta 5"`, compilado pelos workflows do GitHub Actions com a keystore estável dos Secrets.
- Assets desta release: `app.apk` (celular), `app-tv.apk` (Android TV) e `SHA256SUMS.txt`.
- O app lê o `versionCode` da primeira linha deste arquivo para o pop-up de atualização.

### Como testar
1. Instale a Beta 5 no celular e na TV e confirme que o pareamento continua ativo.
2. Na TV, toque uma música e deixe passar dos primeiros 10 segundos.
3. Abra **Estatísticas** no celular e confira o tempo/reproduções da faixa. A tela puxa a TV ao abrir e recarrega sozinha quando o evento chega.
4. Repita com o celular bloqueado ou com o app em segundo plano; ao abrir as Estatísticas, o evento pendente deve aparecer.
5. Se o celular estiver desligado durante a reprodução, ligue-o depois, abra o MystMusic e confira que a TV reenvia o outbox sem duplicar a reprodução.
