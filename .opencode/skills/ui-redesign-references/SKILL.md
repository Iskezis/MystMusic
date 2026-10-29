---
name: ui-redesign-references
description: Redesign completo e mais moderno do MystMusic misturando referências (BitChord, ArchiveTune v15.1.0, Apple Music) sem copiar, mantendo a identidade azul céu e os 3 temas. Use na fase 17.
---
# Novo visual completo (fase 17)

## Como estudar as referências
1. Abrir (ferramentas de web/`gh` se disponíveis) e ler README, notas de release e screenshots de:
   - BitChord: https://github.com/kushagrasinghx/BitChord/releases
   - ArchiveTune v15.1.0: https://github.com/rukamori/ArchiveTune/releases/tag/v15.1.0
   - App Apple Music (referência de design; use o conhecimento de design e imagens públicas de documentação, não assets).
2. Escrever em PROGRESS.md → Decisões um resumo curto (≤ 15 linhas) do que foi observado e **o que será adaptado**.
3. **Licença/ética:** ArchiveTune é GPL-3.0 e BitChord tem licença própria. **Não copiar código, XML de layout, ícones, ilustrações, textos ou nomes.** Usar apenas ideias gerais de estrutura, ritmo, hierarquia e movimento. O resultado deve ser claramente *outro* app: MystMusic.

## Direção de mistura (guia, ajuste com critério)
- **Apple Music:** títulos grandes e limpos (largeTitle colapsável), muito respiro, capas quadradas com cantos 12–16dp e sombra suave, carrosséis de cards grandes ("Escutar agora"), listas com capa 48dp, **player em tela cheia** com capa enorme, fundo desfocado/gradiente da própria capa, controles grandes centralizados, barra de progresso fina arrastável, ações secundárias (letra/fila/rota) na base; mini player translúcido "vidro".
- **ArchiveTune (Material 3 Expressive):** cores dinâmicas derivadas da capa (Palette), formas expressivas (cantos variados, pílulas), fundo do player com blur/gradiente/brilho suave, tipografia mais marcante, movimento Material (springs). Ideia de variantes de player: **implemente 1 estilo excelente** (não 9).
- **BitChord:** estude a organização, densidade da home e microinterações; incorpore o que combina com a navegação do Myst.
- **Identidade Myst:** azul céu como acento, navbar flutuante em pílula com Playlists no centro (mantida), gradientes simples de 2 cores, tema claro/escuro/AMOLED.

## Sistema de design (criar antes de mexer nas telas)
- `Type`: escala (Display 32/28, Title 22/18, Body 15/14, Label 12), fonte Inter/Poppins variável (downloadable ou embutida).
- `Shape`: pequeno 12dp, médio 20dp, grande 28dp, pílula 50%.
- `Elevation`: usar sobreposição de cor/borda 1dp em vez de sombras pesadas (principalmente no AMOLED).
- `Motion`: springs/`FastOutSlowIn`, 200–400ms; skills `android-screen-animations` e `android-element-animations`.
- Componentes reutilizáveis: `TrackRow`, `CoverCard`, `ArtistCircle`, `SectionHeader`, `LargeTitleHeader`, `GlassBar` (fundo translúcido; `RenderEffect` blur só API 31+, com fallback sólido translúcido nos demais e na TV), `StateView`.
- Cores dinâmicas da capa: `Palette` → cor de destaque do player, **suavizada/misturada com o azul céu** e clampada para manter contraste; fallback azul céu.

## Telas a redesenhar (todas)
Entrada/login duplo, Navegação (cabeçalho com avatar, estatísticas e busca; Recentes, Em Alta, Recomendados), Busca (chips + resultados), Artista, Álbum, Playlist, Playlists (Myst/YouTube/Baixadas), Player (mini + tela cheia), Estatísticas, Configurações/Aparência, Pareamento, diálogos (Spotify, atualização), versão TV.

## Regras
- Nenhuma funcionalidade pode regredir; nenhum ID de view usado pelo código pode quebrar sem ajuste.
- Desempenho: scroll a 60fps em aparelho médio; evitar overdraw e blur pesado; TV 32 bits sem blur/sombras pesadas.
- Acessibilidade: contraste ≥ 4.5:1 nos 3 temas, alvos ≥ 48dp, texto escalável (sp).
- Implementar por telas, build verde a cada 2–3 telas; comparar capturas antes/depois quando possível.
- Registrar no PROGRESS.md as telas concluídas.
