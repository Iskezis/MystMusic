---
name: android-element-animations
description: Microanimações de elementos na tela Android (XML/Kotlin): press de botões, shimmer, carrossel, ícone play/pause animado, navbar ativa, equalizador, entrada escalonada de listas.
---
# Animações de elementos

- **Press feedback:** `animate().scaleX(0.95f).scaleY(0.95f).setDuration(90)` no `ACTION_DOWN`; volta com `OvershootInterpolator` no `UP`. Extensão `View.pressScale()`.
- **Entrada escalonada de listas:** `LayoutAnimationController` com `anim/item_enter.xml` (alpha 0→1 + translationY 24dp→0, 280ms, delay 0.08 por item). Só na primeira carga.
- **Shimmer:** `ShimmerFrameLayout` (cor base `surface`, destaque `surface_alt`) enquanto carrega seções.
- **Play/Pause:** `AnimatedVectorDrawable` (`avd_play_to_pause` / `avd_pause_to_play`), 200ms.
- **Navbar:** ao selecionar item, pílula de fundo cresce (scaleX 0→1, 220ms) e ícone sobe 2dp; Playlists central pulsa uma vez (scale 1→1.12→1).
- **Capa girando (opcional):** `ObjectAnimator.ofFloat(rotation, 0, 360)` linear 20s, pausa quando pausado. Só no Player em celular.
- **Equalizador na faixa tocando:** 3 barras com `ValueAnimator` (altura aleatória, 400ms, repeat).
- **Progresso:** `ObjectAnimator` no `progress` do `LinearProgressIndicator`, 250ms.
- **Reordenar playlist:** `ItemTouchHelper` com elevação (`elevation` 0→8dp) e escala 1.03 do item arrastado; `DefaultItemAnimator` com 200ms.
- **Pop-up Spotify:** campo com erro faz shake (translationX ±8dp, 3 ciclos, 250ms).
- **Pareamento TV:** cada dígito do código aparece com flip/fade escalonado (80ms).
- Regras: nunca animar no thread de background; cancelar animadores em `onDestroyView`; durações 150–400ms; interpoladores `FastOutSlowIn`/`Overshoot`; evitar animações infinitas fora do player.
