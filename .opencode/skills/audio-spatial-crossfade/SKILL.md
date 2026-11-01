---
name: audio-spatial-crossfade
description: Configurações de Áudio no MystMusic com Áudio espacial (processador estéreo/virtualizer) e Fade cruzado real entre faixas (dois players), mantendo MediaSession, estatísticas, Discord e downloads corretos. Use na fase 24.
---
# Áudio: espacial + fade cruzado

## Tela Configurações → Áudio
- Item novo "Áudio" em Configurações. Conteúdo:
  1. **Áudio espacial**: `MaterialSwitch` + seletor de intensidade (Baixa / Média / Alta). Texto: "Funciona melhor com fones de ouvido."
  2. **Fade cruzado**: seletor "Desativado" (padrão) ou 2, 4, 6, 8, 10, 12 s (Slider discreto ou chips), com descrição "Sobrepõe o fim de uma música ao início da próxima."
- Preferências no aparelho (`audio_spatial`, `audio_spatial_level`, `audio_crossfade_ms`). TV pareada adota as do celular enquanto pareada, **se** suportadas; senão oculta/ignora.
- Traduzido (en/es) no mesmo commit.

## Áudio espacial
1. **Spatializer do sistema (Android 12L+/API 32+):** definir `AudioAttributes` com `setSpatializationBehavior(SPATIALIZATION_BEHAVIOR_AUTO)` no player para que fones/dispositivos compatíveis espacializem conteúdo multicanal. Isso sozinho não afeta estéreo comum.
2. **Efeito estéreo próprio (todas as versões):** `SpatialAudioProcessor : BaseAudioProcessor` do Media3, aplicado na cadeia de `AudioSink` (`DefaultAudioSink.Builder().setAudioProcessors(arrayOf(...))`) de **ambos** os players (ver fade cruzado). Trabalha em PCM 16-bit e float, estéreo:
   - Processamento mid/side: `M=(L+R)/2`, `S=(L-R)/2`; `S' = S*width`; `L'=M+S'`, `R'=M-S'`. Largura por nível: Baixa 1.25, Média 1.5, Alta 1.8.
   - Realce de externalização leve (opcional e simples): *cross-feed* com atraso interaural ~0,3 ms e passa-baixa suave (≈ 700 Hz) misturando 15–25% do canal oposto atrasado, **apenas** nos níveis Média/Alta.
   - Compensação de ganho (-1 a -3 dB conforme o nível) e **soft limiter** para evitar clipping.
   - Mono (L≈R) → passthrough. Formatos não estéreo → passthrough. Ligar/desligar com rampa de 50 ms (sem clique). `isActive()` falso quando desligado (custo zero).
3. Se o aparelho tiver `Virtualizer` utilizável (`Virtualizer.getStrengthSupported()` e `canVirtualize(STEREO, BINAURAL)`), pode-se preferi-lo, mas o processador próprio é o caminho garantido e testável; registre a escolha em Decisões.
4. Não aplicar em alto-falante embutido por padrão? Opcional: avisar na tela que o efeito é pensado para fones. Sem detecção obrigatória.
5. Sem EQ de terceiros embutido; sem dependências novas.

## Fade cruzado (sobreposição real)
Meta: no fim da faixa A, a faixa B começa já audível enquanto A some, por `crossfadeMs`.
- Arquitetura preferida: `CrossfadeController` com **dois ExoPlayers** (A ativo, B de apoio) e um **player de fachada** (`ForwardingPlayer`/`SimpleBasePlayer`) exposto ao `MediaSession`, de modo que notificação, controles, fila, posição e `MediaController` vejam sempre **uma** reprodução lógica. A fila lógica é uma só (mantida pela fachada); B só recebe o próximo item.
- Disparo: quando `duration - position <= crossfadeMs` e existe próximo item e o modo de repeat/shuffle permite. Antes disso (≈ 10–15 s antes) pré-resolver URL e **pré-carregar** B (`prepare()` com `playWhenReady=false`) para não haver espera.
- Execução: B inicia em volume 0 e sobe até 1; A desce de 1 a 0; curva **equal-power** (`sqrt`/seno-cosseno), passos de ~40 ms em coroutine/`Handler` (sem `ValueAnimator` em thread errada). Ao fim, A é parado e liberado/reciclado; B vira o ativo (troca de papéis); a fachada emite as transições de item/posição como se fosse um único player.
- **Não aplicar crossfade em:** troca manual (próxima/anterior/toque na fila/seek) → usar apenas um **fade curto de 150–250 ms**; repeat-one; última faixa sem repeat; faixas contíguas do mesmo álbum quando o usuário quiser gapless? (decisão simples: aplicar sempre que `crossfadeMs>0`, exceto `repeat-one`); faixa com duração menor que `2*crossfadeMs` (reduzir o fade a `duration/3`).
- Foco de áudio: só o player ativo tem `handleAudioFocus=true`; B `false` até virar ativo; pausa do usuário/perda de foco pausa **ambos** e congela o fade (retoma de onde parou). Fone desconectado (`becoming noisy`) pausa ambos.
- Mesmo `OkHttpDataSource`/`ResolvingDataSource`, offline (arquivo local) e cache iguais nos dois; mesmo `AudioSink` com o `SpatialAudioProcessor` configurado igual nos dois (compartilhe a configuração por um objeto de estado).
- Efeitos colaterais a manter corretos: **estatísticas** (`play_events`: encerrar a faixa A e iniciar a B no momento da troca lógica, tempo ouvido correto durante a sobreposição, sem contar duas vezes), **Discord RPC** (atualiza na troca lógica), **histórico/Recentes**, **telemetria de reprodução do YouTube Music**, **notificação/MediaSession** (metadados trocam no ponto certo), **downloads** (não interferem), **TV** (eventos de estatística normais).
- Mudar a configuração com música tocando: vale a partir da próxima faixa.
- Plano B só se o plano A comprovadamente falhar no CI/aparelho: fade-out/fade-in sequencial com gap mínimo. Registre o motivo no PROGRESS. A opção do usuário continua chamando "Fade cruzado".

## TV / aparelhos fracos
Dois decodificadores simultâneos em TV 32 bits podem engasgar. Habilitar fade cruzado e espacial na TV só se `!isLowRamDevice` e após um teste simples de desempenho (underrun/`onAudioUnderrun` contados); se falhar, desligar sozinho e informar. Caso contrário ocultar as opções.

## Robustez
Qualquer exceção no `CrossfadeController` → liberar B, restaurar volume 1 em A e continuar a reprodução normal (sem travar a música). Sem vazamento de players (conferir `release()`), sem `!!`.

## Verificação
- Espacial: ligar/desligar sem clique, 3 níveis, mono em passthrough, sem clipping em faixa alta.
- Crossfade: 2 e 12 s, faixa curta, pular manualmente, pausar no meio do fade, repeat-one, fim da lista, offline, rotação, notificação correta, estatísticas com 1 evento por faixa, Discord correto, downloads em andamento.
- Tocar 30 faixas seguidas sem vazar memória.
