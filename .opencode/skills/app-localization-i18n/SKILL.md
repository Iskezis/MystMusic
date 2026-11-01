---
name: app-localization-i18n
description: Seleção de idioma no MystMusic (Sistema, Português, English, Español), tradução completa para inglês e espanhol, formatação por localidade e idioma das requisições InnerTube sem quebrar parsers. Use na fase 20.
---
# Idiomas: pt-BR (padrão), en, es

## Seleção
- Configurações → **Idioma**: Sistema · Português (Brasil) · English · Español (nomes sempre no próprio idioma). Padrão: Sistema.
- Aplicar com `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("pt-BR"|"en"|"es"|""))`; vazio = sistema. Funciona sem reiniciar o processo (recreate das activities com transição suave).
- Pré-Android 13: declarar `AppLocalesMetadataHolderService` (`autoStoreLocales=true`) para persistir. Android 13+: `res/xml/locales_config.xml` (`pt-BR`, `en`, `es`) + `android:localeConfig` no `<application>`, assim o app também aparece nas configurações de idioma do sistema.
- Idioma do sistema fora dos 3: usar **English** (registre em Decisões).
- Tela com RadioGroup de cards; TV com foco D-pad. TV pareada adota o idioma do celular até desconectar (mesma lógica do tema); ao desconectar volta ao próprio.
- `Application`/Service/notificações: usar `applicationContext` com a locale aplicada (`createConfigurationContext`) quando precisar de string fora de Activity (notificação de mídia, downloads, pareamento, atualização, Discord).

## Tradução
- `values/strings.xml` = pt-BR (fonte), `values-en/strings.xml`, `values-es/strings.xml`. Mesma lista de chaves nos três; `plurals` com as regras certas (en: one/other; es: one/many/other; pt: one/many/other).
- **Auditoria obrigatória:** buscar texto fixo em `*.kt` (`"…"` visível ao usuário, `setText("`, `Toast`, `Snackbar`, `AlertDialog`, `setTitle`, notificações) e em `*.xml` (`android:text="` sem `@string`, `android:hint`, `contentDescription`, `android:title`, `tools:` não conta). Mover tudo para recursos. `formatted="false"` só quando necessário; usar placeholders posicionais `%1$s`.
- Traduzir de forma natural (inglês dos EUA; espanhol neutro latino-americano), mantendo tom curto e consistente. Nomes próprios (MystMusic, YouTube Music, Spotify, Discord) não se traduzem. Termos: "Recentes"→Recent / Recientes; "Em Alta"→Trending / En tendencia; "Recomendados"→Recommended / Recomendados; "Playlists"→Playlists / Listas; "Estatísticas"→Statistics / Estadísticas; "Baixadas"→Downloads / Descargas.
- Conteúdo de recursos não-string: `arrays`, `content descriptions`, rótulos da navbar, shortcuts, descrição da Play/loja se houver, texto de permissões, `app_name` permanece MystMusic.
- **Script de conferência** `tools/check_strings.py` (Python, sem dependências): compara chaves e placeholders (`%s %d %1$s`) entre os 3 arquivos e sai com erro se houver faltando/sobrando. Chamar no `build.yml` antes do Gradle. Corrigir os erros, não afrouxar o script.
- Notas de release e texto vindo do GitHub ficam como publicados (sem tradução).

## Formatação por localidade
`DateFormat`/`java.time` com `Locale` atual, `NumberFormat`, `DateUtils.formatElapsedTime`/durações (ex.: "1 h 05 min" em pt, "1 hr 05 min" em en, "1 h 05 min" em es; usar recursos, não concatenar texto), ordenação por `Collator`, tamanho de arquivo (`Formatter.formatShortFileSize`). Estatísticas, downloads, atualização e pareamento incluídos.

## InnerTube sem quebrar
- Contexto das requisições: `hl` (pt-BR / en / es) e `gl` coerentes com o idioma escolhido; manter `visitorData` válido.
- **Proibido** parsers que dependam de texto exibido ("Músicas", "Álbuns", "Ver todas"…). Usar `browseId`, `pageType`, `params`, `icon`/`iconType`, `navigationEndpoint` e estrutura. Se hoje algum parser compara texto, refatorar antes de ligar `hl`.
- Títulos de seções vindos do YouTube (ex.: "Escutar de novo") aparecem no idioma da resposta — aceitável.
- Cache por idioma: invalidar caches de Home/Recomendados/Busca quando o idioma mudar.
- Testar busca, home, artista, álbum, playlist, estatísticas e downloads nos 3 idiomas.

## Fases seguintes
A partir daqui qualquer string nova (fases 21–25) nasce nos 3 idiomas no mesmo commit.

## Verificação
Alternar entre os 3 idiomas em cada tela (inclui TV e pop-ups), ver textos cortados (inglês/espanhol costumam ser mais longos: `maxLines`, `ellipsize`, larguras flexíveis), ver notificações e Discord, rotação durante a troca.
