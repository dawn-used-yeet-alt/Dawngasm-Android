# Dawngasm desktop

The desktop shell targets Linux, Windows, and macOS from the same Kotlin
codebase. Installers bundle a Java runtime; the only host dependency is VLC for
audio playback.

## Runtime requirements

VLCJ talks to a locally installed VLC/libvlc. Until the desktop packages bundle
it, install VLC for the matching architecture:

- **Linux:** install `vlc` (or `libvlc5` and the codec plugins) with your
  distribution's package manager. Debian/Ubuntu: `sudo apt install vlc`.
- **Windows:** install the 64-bit VLC app. The MSI does not include VLC.
- **macOS:** install VLC into `/Applications` (VLCJ looks for
  `/Applications/VLC.app`).

Audiochan and hotaudio.net playback need clearance cookies. Desktop currently
uses the in-app paste-cookie dialog in Settings. The native WebView helper is
Phase 10 and will add WebKitGTK (Linux) and WebView2 (Windows) as runtime
dependencies; the current packages do not require either.

## Build and package

Local development:

```sh
./gradlew :desktopApp:run
```

Packages must be built on their target OS (`jpackage` does not cross-build):

```sh
# Linux: deb, rpm, and a jpackage app image
./gradlew :desktopApp:packageDeb :desktopApp:packageRpm :desktopApp:createDistributable

# Windows
./gradlew :desktopApp:packageMsi

# macOS (arm64 or x64 host)
./gradlew :desktopApp:packageDmg
```

The packaging workflow (`.github/workflows/desktop-package.yml`) builds all
four variants and uploads `.deb`, `.rpm`, `.AppImage`, `.msi`, and `.dmg`
artifacts. Linux packaging installs `rpm` in CI, then wraps the jpackage app
image with `appimagetool` and adds AppStream metadata from
`desktopApp/icons/dawngasm.appdata.xml`.

Packages are currently unsigned. macOS Gatekeeper and Windows SmartScreen will
warn on first launch until the signing and notarization work in Phase 16 is
configured with per-OS certificates.

## State and logs

- Linux: `$XDG_CONFIG_HOME/dawngasm` (or `~/.config/dawngasm`)
- Windows: `%APPDATA%\Dawngasm`
- macOS: `~/Library/Application Support/Dawngasm`

Settings are a UTF-8 JSON file written atomically. Logs live under the
`logs/` subdirectory. Downloads go to `~/Music/Dawngasm` unless a folder is
chosen in Settings. `DAWNGASM_CONFIG_DIR`, `DAWNGASM_CACHE_DIR`, and
`DAWNGASM_MUSIC_DIR` override those locations for portable or test runs.
