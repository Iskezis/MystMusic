# MystMusic

App de música Android (Kotlin + XML) com YouTube Music (InnerTube), playlists do Spotify (importação por link), Discord Rich Presence e versão para Android TV 32 bits.

- **Nome do app:** MystMusic
- **Pacote:** `com.iskezis.myst`
- **Cor tema:** azul céu (gradientes simples, visual minimalista e moderno)
- **Idioma da UI:** Português (BR)
- **Build:** exclusivamente via GitHub Actions (repositório já configurado no `git remote`)
- **APKs finais:** `apks/app.apk` (celular) e `apks/app-tv.apk` (Android TV, armeabi-v7a)

## Escopo da versão 1.0

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
