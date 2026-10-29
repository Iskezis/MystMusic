---
name: release-signing-versioning
description: Assinatura estável do APK (keystore em GitHub Secrets), versionamento e workflow que publica GitHub Releases com app.apk e app-tv.apk. Necessário para a atualização in-app (fase 18).
---
# Assinatura e releases

## Por que
O Android só instala um APK por cima de outro se a **assinatura for a mesma**. A chave de debug do runner muda → atualização falha. É preciso uma keystore fixa.

## Keystore (uma vez, manual pelo usuário)
O agente **não gera nem commita** segredos reais. Documente no README os passos para o usuário:
```bash
keytool -genkeypair -v -keystore myst-release.jks -alias myst -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 myst-release.jks   # colar em GitHub → Settings → Secrets
```
Secrets: `MYST_KEYSTORE_B64`, `MYST_KEYSTORE_PASSWORD`, `MYST_KEY_ALIAS`, `MYST_KEY_PASSWORD`.
Se os secrets não existirem, o workflow cai para a assinatura de debug e avisa nos logs (build continua verde). Avisar no PROGRESS/CHANGELOG que a primeira troca de chave exige **reinstalar** o app uma vez.

## Gradle
`signingConfigs { create("release") { … lê de env/`-P` … } }`, usada pelos dois flavors. Gerar o APK **release assinado** (`assembleMobileRelease`, `assembleTvRelease`), com `isMinifyEnabled=false` (evita quebrar reflexão do InnerTube/Room) ou regras ProGuard testadas.

## Versionamento
- `versionName`: `0.2.0-dev.N` durante as fases, `0.2.0 Alpha` no fim. `versionCode` sempre crescente (≥ 10).
- Tag/Release: `v0.2.0-alpha` (SemVer; pré-release marcada como `prerelease`).
- Corpo da Release (usado como notas no pop-up) com a linha `versionCode: <n>` na primeira linha, seguida das mudanças.

## `.github/workflows/release.yml` (modelo)
```yaml
name: Release
on:
  push:
    tags: [ 'v*' ]
  workflow_dispatch:
permissions:
  contents: write
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - name: Keystore
        env: { KS: ${{ secrets.MYST_KEYSTORE_B64 }} }
        run: |
          if [ -n "$KS" ]; then echo "$KS" | base64 -d > $RUNNER_TEMP/myst.jks; echo "MYST_KS=$RUNNER_TEMP/myst.jks" >> $GITHUB_ENV; else echo "::warning::sem keystore, usando debug"; fi
      - run: chmod +x ./gradlew && ./gradlew assembleMobileRelease assembleTvRelease --stacktrace
        env:
          MYST_KEYSTORE_PASSWORD: ${{ secrets.MYST_KEYSTORE_PASSWORD }}
          MYST_KEY_ALIAS: ${{ secrets.MYST_KEY_ALIAS }}
          MYST_KEY_PASSWORD: ${{ secrets.MYST_KEY_PASSWORD }}
      - name: Renomear e publicar
        env: { GH_TOKEN: ${{ github.token }} }
        run: |
          cp app/build/outputs/apk/mobile/release/*.apk app.apk
          cp app/build/outputs/apk/tv/release/*.apk app-tv.apk
          sha256sum app.apk app-tv.apk > SHA256SUMS.txt
          gh release create "${GITHUB_REF_NAME}" app.apk app-tv.apk SHA256SUMS.txt \
            --title "MystMusic ${GITHUB_REF_NAME}" --notes-file RELEASE_NOTES.md --prerelease
```
Ajuste caminhos de saída (se o APK release sair como `*-unsigned.apk`, a signingConfig não foi aplicada). Os nomes dos assets **precisam** ser exatamente `app.apk` e `app-tv.apk` (o app procura por eles).

## Fluxo do agente
1. Atualizar `versionCode/versionName` e `RELEASE_NOTES.md` (primeira linha `versionCode: N`).
2. Commit/push; `git tag v0.2.0-alpha && git push --tags` (o workflow de release roda). Acompanhar com `gh run watch`.
3. Conferir `gh release view` com os 2 assets. Também manter a cópia em `apks/app.apk` e `apks/app-tv.apk` (fluxo de `github-actions-build`).
