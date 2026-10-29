---
name: github-self-update
description: Verificação de atualização no GitHub Releases ao abrir o app, pop-up "Atualizar / Agora não", download com progresso e instalação do APK (celular e TV). Use na fase 18.
---
# Atualização pelo GitHub

## Config
`BuildConfig.GITHUB_REPO` (`owner/repo`): obter no build do Gradle a partir de `GITHUB_REPOSITORY` (CI) ou de `git remote get-url origin` (fallback), sem token. Repositório precisa ser **público** (API sem autenticação). Se privado: não embutir token no APK; mostrar nada e registrar no PROGRESS.

## Verificação
- Quando: ao entrar no app **já logado** (após a tela de entrada completa, na Navegação) e na TV após o pareamento. Também ao voltar do background após > 6h. Não bloquear a UI: coroutine em `Dispatchers.IO`, timeout 10s, falha silenciosa (log), nunca crash.
- `GET https://api.github.com/repos/{repo}/releases/latest` (inclui pré-releases? `latest` ignora pré-release: como as alphas são `prerelease`, usar `GET /releases?per_page=5` e pegar a primeira não-draft). Cabeçalho `Accept: application/vnd.github+json`, `User-Agent`, `If-None-Match` com ETag (60 req/h sem token).
- Versão remota: `versionCode` lido da 1ª linha do corpo (`versionCode: N`); fallback: comparar `tag_name` com SemVer. Atualização disponível se `remoteCode > BuildConfig.VERSION_CODE`.
- Asset: `app.apk` (flavor mobile) ou `app-tv.apk` (flavor tv); URL `browser_download_url`; tamanho `size`; checksum em `SHA256SUMS.txt` se existir.

## Pop-up (MaterialAlertDialog, tema do app)
Título "Nova versão disponível", subtítulo `vX → vY` (atual → nova), notas da Release (rolável, máx. ~12 linhas; Markdown simples), tamanho do download.
Botões: **Atualizar** (primário, gradiente azul céu) e **Agora não** (continua usando a versão atual). Aparece **sempre que o app abre** enquanto houver versão nova (sem botão de pular nesta versão). Cancelável por voltar = "Agora não". Na TV: botões focáveis, foco inicial em "Atualizar".

## Download e instalação
1. "Atualizar" → diálogo/barra com progresso (OkHttp stream → `cacheDir/updates/app-<code>.apk`; `.part` + rename; verificar SHA-256 se disponível; apagar ao terminar/erro). Botão Cancelar. Mostrar erro com "Tentar novamente".
2. Instalar: `FileProvider` (`<cache-path>` em `file_paths.xml`, **não exportado**), `Intent.ACTION_VIEW`/`PackageInstaller` com `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`.
3. Manifest: `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>`. Se `!packageManager.canRequestPackageInstalls()` → explicar e abrir `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` (`package:com.iskezis.myst`); ao voltar, retomar a instalação.
4. TV: o instalador do sistema pode ser limitado; se falhar, mostrar instrução de instalar via `apks/app-tv.apk`/downloader.
5. Falha de assinatura (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`): explicar "desinstale e instale de novo" (ver `release-signing-versioning`).

## Regras
- Sem crash offline/sem GitHub/rate-limit (403/429): ignorar silenciosamente.
- Nunca baixar de URL que não seja do `github.com`/`objects.githubusercontent.com` da própria repo; exigir HTTPS.
- Não logar URLs com token; não pedir permissões extras além da de instalar.
- Teste: build A (versionCode 10) instalada; publicar Release B (versionCode 11); abrir A → pop-up; "Agora não" segue normal; "Atualizar" instala B sobre A e mantém os dados da conta Myst.
