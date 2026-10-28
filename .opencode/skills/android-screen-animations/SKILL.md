---
name: android-screen-animations
description: Animações de transição entre telas/fragments no app Android (XML): fade-through, shared element da capa para o player, slide do mini player, entrada da tela de login.
---
# Animações de tela

- Transições de fragment: `MaterialFadeThrough` entre abas da navbar (`enterTransition`/`exitTransition`/`reenterTransition`/`returnTransition`), duração 300ms, interpolador `FastOutSlowInInterpolator`.
- Mini player → Player: `MaterialContainerTransform` (shared element: capa + container), `scrimColor` transparente, duração 350ms. Defina `transitionName` único por item (ex. `cover_${id}`).
- Entrada de telas de login: card sobe 24dp com fade (`translationY` + `alpha`), 400ms, logo com leve escala 0.9→1.
- Navegar entre login ↔ cadastro: `MaterialSharedAxis` (eixo X).
- Dialogs: animação de escala 0.92→1 + fade, 200ms (`windowAnimations` style).
- Navbar: ao abrir o Player em tela cheia, esconder a navbar com `translationY` até fora (250ms) e reexibir ao voltar.
- Animar só `alpha`, `translation*`, `scale*` (sem layout pass). Respeitar `Settings.Global.ANIMATOR_DURATION_SCALE` (se 0, pular).
- Evitar jank: `postponeEnterTransition()` até imagens carregarem (`startPostponedEnterTransition` no listener do Coil).
- Na TV: sem shared element pesado; apenas fade (hardware 32 bits fraco).
- Arquivos: `res/anim/`, `res/transition/`, estilos em `themes.xml`.
