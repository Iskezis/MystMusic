# AGENTS.md — Regras para o agente (DeepSeek 4.1 Flash / OpenCode)

Você está construindo o **MystMusic**. Leia o README.md, depois PROGRESS.md. Carregue a skill relevante ANTES de cada tarefa (ferramenta `skill`).

## Exigência de qualidade (inegociável)
- Entregue tudo do escopo, sem pular itens, sem TODOs, sem telas "em breve", sem código fictício/mock que finja funcionar.
- O app **não pode crashar**: toda chamada de rede/IO em try/catch com estado de erro visível ao usuário e botão "Tentar novamente".
- Cada tela tem 3 estados implementados: carregando (shimmer), vazio, erro.
- Nunca declare "pronto" sem build verde no GitHub Actions e APK em `apks/`.
- Se algo for ambíguo, escolha a opção mais simples que cumpre o README e registre a decisão no PROGRESS.md. Não pare para perguntar por detalhes menores.

## Identidade fixa
- App name: `MystMusic` (`strings.xml` → `app_name`)
- `applicationId` / `namespace`: `com.iskezis.myst`
- Linguagem: Kotlin. UI: **XML + ViewBinding** (sem Compose, exceto se uma skill disser o contrário).
- minSdk 21, targetSdk/compileSdk 34, JDK 17, AGP/Gradle estáveis compatíveis (verifique antes de fixar versões).
- Product flavors (dimensão `device`): `mobile` (abis `arm64-v8a`, `armeabi-v7a`) e `tv` (**apenas `armeabi-v7a`**, 32 bits).

## Design (resumo; detalhes em `android-music-ui-xml`)
- Azul céu: primária `#38BDF8`, escura `#0EA5E9`, clara `#BAE6FD`, fundo escuro `#0B1220`, superfície `#111A2E`.
- Gradientes simples de 2 cores (azul céu → azul mais profundo). Minimalista: muito espaço, poucas sombras, cantos de 16–28dp.
- Navbar flutuante: pílula arredondada, margem inferior 16dp, centralizada, 3 itens (Navegação, Playlists central destacado, Configurações).

## Regras de código
- Arquitetura: MVVM (ViewModel + StateFlow), Repository, Room, Coroutines, Media3.
- Nenhum segredo no repositório. Chaves (ex.: Spotify) via `local.properties`/GitHub Secrets e `BuildConfig`; sem chave, use o fallback descrito na skill.
- Sem dependências abandonadas. Verifique a versão no Maven antes de adicionar.
- Strings em `strings.xml`, cores em `colors.xml`, dimens em `dimens.xml`. Sem valores soltos em layouts.
- Acessibilidade: `contentDescription` em ícones, alvos de toque ≥ 48dp.

## Fluxo de build (obrigatório) — skill `github-actions-build`
1. Não rode Gradle localmente.
2. `git add -A && git commit && git push` (use o remote existente; **não altere o remote**).
3. Acompanhe o run com `gh run watch`. Se falhar: `gh run view --log-failed`, corrija a causa raiz, commit, push. Repita até ficar verde.
4. Baixe os artefatos e copie: celular → `apks/app.apk`, TV → `apks/app-tv.apk` (sobrescreva). Commit e push do APK.
5. Registre no PROGRESS.md o número do run e o resultado.

## Ordem de trabalho (fases)
1. Setup do projeto + workflow + primeiro build verde (APK vazio com nome/pacote corretos).
2. Tema, navbar flutuante, esqueleto das telas (skill `android-music-ui-xml`).
3. Contas: Myst local + YouTube (skills `myst-local-account`, `youtube-music-innertube`).
4. Navegação: busca, recentes, em alta, recomendados (skills `recommendations-local`, `youtube-music-innertube`).
5. Player Media3 + mini player + notificação (skill `media3-playback`).
6. Playlists + integração Spotify (skill `spotify-playlist-import`).
7. Discord RPC (skill `discord-rpc`).
8. Animações de tela e de elementos (skills `android-screen-animations`, `android-element-animations`).
9. Flavor TV + pareamento por código (skills `android-tv-adaptation`, `tv-pairing-code`).
10. Revisão final com a checklist abaixo, build final, APKs em `apks/`.

## Checklist de aceitação final
- [ ] Nome "MystMusic" e pacote `com.iskezis.myst` em ambos os flavors
- [ ] Login YouTube e conta Myst funcionando; deslogar funciona
- [ ] Navegação: busca + Recentes + Em Alta + Recomendados
- [ ] Navbar flutuante com 3 itens, Playlists ao centro
- [ ] Playlists: criar, nome, imagem, reordenar, remover
- [ ] Integrar do Spotify: pop-up com campo de texto, importa, trata link inválido
- [ ] Player + notificação + Discord RPC
- [ ] Animações aplicadas (ver skills)
- [ ] TV: D-pad, pareamento 5 dígitos, armeabi-v7a
- [ ] Build verde; `apks/app.apk` e `apks/app-tv.apk` atualizados
- [ ] PROGRESS.md completo

## Notas de risco (registre, não ignore)
- InnerTube e login por cookies são APIs não oficiais; podem mudar. Isole em uma camada (`data/youtube`) para facilitar manutenção.
- Discord RPC no Android exige token de usuário (zona cinzenta dos termos do Discord). Deixe opt-in com aviso claro.
- "Localhost" entre celular e TV só funciona na mesma rede Wi-Fi: implementado como servidor HTTP local + descoberta NSD (skill `tv-pairing-code`).
- Discord: a aba Configurações deve ter só "Deslogar", mas o RPC precisa de login Discord. Decisão: um único item extra "Discord" opt-in, discreto, abaixo de "Deslogar".
