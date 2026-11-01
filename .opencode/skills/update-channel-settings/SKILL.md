---
name: update-channel-settings
description: Configuração do canal de atualização automática do GitHub (Somente estáveis ou Estáveis e betas), classificação de releases, tela Configurações → Atualizações e ajuste do workflow de release. Use na fase 19.
---
# Canal de atualização (estável × beta)

Estende `github-self-update` e `release-signing-versioning`. Leia-as antes.

## Preferência
- `update_channel` = `stable` | `beta` (SharedPreferences do aparelho, não da conta Myst).
- **Padrão:** `stable` em instalação nova. **Migração:** se o app instalado for uma build beta (`versionName` contém `beta`, `alpha`, `rc` ou `dev`), o valor inicial é `beta` (só na primeira leitura, gravando em seguida) para ninguém parar de receber atualização sem querer.
- Valor muda na hora; próxima verificação ignora o intervalo de 6 h (limpar ETag/horário) e roda ao fechar a tela.

## Tela Configurações → Atualizações
- Item novo "Atualizações" na lista de Configurações (entre Armazenamento e Conectar à TV; mesma aparência dos demais).
- Conteúdo: versão instalada (`versionName (versionCode)`), duas opções em cards com RadioGroup:
  - **Somente estáveis**: "Avisar apenas sobre versões finalizadas."
  - **Estáveis e betas**: "Também avisar sobre versões beta (podem ter erros)."
- Botão **Verificar agora** (pequena adição permitida; registre em Decisões) mostrando "Você está na versão mais recente" / pop-up de atualização / erro de rede com "Tentar novamente".
- TV: mesmos controles, foco D-pad (foco inicial na opção selecionada).
- Textos em pt-BR agora; traduzidos na fase 20.

## Classificação de release
Para cada item de `GET /repos/{repo}/releases?per_page=30` (ignorar `draft`):
```
isBeta = release.prerelease || tag.matches(Regex("(?i).*[-.](alpha|beta|rc|dev)([-.]?\\d+)?.*"))
```
- Canal `stable`: considera só `!isBeta`.
- Canal `beta`: considera todos.
- Entre os candidatos, escolher o de maior `versionCode` (lido da 1ª linha `versionCode: N` do corpo; fallback SemVer da tag). Oferecer apenas se `remoteCode > BuildConfig.VERSION_CODE`. **Nunca oferecer downgrade**: quem voltar de beta para estável continua na beta instalada até sair estável maior.
- Release sem `app.apk`/`app-tv.apk` correspondente ao flavor → ignorar.
- Pop-up mostra um selo "Beta" quando `isBeta`.
- Nota: todas as releases 0.1.8 Beta 1–5 são normais no GitHub mas contam como beta pela tag; isso é esperado.

## Workflow
- Em `release.yml`: `--prerelease` quando a tag casa a regex acima; Release normal (`--latest`) caso contrário. **Remover** a verificação que falhava se `isPrerelease` fosse verdadeiro (era da Beta 5) e substituir por: falha se tag beta sem `--prerelease` ou tag estável marcada pré-release.
- `RELEASE_NOTES.md` continua com `versionCode: N` na 1ª linha.
- Não publique release estável sem pedido do usuário; para testar, use uma beta/pré-release e informe.

## Testes
Criar fixtures JSON de `/releases` (estável 0.2.0 code 50; beta 0.2.1-beta.1 code 51; draft; sem asset) e testes de unidade da função `pickUpdate(channel, installedCode, releases)` cobrindo: stable ignora beta, beta pega a maior, sem downgrade, sem asset, lista vazia, JSON inválido. Se o CI não roda testes, deixar as fixtures e validar manualmente com `gh release list`.

## Robustez
Falha silenciosa offline/rate limit; nunca crash; preferência inválida → `stable`.
