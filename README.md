# MystMusic

Android music app (Kotlin + XML) featuring YouTube Music playback through InnerTube, a local Myst account, Spotify playlist importing, offline downloads, Discord Rich Presence, and a 32-bit Android TV build.

- **App Name:** MystMusic
- **Package:** `com.iskezis.myst`
- **Current Version:** 0.1.8 Beta 5 (`versionCode 44`)
- **Status:** Beta; includes unofficial YouTube Music and Discord integrations.
- **Theme Color:** Sky blue (`#38BDF8`), simple two-color gradients, minimalist surfaces.
- **UI Language:** Portuguese (Brazil).
- **Build Process:** Handled exclusively via GitHub Actions.
- **Final APKs:** `apks/app.apk` (mobile) and `apks/app-tv.apk` (Android TV, `armeabi-v7a`).

## What's new in 0.1.8 Beta 5

- **Dedicated TV statistics channel:** the TV now sends only playback events to the phone through `/stats`, independently of the full snapshot. A playlist or YouTube-cache merge problem can no longer block a song played on the TV from reaching Statistics.
- **Reliable statistics outbox:** events are stored in `tv_pending_stats.json` before the network and are only cleared after the phone acknowledges them. Retries run with backoff, and the outbox is restored on the next process start or pairing with the same Myst account.
- **Live phone updates:** the Statistics screen requests an immediate sync when opened/refreshed and keeps observing `play_events` to reload after the TV merge completes.
- **No lost events during full syncs:** the full snapshot only clears the statistics outbox when every pending event was included in that request.
- **Full release:** Beta 5 is published as a normal GitHub Release, not a pre-release.

The complete history is in [CHANGELOG.md](CHANGELOG.md).

## Features

### Accounts and profile

- Mandatory dual login: a YouTube account **and** a local Myst account. The navigation only opens when both are signed in.
- Myst account creation with profile photo (gallery picker + circular crop, 512 px JPEG).
- The Myst identity is always the primary profile shown in the header and Settings; the YouTube account appears as a secondary connection.
- Portable `Mystaccount.json`: internal automatic copy plus an optional external copy chosen through the system file picker. It restores profile, avatar, playlists, history, playback statistics, preferences and the YouTube Music cache. YouTube cookies and Discord tokens are not written to the file.
- Per-account isolation of recent tracks, likes, playlists, history, preferences and statistics.
- Logout from Myst, YouTube or both. On the TV, any logout returns to the pairing screen.

### Navigation and discovery

- Home with greeting, search pill, "Continuar ouvindo", Recentes in pages of four songs (swipe sideways for 5–8, 9–12…), Em Alta as a ranked list, and Recomendados in a horizontal carousel.
- Search across songs, artists, albums and playlists with filter chips.
- Artist profile, album screen and public playlist screen, with pagination and a "Copiar para Myst" action for public YouTube Music playlists.
- Recent searches per Myst account, recorded only when the user confirms a search or selects a result.
- YouTube Music library sync: the account's playlists, listening history and personalised recommendations are cached in Room and refreshed in the background.
- Dedicated Statistics screen: listening time, play count, top songs/artists/albums, 24-hour and per-day charts, current streak and record.

### Playlists and Spotify

- Create, rename, change the cover image, reorder tracks by drag & drop, remove tracks and play Myst playlists.
- YouTube Music playlists are shown read-only in a separate library section; "Copiar para Myst" creates an editable local copy without changing the original.
- **Integrar do Spotify:** paste a playlist link in the pop-up and the app maps each track to YouTube Music, preserving the original order and skipping ambiguous covers/karaoke/remixes.

### Player and Discord

- Media3/ExoPlayer playback with a media notification, queue, repeat (off / all / one) and a full-screen player.
- Real blurred cover backdrop, square artwork, three-dot track menu (download, add to playlist, artist, album) and queue sheet that starts at the current track.
- Mini player with artwork, title/artist, progress under the controls and previous/play/next buttons.
- Opt-in Discord Rich Presence showing the current song and artist, with progress when the duration is known. The app warns that Discord RPC uses a user token and may conflict with Discord's terms.

### Downloads and offline use

- Long-press a song to download it, or download an entire album, playlist, artist discography or the complete "Músicas em alta" catalog.
- Downloads run in a foreground service with a queue, notification, retry/recover action, `Range` resume, `WakeLock` and `WifiLock`.
- Offline playback and download badges throughout the UI.
- Settings → Armazenamento shows used space, downloaded songs and the Media3 cache, with cache size/retention limits and individual or bulk removal.

