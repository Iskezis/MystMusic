---
name: myst-local-account
description: Conta Myst local (criar conta e login) com Room, senha com PBKDF2 e sessão em EncryptedSharedPreferences; logout do YouTube e do Myst.
---
# Conta Myst (local)
- Tabela Room `users(id, username UNIQUE, salt, passwordHash, createdAt)`. Hash: PBKDF2WithHmacSHA256, 120k iterações, salt 16 bytes `SecureRandom`. Comparação em tempo constante.
- Validação: usuário 3–20 chars, senha ≥ 8; erros inline nos `TextInputLayout`.
- Sessão: `EncryptedSharedPreferences` (`session_type`: myst|youtube, `user_id`). Splash decide: sem sessão → tela de entrada.
- Tela de entrada: botão "Entrar com YouTube" (gradiente) e "Conta Myst" (criar/entrar). Alternância animada (SharedAxis).
- Dados por usuário: playlists, histórico e gostos ligados a `user_id` (para YouTube usar id derivado da conta/cookie hash).
- Logout: limpa sessão (+cookies WebView `CookieManager.removeAllCookies` para YouTube), para player/RPC, volta à entrada, limpa back stack. Mantém dados da conta Myst no aparelho.
- Nunca logar senha/tokens (nem em debug).
