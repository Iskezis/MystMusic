# MystMusic

App de música Android (Kotlin + XML) com YouTube Music (InnerTube), playlists do Spotify (importação por link), Discord Rich Presence e versão para Android TV 32 bits.

- **Nome do app:** MystMusic
- **Pacote:** `com.iskezis.myst`
- **Versão atual:** 0.1.0 Alpha (versionCode 9)
- **Status:** alpha de testes; integrações não oficiais do YouTube Music e do Discord
- **Cor tema:** azul céu (gradientes simples, visual minimalista e moderno)
- **Idioma da UI:** Português (BR)
- **Build:** exclusivamente via GitHub Actions (repositório já configurado no `git remote`)
- **APKs finais:** `apks/app.apk` (celular) e `apks/app-tv.apk` (Android TV, armeabi-v7a)

## Escopo da versão 0.1.0 Alpha

### Entrada (Login)
1. Login com conta do YouTube (WebView do Google → cookies → sessão InnerTube).
2. Criar conta / login Myst (conta local do app, armazenada no aparelho, senha com PBKDF2).
3. Na TV: ao abrir, exige sincronização por código de 5 dígitos exibido no celular.

### Navegação (tela inicial)
- Barra de busca no topo.
- Seção 1: **Recentes** (o que o usuário acabou de ouvir).
- Seção 2 (rolando para baixo): **Em Alta** conforme o gosto do usuário.
- Seção 3: **Recomendados** conforme o gosto do usuário.
- Navbar flutuante, centralizada embaixo, bordas arredondadas: **Navegação | Playlists (centro, destaque) | Configurações**.

### Playlists
- Criar, renomear, trocar imagem, reordenar músicas (drag & drop), remover.
- Botão **"Integrar do Spotify"**: pop-up com campo de texto para colar o link da playlist → importa e mapeia as faixas para o YouTube Music.

### Configurações
- Apenas **Deslogar** (YouTube ou Myst). (Exceção documentada no AGENTS.md: item de Discord.)

### Player
- Mini player + tela cheia, notificação de mídia, Discord RPC com a música atual.

### TV (flavor `tv`)
- Layouts para 10-foot UI, navegação por D-pad, tela de pareamento com código de 5 dígitos, `minSdk 21`, ABI `armeabi-v7a`.

## Instalação (alpha)
1. Baixe `apks/app.apk` no celular ou `apks/app-tv.apk` na Android TV.
2. Permita a instalação de aplicativos de fontes desconhecidas para o navegador/gerenciador de arquivos usado.
3. Se uma versão anterior tiver sido assinada com outra chave, desinstale-a antes de instalar esta alpha.
4. Na TV, abra o app e use o código de 5 dígitos exibido para parear com o celular na mesma rede Wi-Fi.

## Limitações conhecidas
- O YouTube Music é acessado por InnerTube (API não oficial) e o Discord RPC usa token de usuário. Essas integrações podem mudar ou deixar de funcionar sem aviso; o Discord RPC é opt-in e tem aviso de risco.
- Os APKs desta alpha são assinados com a chave de debug do build. Não são adequados para publicação em loja e uma atualização futura com outra assinatura pode exigir desinstalar a versão anterior.
- A importação do Spotify mapeia as faixas por título, artista e duração; faixas ambíguas podem ser ignoradas com aviso na tela.
- O pareamento celular ↔ TV exige que os dois aparelhos estejam na mesma rede Wi-Fi.
- A reprodução depende dos codecs AAC/Opus disponíveis no aparelho ou na TV.

## Registro de mudanças
Consulte [CHANGELOG.md](CHANGELOG.md) para as notas da versão 0.1.0 Alpha.

## Como o agente deve trabalhar
Leia nesta ordem: `AGENTS.md` → `PROGRESS.md` → skills em `.opencode/skills/`.
Atualize o `PROGRESS.md` a cada etapa concluída. Nunca compile localmente: faça push e use o GitHub Actions (skill `github-actions-build`).

## Estrutura esperada
```
app/src/main/        código comum (Kotlin, XML, drawables)
app/src/mobile/      recursos/manifest do celular
app/src/tv/          recursos/manifest/layouts da TV
.github/workflows/build.yml
apks/app.apk, apks/app-tv.apk
.opencode/skills/*
```
