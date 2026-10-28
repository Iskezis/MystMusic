---
name: android-music-ui-xml
description: Criar interface de app de música Android em XML (Material 3, azul céu, minimalista, gradientes simples), incluindo navbar flutuante arredondada, home com seções, busca, player e playlists.
---
# UI de app de música em XML

## Paleta (colors.xml)
sky_400 `#38BDF8`, sky_500 `#0EA5E9`, sky_200 `#BAE6FD`, bg `#0B1220`, surface `#111A2E`, surface_alt `#16223A`, text `#F1F5F9`, text_dim `#94A3B8`, error `#F87171`.
Tema: `Theme.Material3.Dark.NoActionBar`, `colorPrimary=sky_400`, `android:windowBackground=bg`, status bar transparente (edge-to-edge com insets).

## Gradientes (drawable)
`bg_gradient_sky.xml`: `<shape><gradient android:startColor="@color/sky_400" android:endColor="@color/sky_500" android:angle="135"/></shape>`. Use em botão primário, cabeçalho de playlist, mini player. Máx. 2 cores. Fundo geral sólido escuro com leve gradiente `bg → surface`.

## Home ("Navegação")
`CoordinatorLayout` → `SwipeRefreshLayout` → `RecyclerView` vertical único com view types: `SearchBar` (fixo no topo via `MaterialCardView` raio 28dp, ícone de busca, hint "Buscar músicas"), `SectionHeader`, `HorizontalCarousel` (RecyclerView horizontal aninhado, `setHasFixedSize`, `RecycledViewPool` compartilhado). Ordem: **Recentes → Em Alta → Recomendados**. Cards: capa 140dp, cantos 16dp, título 1 linha, artista dim.
Use `ListAdapter` + `DiffUtil`. Estado: shimmer / vazio / erro (layout `view_state.xml` reutilizável).

## Navbar flutuante (obrigatória)
`MaterialCardView` com `layout_gravity="bottom|center_horizontal"`, `cardCornerRadius="32dp"`, `cardElevation="12dp"`, `layout_marginBottom="16dp"`, largura `wrap_content` (mín. 280dp), fundo `surface_alt` 92% opaco, stroke 1dp branco 8%. Dentro: `LinearLayout` horizontal com 3 `ImageButton`/ícone+label: Navegação, **Playlists (centro, maior, 56dp, fundo gradiente sky, círculo)**, Configurações. Item ativo: ícone `sky_400` + pílula de fundo `sky_400` 16%. Conteúdo das telas recebe `paddingBottom=104dp` (clipToPadding=false) para não ficar sob a navbar. Aplicar `WindowInsets` (navigationBars) à margem.
Navegação: 1 Activity + `FragmentContainerView` + Navigation Component; navbar fica na Activity.

## Mini player
Acima da navbar (margem inferior 88dp): capa 44dp, título, play/pause, barra de progresso fina de 2dp. Toque expande para `PlayerFragment` (capa grande 300dp, slider, controles, gradiente derivado da capa via Palette suavizado com sky).

## Playlists
Grade 2 colunas (celular). Primeiro item: card "Nova playlist" (+). FAB/botão "Integrar do Spotify" no topo com ícone. Edição: `ItemTouchHelper` para reordenar (alça ≡), swipe para remover com Snackbar "Desfazer".

## Pop-up Spotify
`MaterialAlertDialog` custom: título "Integrar do Spotify", `TextInputLayout` (outlined, multiline 2–3 linhas, hint "Cole o link da playlist"), botões "Cancelar" / "Importar" (gradiente). Validar link; erro inline no campo.

## Tipografia/espaçamento
Fonte sistema ou `Poppins`/`Inter` via downloadable fonts. Títulos 20sp bold, corpo 14sp. Grid de 8dp. Margens de tela 20dp.

## Boas práticas
ViewBinding, `ConstraintLayout` raso, `tools:` para preview, sem hardcode, vetores (`VectorDrawable`) para ícones, `MaterialButton` com `cornerRadius=24dp`. Modo escuro único (v1).
