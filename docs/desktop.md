# Dawngasm desktop

The desktop shell targets Linux, Windows, and macOS from the same Kotlin
codebase. Installers bundle a Java runtime; the host needs VLC for audio and a
system webview for Cloudflare clearance.

## Runtime requirements

VLCJ talks to a locally installed VLC/libvlc. Until the desktop packages bundle
it, install VLC for the matching architecture:

- **Linux:** install `vlc` (or `libvlc5` and the codec plugins) with your
  distribution's package manager. Debian/Ubuntu: `sudo apt install vlc`.
- **Windows:** install the 64-bit VLC app. The MSI does not include VLC.
- **macOS:** install VLC into `/Applications` (VLCJ looks for
  `/Applications/VLC.app`).

Audiochan and hotaudio.net playback need clearance cookies. Settings opens a
native clearance window provided by the bundled `dawngasm-clearance-helper`;
the clipboard paste dialog remains available as a fallback. The helper uses the
system webview:

- **Linux:** `libwebkit2gtk-4.1-0`. The `.deb`/`.rpm` packages do not declare
  this dependency yet — install `libwebkit2gtk-4.1-0` if the clearance window
  does not open.
- **Windows:** WebView2 Runtime, which ships with Windows 11 and current
  Windows 10 installs. The MSI does not bundle it.
- **macOS:** WKWebView, included with the system.

The helper lives in `desktopApp/native/clearance-helper` and is built with Cargo
(Rust 1.89+). Packaging tasks build and stage it automatically, so Rust is
required to produce installers. `:desktopApp:run` looks for a locally built
helper under `desktopApp/native/clearance-helper/target/release/`.

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
`desktopApp/icons/dev.dawngasm.app.appdata.xml`.

Packages are currently unsigned. macOS Gatekeeper and Windows SmartScreen will
warn on first launch until the signing and notarization work in Phase 16 is
configured with per-OS certificates.

## Signing

The packaging workflow signs artifacts only when the matching repository
secrets exist; otherwise it publishes unsigned packages.

- **Windows:** `WINDOWS_CERTIFICATE_PFX_BASE64` and
  `WINDOWS_CERTIFICATE_PASSWORD`. The MSI is signed with `signtool` and a
  DigiCert timestamp.
- **macOS:** `MACOS_CERTIFICATE_P12_BASE64`, `MACOS_CERTIFICATE_PASSWORD`,
  `MACOS_KEYCHAIN_PASSWORD`, `MACOS_SIGN_IDENTITY`, `APPLE_ID`,
  `APPLE_TEAM_ID`, and `APPLE_APP_PASSWORD`. The DMG is signed through
  Compose's `compose.desktop.mac.sign` properties, submitted with
  `notarytool`, and stapled.
- The bundled clearance helper is an executable inside the app resources
  directory. Verify it is included in the developer's signing/notarization
  scope before publishing a notarized build.

## State and logs

- Linux: `$XDG_CONFIG_HOME/dawngasm` (or `~/.config/dawngasm`)
- Windows: `%APPDATA%\Dawngasm`
- macOS: `~/Library/Application Support/Dawngasm`

Settings are a UTF-8 JSON file written atomically. Logs live under the
`logs/` subdirectory. Downloads go to `~/Music/Dawngasm` unless a folder is
chosen in Settings. `DAWNGASM_CONFIG_DIR`, `DAWNGASM_CACHE_DIR`, and
`DAWNGASM_MUSIC_DIR` override those locations for portable or test runs.
