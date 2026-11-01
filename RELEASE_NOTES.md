versionCode: 45
## MystMusic 0.1.9 Dev 1 — canal de atualização

Nova tela **Configurações → Atualizações** para escolher quais versões o app deve avisar e conferir a atualização na hora.

### Novidades desta build
- **Canal de atualização:** **Somente estáveis** (padrão em instalação nova) ou **Estáveis e betas**. A preferência fica no aparelho, vale na hora e não depende da conta Myst.
- **Migração sem susto:** quem já está em uma build `alpha`, `beta`, `rc` ou `dev` começa no canal **Estáveis e betas** na primeira leitura, para não parar de receber atualizações sem querer.
- **Classificação correta:** uma release conta como beta quando o GitHub a marca como pré-release **ou** quando a tag tem `-alpha`, `-beta`, `-rc` ou `-dev`. As Betas 1–5 continuam sendo betas mesmo tendo sido publicadas como Release normal.
- **Sem downgrade:** o pop-up só aparece quando o `versionCode` remoto é maior que o instalado. Voltar de beta para estável não rebaixa o app.
- **Tela com três estados:** verificando (indicador), tudo em dia e erro com **Tentar novamente**. O pop-up de nova versão e o download continuam funcionando, agora com um selo **Beta** quando for o caso.
- **Verificar agora:** botão manual na tela; trocar de canal limpa o cache ETag e dispara a verificação imediatamente.
- **TV:** a mesma tela, com os cards focáveis por D-pad e foco inicial no canal selecionado.
- **Workflow de release:** tags beta são publicadas como pré-release e tags estáveis como Release normal; o workflow falha se a classificação não bater com a tag.

### Compatibilidade
- Atualiza por cima da Beta 5 (`versionCode 44`) e anteriores com a mesma assinatura, mantendo conta Myst, playlists, histórico, downloads e estatísticas.
- Nenhuma migração do Room nesta build.
- A TV continua usando `app-tv.apk` (`armeabi-v7a`, 32 bits).

### Como testar
1. Abra **Configurações → Atualizações** e confirme a versão instalada.
2. Alterne entre **Somente estáveis** e **Estáveis e betas** e use **Verificar agora**.
3. No canal estável, as releases com tag beta não devem aparecer; no canal beta, a maior versão válida deve aparecer com o selo **Beta**.
4. Feche e abra o app: a escolha deve continuar salva.
5. Repita na TV usando o controle remoto.
