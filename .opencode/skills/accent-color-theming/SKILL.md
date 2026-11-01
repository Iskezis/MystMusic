---
name: accent-color-theming
description: Cor de destaque (secundária) personalizável no MystMusic - Azul celeste, Vermelho, Verde, Amarelo, Roxo, Rosa e Branco/Preto conforme o tema - via tokens e ThemeOverlay, aplicada em todo o app, TV e notificações. Use na fase 25.
---
# Cor de destaque personalizável

## Onde escolher
Configurações → **Aparência** → seção **Cor de destaque**: linha de 7 amostras circulares (48dp, selecionada com anel + ✓). Muda ao vivo (recreate suave de 200 ms). Persistido no aparelho; TV pareada adota a do celular até desconectar. Nome da amostra por texto/contentDescription traduzido (pt/en/es).

## Cores (token `mystAccent`, `mystAccentVariant`, `mystOnAccent`)
Cada cor tem variante para tema escuro/AMOLED e para o claro (mais profunda), para manter contraste:
| opção | escuro/AMOLED accent / variant | claro accent / variant | onAccent |
|---|---|---|---|
| Azul celeste (padrão) | `#38BDF8` / `#0EA5E9` | `#0284C7` / `#0369A1` | calcular |
| Vermelho | `#F87171` / `#EF4444` | `#DC2626` / `#B91C1C` | calcular |
| Verde | `#4ADE80` / `#22C55E` | `#16A34A` / `#15803D` | calcular |
| Amarelo | `#FACC15` / `#EAB308` | `#CA8A04` / `#A16207` | calcular |
| Roxo | `#C084FC` / `#A855F7` | `#7C3AED` / `#6D28D9` | calcular |
| Rosa | `#F472B6` / `#EC4899` | `#DB2777` / `#BE185D` | calcular |
| **Branco/Preto** | **escuro/AMOLED: `#FFFFFF` / `#E5E5E5`** | **claro: `#0A0A0A` / `#262626`** | calcular |
`mystOnAccent` = preto ou branco pelo maior contraste (helper de `AdaptiveContentColors`/luminância); exigir ≥ 4.5:1 para texto sobre o destaque. Se algum valor falhar, ajustar o tom (registre). "Branco/Preto" muda sozinho com o tema efetivo (claro→preto, escuro/AMOLED→branco); no tema Sistema segue o sistema.
Gradientes: `mystGradientStart = accent`, `mystGradientEnd = accentVariant` (continuam 2 cores simples).

## Implementação
- Preferência `accent` = `sky|red|green|yellow|purple|pink|mono`. Aplicar `ThemeOverlay.Myst.Accent.<Nome>` (e a variante clara/escura) por cima do tema (mesma técnica do overlay AMOLED da fase 11): `onActivityPreCreated`/`BaseActivity` **antes** de `setContentView`; `Application` lê de forma síncrona.
- Atributos em `attrs.xml`: `mystAccent`, `mystAccentVariant`, `mystOnAccent`, `mystAccentContainer` (accent a 16% de alpha sobre a superfície, para pílulas/chips ativos). Mapear também `colorPrimary`, `colorOnPrimary`, `colorSecondary`, `colorPrimaryContainer` do Material para esses valores para que `MaterialButton`, `Slider`, `MaterialSwitch`, `Chip`, `ProgressIndicator`, `TextInputLayout` herdem sozinhos.
- **Auditoria obrigatória:** substituir qualquer azul céu fixo (`#38BDF8`, `#0EA5E9`, `@color/sky_*`, `bg_gradient_sky`) em XML/Kotlin por `?attr/mystAccent*`. Drawables de gradiente usam atributos de tema (`GradientDrawable` criado em código com `MaterialColors.getColor`). Vetores tintados por `?attr/mystAccent`. Procurar também cores hex em `Canvas`/Paint (gráficos de Estatísticas, equalizador, barras), selector de foco da TV, ícone de notificação (`setColor`), widgets/`RemoteViews` e splash.
- Onde o destaque aparece: botões primários, gradientes, item ativo da navbar e pílula, seções destacadas, sliders/seekbars (exceto no player, que usa as cores adaptativas da fase 21), switches, chips selecionados, progresso/download, links, gráficos, indicador de página, anel de foco da TV, notificação de mídia/download.
- **Não** colorir o fundo do player/fila com o destaque (esses usam a capa); mini player usa o destaque no progresso e botão.
- Launcher icon e ícone do app permanecem como estão.
- Notificações: `NotificationCompat.Builder.setColor(accent)` lido na hora de criar (tema escuro de notificação: usar variante clara).

## Interação com temas
A escolha vale em Sistema/Claro/Escuro/AMOLED. No AMOLED, preto puro + destaque; evitar variantes escuras demais. Trocar de tema recalcula as variantes automaticamente (claro ↔ escuro), inclusive para "Branco/Preto".

## Verificação
7 cores × 4 temas nas telas principais (Login, Navegação, Busca, Artista/Álbum/Playlist, Playlists, Player, Fila, Estatísticas, Configurações, Pareamento, TV). Contraste dos textos sobre o destaque, estados focado/pressionado/desabilitado, nenhum resto de azul céu fixo (busca por grep no CI com falha se encontrar hex proibido fora de `colors.xml`/overlays), rotação durante a troca.
