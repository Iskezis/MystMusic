---
name: github-actions-build
description: Compilar o MystMusic exclusivamente via GitHub Actions, corrigir erros de build em loop e mover os APKs para apks/app.apk e apks/app-tv.apk. Use após qualquer mudança de código.
---
# Build via GitHub Actions

## Regras
- Nunca rode Gradle localmente. Nunca troque o `git remote` (já configurado).
- Workflow: `.github/workflows/build.yml` (já existe). Ajuste só se necessário.
- Gradle wrapper (`gradlew`, `gradle/wrapper/*`) DEVE estar commitado. `apks/` NÃO pode estar no `.gitignore`.

## Loop
```bash
git add -A && git commit -m "feat: <resumo>" && git push
sleep 8
RUN=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch $RUN --exit-status || gh run view $RUN --log-failed | tail -n 150
```
Se falhou: leia o erro, ache a **causa raiz** (não silencie warnings/erros com hacks), corrija, push, repita. Máximo de tentativas por mesmo erro: 5; depois mude a abordagem e registre no PROGRESS.md.

## Erros comuns
- `Unresolved reference`: import/dependência faltando ou versão errada.
- ViewBinding: id no XML com nome diferente do usado no Kotlin.
- `Manifest merger failed`: `android:exported` ausente em activity/service com intent-filter; conflito entre flavors.
- `Duplicate class`: dependência duplicada → exclua o módulo transitivo.
- `AAPT: resource not found`: drawable/cor/string citada mas não criada.
- Versões AGP/Gradle/Kotlin/JDK incompatíveis → alinhe pela tabela oficial.
- Falta de `kapt`/`ksp` para Room → aplique o plugin certo.
- Permissões de foreground service (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) no targetSdk 34.

## Entregar APK
```bash
RUN=$(gh run list --status success --limit 1 --json databaseId -q '.[0].databaseId')
rm -rf /tmp/out && mkdir -p /tmp/out apks
gh run download $RUN -D /tmp/out
cp $(find /tmp/out/myst-mobile-apk -name '*.apk' | head -1) apks/app.apk
cp $(find /tmp/out/myst-tv-apk -name '*.apk' | head -1) apks/app-tv.apk
git add -f apks/*.apk && git commit -m "chore: atualiza APKs (run $RUN)" && git push
```
Confirme `ls -la apks/` e registre o run no PROGRESS.md. Este commit extra pode disparar outro run: isso é normal; verifique que também fica verde.
