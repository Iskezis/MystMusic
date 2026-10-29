---
name: theme-system-amoled
description: Tema do MystMusic que segue o sistema (claro/escuro), com tela Aparência nas Configurações (Escuro/Claro/Sistema) e opção Preto AMOLED. Use na fase 11 e sempre que criar layouts.
---
# Tema: Sistema / Claro / Escuro / Preto AMOLED

## Modelo
- Preferências (SharedPreferences ou DataStore, nível do aparelho): `theme_mode` = `system|light|dark` (padrão `system`), `amoled` = boolean (padrão false).
- AMOLED só vale quando o tema **efetivo** é escuro (modo `dark`, ou `system` com o sistema escuro). Em claro, o interruptor fica oculto e a flag é ignorada (mas preservada).

## Implementação
1. `MystApp.onCreate()`: ler preferência **de forma síncrona** (SharedPreferences) e chamar `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM | MODE_NIGHT_NO | MODE_NIGHT_YES)` antes de qualquer Activity → sem flash do tema errado.
2. Temas: `Theme.Myst` (DayNight, base Material3) em `values/themes.xml` (claro) e `values-night/themes.xml` (escuro). Overlay `ThemeOverlay.Myst.Amoled` com os tokens AMOLED.
3. Aplicar o overlay AMOLED em `Application.onCreate` via `registerActivityLifecycleCallbacks` → `activity.theme.applyStyle(R.style.ThemeOverlay_Myst_Amoled, true)` **antes de `super.onCreate`/`setContentView`** (ou `ActivityLifecycleCallbacks.onActivityPreCreated` no API 29+; no < 29 usar `attachBaseContext`/classe `BaseActivity` que aplica no início do `onCreate`). Ao alternar: `activity.recreate()` com transição suave (crossfade 200ms), sem reiniciar o app.
4. Mudança de tema do sistema com modo `system` é tratada pelo Android (configChanges não declarados → recreate automático). Estado em ViewModel/`SavedStateHandle`.

## Tokens (atributos customizados em `attrs.xml`)
`mystBackground`, `mystSurface`, `mystSurfaceAlt`, `mystOnSurface`, `mystOnSurfaceDim`, `mystNavbarBg`, `mystStroke`, `mystGradientStart/End`.
| token | claro | escuro | AMOLED |
|---|---|---|---|
| background | `#F6FAFE` | `#0B1220` | `#000000` |
| surface | `#FFFFFF` | `#111A2E` | `#0A0A0A` |
| surfaceAlt | `#E8F3FC` | `#16223A` | `#141414` |
| onSurface | `#0F172A` | `#F1F5F9` | `#F1F5F9` |
| onSurfaceDim | `#475569` | `#94A3B8` | `#94A3B8` |
Azul céu (`#38BDF8`/`#0EA5E9`) é destaque nos três; no claro, texto sobre azul céu deve ser escuro (`#04202E`) para contraste ≥ 4.5:1 (verificar).
- Proibido `@color/…` fixo para fundo/texto em layouts e drawables: usar `?attr/…`. Drawables com gradiente usam `?attr/mystGradientStart/End` (ou `ContextCompat` + `GradientDrawable` em código).
- Status bar/nav bar: ícones claros/escuros conforme o tema (`WindowInsetsControllerCompat.isAppearanceLightStatusBars`). Capas/placeholder e shimmer também por atributo. Verificar o WebView de login (fundo) e dialogs.

## Tela Aparência (Configurações → Aparência)
- Item "Aparência" na lista de Configurações abre `AppearanceFragment` (celular) / painel focável (TV).
- Seção "Tema": 3 opções (RadioGroup em cards): **Sistema**, **Claro**, **Escuro**, com pré-visualização em miniatura.
- Abaixo, `MaterialSwitch` **"Preto AMOLED"** com descrição "Fundo totalmente preto". Aparece/desaparece com animação (fade + altura) quando o tema efetivo é/deixa de ser escuro.
- Por enquanto **somente** isso (sem outras opções de aparência).
- TV: foco D-pad, mesmos controles; TV costuma ser escura, mas respeita a escolha.

## Verificação
Alternar os 3 modos e o AMOLED em cada tela (Login, Navegação, Busca, Playlists, Player, Configurações, TV). Nada ilegível, nenhum fundo "vazando" na cor errada, capturas de tela com contraste ok. Nenhum crash ao girar a tela durante a troca.
