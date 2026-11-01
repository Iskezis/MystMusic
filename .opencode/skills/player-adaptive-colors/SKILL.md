---
name: player-adaptive-colors
description: Botões, ícones, textos e barra do player em preto quando o fundo atrás deles é claro e em branco quando é escuro, medindo a luminância do fundo real (capa borrada) por zona, com transição suave. Cria o helper reutilizável AdaptiveContentColors. Use na fase 21.
---
# Cores adaptativas no player

## Problema
O fundo do player é a capa borrada (sem escurecer). Capa clara → ícones brancos ficam ilegíveis. A cor **não** vem do tema do app e sim do **fundo real**.

## Regra
- Fundo claro → conteúdo **preto** (`#000000`, texto secundário `#000000` a 65% de alpha).
- Fundo escuro → conteúdo **branco** (`#FFFFFF`, secundário 70% de alpha).
- Escolha por contraste, não por limiar fixo: calcular luminância relativa WCAG do fundo `L`; contraste com branco = `1.05/(L+0.05)`, com preto = `(L+0.05)/0.05`; escolher o maior. Histerese: só troca se a nova opção ganhar ≥ 15% de contraste sobre a atual (evita piscar em capas médias).

## Helper `AdaptiveContentColors` (em `ui/common/`)
```
data class ContentPalette(val primary:Int, val secondary:Int, val isDarkContent:Boolean)
object AdaptiveContentColors {
  fun relativeLuminance(argb:Int):Double
  fun paletteFor(backgroundLuminance:Double, previous:ContentPalette?):ContentPalette
  fun sampleLuminance(bitmap:Bitmap, region:RectF /* 0..1 */):Double   // média em amostra reduzida (≤ 32x32), ignora alpha 0
}
```
Reutilizado pela fase 22 (fila). Sem dependência nova.

## Zonas
O blur pode ser claro em cima e escuro embaixo. Medir **3 zonas** no bitmap borrado já posicionado na tela (mesma escala/crop do `CoverBackdropView`): topo (botão voltar, menu ⋮, título da tela), bloco de título/artista, e controles (progresso, tempos, play/pause/anterior/próximo, repeat, fila). Cada zona tem sua própria `ContentPalette`. Se a diferença visual entre zonas ficar estranha, unificar usando a pior zona (a de menor contraste).
Calcular fora da main thread (`Dispatchers.Default`) quando a capa/backdrop muda; aplicar na main.

## O que colorir
Ícones (voltar, ⋮, repeat, shuffle, fila, anterior, próximo, play/pause, curtir, download), textos (título, artista, tempo atual/total, rótulos), **barra de progresso** (trilho 25% de alpha, preenchido sólido, bolinha), indicadores de página, spinner de loading, selo de download. Botão play/pause: círculo preenchido com a cor `primary` e ícone na cor oposta. Estados desabilitado: alpha 38%.
Usar `ImageViewCompat.setImageTintList` / `MaterialButton.iconTint` / `setTextColor`/`Slider.trackActiveTintList` etc.

## Transição
Ao trocar de faixa ou de tema, animar de uma paleta para outra com `ValueAnimator.ofArgb` (250 ms, `FastOutSlowIn`) e `ArgbEvaluator`; esperar o backdrop novo ficar pronto (crossfade do fundo e das cores juntos, mesma duração). Sem flash de cor errada durante o carregamento: manter a paleta anterior até o novo bitmap estar medido.

## Barras do sistema
Ícones da status bar/nav bar do player acompanham a zona do topo: `WindowInsetsControllerCompat.isAppearanceLightStatusBars = isDarkContent`.

## Cobertura
Capas totalmente brancas, totalmente pretas, cinza médio, muito coloridas (vermelho puro, amarelo), degradês e capa ausente (placeholder). Nos temas claro/escuro/AMOLED e com qualquer cor de destaque. Mini player **não** muda (usa o tema).

## TV
Mesma lógica; amostragem em bitmap pequeno; sem animação pesada.

## Verificação
Tocar faixas com capas dos tipos acima e conferir legibilidade (contraste ≥ 4.5:1 para texto, ≥ 3:1 para ícones). Nada de ícone com cor do tema no player.