### Themes and appearance

- System, Light, Dark and AMOLED Black themes, persisted per device.
- No hard-coded surface/text colours in layouts: screens use theme attributes, so light/dark/AMOLED work across every screen and on TV.
- Appearance screen in Settings with live theme cards; AMOLED is available when the effective theme is dark.

### Android TV

- `tv` flavor with 10-foot layouts, D-pad focus rings and an explicit focus order.
- Pairing by a 5-digit code over the local Wi-Fi network (HTTP server + NSD discovery). The phone sends both accounts, the Myst avatar, playlists and preferences; the TV keeps an in-memory session while paired.
- TV build uses `armeabi-v7a` only and does not persist the Myst account to disk.

### Diagnostics

- Settings → Logs e diagnóstico shows the recent log, allows copying, clearing and sharing through a non-exported `FileProvider`.
- A previous crash stack is saved and included in the next diagnostic report. No cookies or tokens are written to the log.

## Installation

1. Download `apks/app.apk` for a phone/tablet or `apks/app-tv.apk` for Android TV.
2. Allow installation from unknown sources for the browser or file manager you are using.
3. If you have an APK older than `0.2.0-dev.4`, uninstall it **once** before installing (the development signing key changed). From `0.2.0-dev.4` onward, updates install over the previous version and keep the Myst account, playlists, history and statistics.
4. On the TV, open the app and use the displayed 5-digit code to pair it with the phone (both devices must be on the same Wi-Fi network).
5. When a new release is available, use the in-app pop-up or the GitHub button. If the Android installer refuses the update because of a different signature, uninstall the old version once and install the new APK.

## Build and release

- `build.yml` runs on every push to `main`/`master` (and manually), builds the `mobile` and `tv` flavors, verifies the APK signature and uploads the APKs as workflow artifacts.
- `release.yml` runs when a tag matching `v*` is pushed (or manually with a tag), builds signed release APKs, verifies the signature and publishes a GitHub Release with exactly these assets:
  - `app.apk` (mobile)
  - `app-tv.apk` (Android TV)
  - `SHA256SUMS.txt` (SHA-256 of both APKs)
- Repository secrets required: `MYST_KEYSTORE_B64` (base64 of the keystore), `MYST_KEYSTORE_PASSWORD`, `MYST_KEY_ALIAS` and `MYST_KEY_PASSWORD`.
- Never commit the keystore or its passwords. Keep the same signing key for every future update; changing it forces users to uninstall and lose local data.
- The first line of `RELEASE_NOTES.md` must be `versionCode: <n>`; the app uses it to compare the installed version with the release. `versionCode` must always increase.
- Current tag: `v0.1.8-beta.3`.

## Security and privacy

- The Myst account password is stored as a PBKDF2 hash; sessions use `EncryptedSharedPreferences`.
- `Mystaccount.json` is a credential: it contains the PBKDF2 salt/hash and account data. Keep it private.
- The update checker only accepts HTTPS URLs from this repository's GitHub Releases. No GitHub token is embedded in the APK; the release repository must be public for anonymous API access.
- Downloaded APKs stay in the app's private cache and are shared with the Android installer only through a temporary read permission.

## Known limitations

- YouTube Music is accessed through InnerTube, an unofficial API, and may change or stop working without notice.
- Discord RPC uses a user token; it is opt-in and includes a risk warning. Discord may change or restrict this flow.
- Offline downloading of YouTube Music audio may conflict with YouTube's Terms of Service. The app keeps files private to the app and shows a notice before the first download.
- Spotify imports depend on title, artist and duration matching; ambiguous tracks may be skipped and are reported on screen.
- Pairing a phone with a TV requires both devices to be on the same Wi-Fi network.
- The TV system installer may limit in-app updates; if that happens, download `app-tv.apk` from the GitHub Release or transfer it with a downloader.
- Playback depends on the AAC/Opus codecs available on the device or TV. The app prioritises AAC for compatibility with 32-bit TVs.
- The Myst account is local to the device. Keep Android backup enabled and/or save a `Mystaccount.json` copy. Encrypted sessions are deliberately not backed up, so YouTube login is required again after a restore.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the complete version history, including the final 0.1.8 Beta release.
