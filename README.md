# MystMusic

Android music app (Kotlin + XML) featuring YouTube Music (via InnerTube), Spotify playlist importing (via link), Discord Rich Presence, and a 32-bit version for Android TV.

- **App Name:** MystMusic
- **Package:** `com.iskezis.myst`
- **Current Version:** 0.1.0 Alpha (versionCode 9)
- **Status:** Alpha testing phase; includes unofficial integrations for YouTube Music and Discord.
- **Theme Color:** Sky blue (simple gradients, minimalist and modern aesthetic).
- **UI Language:** Portuguese (Brazil).
- **Build Process:** Handled exclusively via GitHub Actions (repository already configured in `git remote`).
- **Final APKs:** `apks/app.apk` (mobile) and `apks/app-tv.apk` (Android TV, armeabi-v7a).

## Scope of Version 0.1.0 Alpha

### Login/Authentication
1. Login via YouTube account (Google WebView → cookies → InnerTube session).
2. Create Myst account / Login (local app account, stored on the device; passwords secured using PBKDF2).
3. Android TV: Upon launch, requires synchronization via a 5-digit code displayed on the mobile app.

### Navigation (Home Screen)
- Search bar at the top.
- Section 1: **Recent** (what the user has just listened to).
- Section 2 (scrolling down): **Trending** (based on user preferences).
- Section 3: **Recommended** (based on user preferences).
- Floating navigation bar, centered at the bottom with rounded corners: **Browse | Playlists (center, highlighted) | Settings**.

### Playlists
- Create, rename, change cover image, reorder tracks (drag & drop), and remove items. - **"Integrate from Spotify"** button: opens a pop-up with a text field to paste a playlist link → imports and maps the tracks to YouTube Music.

### Settings
- **Log out** option only (for YouTube or Myst). (Exception documented in AGENTS.md: Discord item.)

### Player
- Mini-player + full-screen mode, media notifications, and Discord RPC showing the current track.

### TV (`tv` flavor)
- Layouts designed for 10-foot UI, D-pad navigation, pairing screen with a 5-digit code, `minSdk 21`, and `armeabi-v7a` ABI.

## Installation (alpha)
1. Download `apks/app.apk` for mobile or `apks/app-tv.apk` for Android TV.
2. Enable the installation of apps from unknown sources for the browser or file manager you are using.
3. If a previous version was signed with a different key, uninstall it before installing this alpha version.
4. On the TV, open the app and use the displayed 5-digit code to pair it with your mobile device (both must be on the same Wi-Fi network).

## Known limitations
- YouTube Music is accessed via InnerTube (an unofficial API), and Discord RPC uses a user token. These integrations may change or stop working without notice; Discord RPC is an opt-in feature and includes a risk warning.
- The APKs in this alpha release are signed with a debug key. They are not suitable for store publication, and a future update signed with a different key may require you to uninstall the previous version.
- Spotify imports map tracks based on title, artist, and duration; ambiguous tracks may be skipped, with an on-screen notification provided. - Pairing a mobile device with a TV requires both devices to be on the same Wi-Fi network.
- Playback depends on the AAC/Opus codecs available on the device or the TV.

## Changelog
See [CHANGELOG.md](CHANGELOG.md) for the version 0.1.0 Alpha release notes.

## Important
The application for Android TVs has not been released yet, as it is still in the testing phase. In the future, this version will become available, alongside interface updates, new languages, customization options, and full integration with other applications.
