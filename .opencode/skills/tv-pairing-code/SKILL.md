---
name: tv-pairing-code
description: Pareamento celular↔TV por código de 5 dígitos via rede local (servidor HTTP + descoberta NSD) que sincroniza também a conta (Myst/YouTube).
---
# Pareamento por código de 5 dígitos

"Localhost" entre dois aparelhos = **mesma rede Wi-Fi/LAN**. Implementar assim:

## Lado TV (`PairingActivity`)
1. Gera nada: apenas inicia um servidor HTTP local (NanoHTTPD ou `ServerSocket` + coroutines) na porta 8765 (fallback: porta livre) e registra serviço NSD `_mystmusic._tcp` com nome `MystTV-<id curto>`.
2. Mostra tela: "Abra o MystMusic no celular → Configurações/Playlists… → *Conectar à TV* e digite o código", exibindo instrução e estado de espera.
3. Endpoint `POST /pair {code, payload}`. Se `code` confere com o código atual → grava payload e responde 200; senão 403 (contar tentativas; após 5 erros, bloqueia 60s e renova o código).

## Lado celular
- O **código de 5 dígitos aparece no celular**: ao tocar "Conectar à TV" o celular gera `SecureRandom` 00000–99999, descobre TVs via NSD, lista as encontradas; o usuário escolhe a TV, e o celular envia o código + payload. A TV valida o código **recebido** de forma que ambos concordem: fluxo definitivo →
  1. Celular gera o código e o exibe.
  2. Celular chama `POST /request {code_hash}` na TV escolhida (hash SHA-256 do código + nonce).
  3. TV mostra "Digite o código do celular" com 5 campos (controle remoto numérico/D-pad). Usuário digita na TV.
  4. TV envia `POST /confirm {code}` ao celular (servidor local do celular durante o pareamento) → celular compara; se igual, responde com o `payload` de sessão criptografado com chave derivada do código (AES-GCM, PBKDF2).
  Assim o código aparece no celular e a TV o "identifica" pela rede local, conforme pedido.
- Alternativa simples aceita se mais robusta: TV mostra o código e o celular o digita. **Escolha uma, implemente completa e registre no PROGRESS.md**. Preferir a primeira (pedido do usuário).

## Payload sincronizado
Tipo de conta (`myst`|`youtube`), usuário Myst (nome + hash/ token de sessão local, não a senha), cookies/ sessão do YouTube criptografados, playlists Myst (JSON). Nunca trafegar em claro; sempre AES-GCM com a chave do código.

## Regras
- Expira em 2 min; código de uso único; servidor local só enquanto a tela de pareamento está aberta.
- Permissões: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `INTERNET`; `android:usesCleartextTraffic` restrito ao tráfego local (network security config permitindo cleartext só para IPs privados ou usar HTTPS com cert autoassinado).
- Estados na UI: procurando, TV encontrada, código errado, expirado, sucesso (animação).
- Mesmo bloqueio na TV: sem sincronização concluída, não entra na Navegação.
