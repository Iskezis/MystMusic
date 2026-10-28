---
name: android-tv-adaptation
description: Adaptar o MystMusic para Android TV 32 bits (flavor tv, armeabi-v7a): manifest leanback, layouts 10-foot, navegação por D-pad/foco, banner.
---
# Android TV (flavor `tv`)

## Manifest (`app/src/tv/AndroidManifest.xml`)
- `<uses-feature android:name="android.software.leanback" android:required="true"/>`, `<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`.
- Activity launcher com `<category android:name="android.intent.category.LEANBACK_LAUNCHER"/>` e `android:banner="@drawable/banner_tv"` (320x180, azul céu, logo MystMusic), `android:theme` TV.
- ABI: `abiFilters "armeabi-v7a"` apenas. `minSdk 21`. Nada de libs nativas só-64-bit. Evitar RAM alta (largeHeap não).

## Layouts
- Pasta `app/src/tv/res/layout/` sobrescreve layouts: tipografia ≥ 18sp, margens 48dp (overscan), cards maiores (200x200dp).
- Navbar da TV: **barra lateral/superior** com Navegação, Playlists, Configurações (a pílula flutuante inferior só no celular). Mantém azul céu e cantos arredondados.
- Busca na TV: campo com teclado de TV; permitir ditado se disponível.

## Foco / D-pad
- Todo item clicável: `focusable="true"`, `clickable="true"`, estado focado visível (escala 1.08 + borda `sky_400` 3dp + elevação). Selector `state_focused`.
- Definir `nextFocusUp/Down/Left/Right` quando o padrão falhar. `RecyclerView` com `descendantFocusability="afterDescendants"`; use `requestFocus()` ao abrir tela.
- Tecla Voltar fecha player/dialog antes de sair. Mídia: tratar `KEYCODE_MEDIA_PLAY_PAUSE/NEXT/PREVIOUS`.
- Sem swipe/drag: reordenar playlist na TV usando botões "mover para cima/baixo" (o gerenciamento principal é no celular).

## Desempenho (32 bits, hardware fraco)
Imagens em `size()` reduzido (Coil), sem blur, sem shared-element pesado, sem animações contínuas, listas com `setHasFixedSize`.

## Fluxo inicial
Abrir app → se não sincronizado → `PairingActivity` (skill `tv-pairing-code`) → após sucesso, Navegação.
