---
name: quality-gate
description: Checklist de qualidade antes de marcar qualquer fase como concluída no MystMusic (sem crashes, estados de UI, nomes e pacote corretos, build verde).
---
# Quality gate
Antes de marcar uma fase no PROGRESS.md:
1. Build GitHub verde no commit atual.
2. `applicationId` = `com.iskezis.myst`, app_name = MystMusic nos dois flavors.
3. Nenhuma `!!`, nenhum `TODO`, nenhum `printStackTrace`; erros tratados e visíveis.
4. Cada tela: loading / vazio / erro implementados; rotação/process-death não crasham (estado em ViewModel/`SavedStateHandle`).
5. Navegação por back stack correta; logout limpa tudo.
6. Strings/cores/dimens em recursos; contentDescription nos ícones.
7. TV: foco visível em todos os itens; sem ABI 64 bits no flavor tv.
8. Revisar manifest (permissões mínimas, `exported`).
9. Atualizar PROGRESS.md (fase, run #, decisões).
10. Revisão final: reler README.md e AGENTS.md e conferir item por item do escopo.
