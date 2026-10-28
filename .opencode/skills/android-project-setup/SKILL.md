---
name: android-project-setup
description: Criar a estrutura Gradle do MystMusic (Kotlin, ViewBinding, flavors mobile/tv, namespace com.iskezis.myst) sem depender de build local.
---
# Setup do projeto
- `settings.gradle.kts`: `rootProject.name = "MystMusic"`, módulo `:app`.
- `app/build.gradle.kts`: `namespace = "com.iskezis.myst"`, `applicationId = "com.iskezis.myst"`, compile/targetSdk 34, minSdk 21, `buildFeatures { viewBinding = true; buildConfig = true }`, Java/Kotlin JVM 17.
- Flavors: `flavorDimensions += "device"`; `mobile` (ndk abiFilters `arm64-v8a`,`armeabi-v7a`), `tv` (abiFilters só `armeabi-v7a`, `applicationIdSuffix` NÃO usar: mesmo pacote).
- `versionCode`/`versionName` incrementados a cada entrega.
- Debug signing padrão basta (APK instalável). Habilite `multiDexEnabled = true` (minSdk 21).
- Dependências base: core-ktx, appcompat, material, constraintlayout, recyclerview, lifecycle (viewmodel/runtime-ktx), navigation (fragment/ui-ktx), room (+ksp), coroutines, media3 (exoplayer, session, ui), coil (imagens), okhttp, kotlinx-serialization-json, security-crypto, shimmer, swiperefreshlayout, palette-ktx, leanback (só flavor tv).
- Pesquise versões estáveis atuais e compatíveis; fixe em `gradle/libs.versions.toml`.
- Ícone adaptativo azul céu com nota musical; `app_name` = MystMusic.
- Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `CHANGE_WIFI_MULTICAST_STATE`.
- Pacotes: `ui/`, `ui/tv/`, `data/{db,youtube,spotify,discord,account,pairing}`, `player/`, `util/`.
