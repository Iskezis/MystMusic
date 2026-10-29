---
name: dual-login-profile-photo
description: Login obrigatório com YouTube E conta Myst, criação de conta Myst com foto de perfil, avatar da conta Myst no app e migração de sessões da 0.1.0. Use na fase 12.
---
# Login duplo obrigatório + foto de perfil

## Regras de negócio
- Sessão válida = `mystUserId != null` **e** `youtubeLinked == true`. Sem as duas, a Navegação não abre.
- Ordem na tela de entrada: **1) Conta Myst** (criar/entrar) → **2) YouTube** (WebView). Um indicador de passos (1/2, 2/2) com os dois cartões: "Conta Myst" e "YouTube", cada um mostrando ✓ quando feito.
- Botão "Continuar" só habilita com os dois ✓ (ou avança sozinho ao completar o segundo).
- Vínculo: gravar no registro do usuário Myst o `youtubeAccountId` (hash do SAPISID/ID do canal). Ao entrar numa conta Myst já vinculada, se o YouTube logado for outro, mostrar aviso claro com "Usar a conta do YouTube vinculada" / "Trocar vínculo".
- Logout: "Sair do Myst" ou "Sair do YouTube" → qualquer um leva ao `AuthActivity` no passo que falta. Logout do YouTube limpa cookies/visitorData; logout do Myst não apaga os dados da conta.
- **Migração 0.1.0 → 0.2.0:** quem tinha só uma conta (sessão `session_type`) abre o app e cai direto no passo que falta, com a mensagem "Agora o MystMusic precisa das duas contas". Nunca perder playlists/histórico: contas YouTube-only da 0.1.0 tinham dados ligados a um `user_id` derivado; ao criar/entrar a conta Myst, **oferecer "Trazer meus dados atuais para esta conta"** (reatribuir `userId` no Room em transação) com confirmação.

## Foto de perfil (criação da conta Myst)
- Tela de criação: avatar circular 96dp no topo (placeholder: inicial do nome sobre gradiente azul céu) com botão câmera/lápis. Toque abre `ActivityResultContracts.PickVisualMedia` (ImageOnly).
- Recorte circular/quadrado 1:1 (UCrop **somente** se resolver no CI; caso contrário, tela de recorte própria com gesto de pinça + máscara circular).
- Salvar: redimensionar para 512x512 JPEG q90 em `filesDir/avatars/<userId>.jpg`; guardar caminho em `users.avatarPath`. Sem foto → placeholder.
- Permitir trocar depois tocando o avatar em Configurações (pequena adição permitida; registre em Decisões). Remover a foto antiga ao trocar.
- Room: migração `ALTER TABLE users ADD COLUMN avatarPath TEXT` + `youtubeAccountId TEXT` (**Migration real**, sem destrutiva).

## Onde o perfil aparece
- Cabeçalho da Navegação (canto superior esquerdo, 40dp, com "Olá, <nome>").
- Topo de Configurações (card: avatar 72dp + nome + status "YouTube conectado ✓").
- **Nunca** usar foto/nome da conta do YouTube como perfil do app.
- Imagens via Coil com `allowHardware(false)` (já configurado) e cache invalidado ao trocar a foto (`memoryCacheKey`/`diskCacheKey` com timestamp).

## TV
- Sem login digitado: o pareamento (skill `tv-pairing-code`) deve carregar **as duas contas + a foto** (base64 JPEG 512 criptografado no payload AES-GCM). A TV só sai do pareamento com as duas presentes.

## Estados e erros
Cada passo com carregando/erro/tentar novamente; validações inline; senha nunca logada. WebView do YouTube com botão "Cancelar" e tratamento de falha de carga.
