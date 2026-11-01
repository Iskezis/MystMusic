---
name: fullscreen-queue-blur
description: Transformar a lista de reprodução (fila) em tela cheia com fundo da capa atual borrada, cores adaptativas e transição suave. Use na fase 22.
---
# Fila em tela cheia

## Objetivo
A fila hoje é um bottom sheet. Passa a ser uma **tela cheia** (`QueueFragment`/Activity do player, com Navigation ou fragment transaction dentro do player) cujo **fundo é a capa da faixa atual borrada**, igual ao do player.

## Layout
- Raiz: `FrameLayout` com `CoverBackdropView` (reutilizar o componente do player; se for código dentro do `PlayerActivity`, extraí-lo para `ui/common/CoverBackdropView` sem mudar a aparência do player) + conteúdo.
- Cabeçalho: botão voltar/fechar (seta para baixo), título "Fila" (traduzido), cartão "Tocando agora" (capa 56dp, título, artista, indicador de equalizador animado).
- Lista (`RecyclerView`) abaixo, ocupando o resto da tela, com padding inferior para os insets; começa **rolada na faixa atual** (comportamento existente: a fila inicia na faixa tocada). Seção "A seguir". Linhas transparentes; a linha atual com fundo translúcido (alpha 12–18%) e cantos 16dp.
- Mantém **todas** as ações atuais da fila (tocar ao tocar, remover/mover se já existirem, menu da faixa, selo de download). Não adicionar ações novas.
- Estados: fila vazia ("Nada na fila"), carregando.

## Fundo
- Mesmo pipeline de blur do player (bitmap reduzido + blur, sem escurecer). Um único bitmap borrado em cache compartilhado entre player e fila (`BackdropCache` por `videoId`/url) → abertura instantânea.
- Troca de faixa com a fila aberta: crossfade 300 ms do fundo (e das cores).
- Android 12+ pode usar `RenderEffect` onde já for usado no player; manter o mesmo comportamento no resto.
- Sem capa: gradiente azul céu (atual destaque) em vez de fundo vazio.

## Cores
Usar `AdaptiveContentColors` (fase 21) para título, artista, ícones, divisores e alça de arrastar; zonas: cabeçalho e lista. Texto da lista sobre fundo variável: se o contraste mínimo não for atingido, aumentar a opacidade do fundo translúcido das linhas em vez de mudar o fundo.

## Transição
Abrir: sobe de baixo (translationY 100%→0, 320 ms, `FastOutSlowIn`) com o fundo em fade; fechar inverso, com gesto de arrastar para baixo (`Slider`/`ItemTouchHelper` não conflitam: usar o cabeçalho como área de arrasto). Botão voltar do sistema fecha a fila antes de sair do player. Ao fechar, as cores voltam à paleta do player sem salto.

## TV
Tela cheia com D-pad: foco inicial na faixa atual; Voltar fecha. Blur leve: bitmap pré-borrado pequeno (≈ 1/10 da tela, `stack blur`), sem `RenderEffect`, sem animação complexa (32 bits).

## Verificação
Abrir/fechar várias vezes, trocar de faixa com a fila aberta, rotacionar, fila longa (500 itens: sem jank), capas clara/escura, 3 temas, TV. Nenhum bottom sheet de fila restante no código.
