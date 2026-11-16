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

- **Linux:** `libwebkit2gtk-4.1-0`. The AppImage does not declare this
  dependency yet — install `libwebkit2gtk-4.1-0` if the clearance window does
  not open.
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
# Linux: jpackage app image (the AppImage is assembled from this)
./gradlew :desktopApp:createDistributable

# Linux installers are still available locally
./gradlew :desktopApp:packageDeb :desktopApp:packageRpm

# Windows
./gradlew :desktopApp:packageMsi

# macOS (arm64 or x64 host)
./gradlew :desktopApp:packageDmg
```

The packaging workflow (`.github/workflows/desktop-package.yml`) currently
ships only the Linux AppImage plus the Windows MSI and macOS DMGs. The Linux
job builds `createDistributable`, smoke-tests the packaged launcher, strips the
bundled native libraries, wraps the app image with `appimagetool`, and adds
AppStream metadata from `desktopApp/icons/dev.dawngasm.app.appdata.xml`.
`.deb`/`.rpm` remain local Gradle targets until distro packaging is revisited.

Packages are currently unsigned. macOS Gatekeeper and Windows SmartScreen will
warn on first launch until the signing and notarization work in Phase 16 is
configured with per-OS certificates.

## Size and startup

The desktop runtime uses an explicit jlink module list instead of
`includeAllModules`: the Compose defaults (`java.base`, `java.desktop`,
`java.logging`, `jdk.crypto.ec`) plus `jdk.unsupported` for JNA, `jdk.httpserver`
for the hotaudio loopback server, `jdk.zipfs`, and `jdk.charsets`. Adding a new
library that needs another JDK module shows up as a `ClassNotFoundException` in
a packaged run; add the module to `nativeDistributions.modules(...)` rather than
going back to `includeAllModules`.

`material-icons-extended` is pruned after `createDistributable` and before any
installer is built. `PruneMaterialIconsTask` scans `composeApp/src`,
`shared/src`, and `desktopApp/src` for `androidx.compose.material.icons.*`
imports, keeps the matching generated icon classes, and removes the rest from
the packaged jar. It writes a report to
`desktopApp/build/reports/material-icons-prune.txt`. Creating a new icon
reference needs no extra step; the task reruns with the app image. If the
dependency is ever removed, the task becomes a no-op.

Packaging jobs run `Dawngasm --version` against the packaged launcher. The
desktop entry point initializes platform directories, JNA, and the hotaudio
native library before printing, so the smoke test catches missing runtime and
native payloads. The Linux job also strips the bundled native libraries before
`appimagetool` compresses the image. Use `:desktopApp:suggestModules` only as a
hint when adding dependencies; its output can be incomplete or overly broad.

The Phase 19 AppImage was 137,595,384 bytes with the full JDK module image and
the complete 37 MB icon jar. The Phase 20 build is **74,234,942 bytes
(72 MiB)**, a 46% reduction, with a 149 MiB unpacked app image. The ProGuard
`packageRelease*` tasks remain a later option: they can shrink all jars, but
need a validated keep-rule set for JNA, VLCJ, Ktor/OkHttp,
kotlinx.serialization, coroutines, and Compose reflection.

## Native desktop migration

The shipping desktop shell is still the JVM build above. Phase 21 adds a
separate `native-spike` Gradle build that links a Kotlin/Native executable with
no JVM, using Compose Native plus the `kmplayer` and `kmedianotification`
libraries. It is intentionally a second build: the root project stays on
Compose 1.11.1 while the spike uses Compose 1.13.0-beta01 and `compose-native`
1.13.0-beta01.

The spike builds a Compose Native window and supports `--self-test`, which
prints the OS, the `kmplayer` backend/capabilities, and the media-notification
status before exiting without a window. Desktop CI runs that self-test on every
runner so missing native dependencies fail the build.

Linux needs SDL3, Fontconfig, D-Bus, EGL/GL, GStreamer, and Pulse development
packages; CI builds SDL3 from a pinned commit because older Ubuntu images do not
ship `libsdl3-dev`. macOS uses `brew install sdl3`. Windows needs no extra SDK
because the Compose Native plugin stages SDL3 and the MinGW runtime.

`kmplayer` uses GStreamer on Linux, Media Foundation on Windows, and
AVFoundation on macOS, so it can replace VLCJ without asking users to install
VLC. `kmedianotification` provides MPRIS on Linux and SMTC on Windows.
Phase 22 covers porting `:shared` and `:composeApp` to the native target and
cutting over once playback, hotaudio streaming, downloads, clearance, and
settings reach parity.

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

## Hotaudio native library

hotaudio streaming uses the native extractor from `hotaudio-extractor`
`v2.3.0`. `:desktopApp:compileKotlin` runs `BuildDesktopHotaudioTask`, which
clones that tag, builds `hotaudio-ffi`, generates the Kotlin UniFFI bindings,
and stages `libhotaudio_ffi` under `native/<os>-<arch>/`. The first build needs
Rust and network access; later builds reuse the staged outputs.

At startup the app points JNA at the bundled directory and installs
`DesktopHotaudioBridge`. `DAWNGASM_HOTAUDIO_NATIVE_DIR` overrides the search
path for a locally built library. A desktop test loads the library and calls
`version()`/`setClearance()`/`clearClearance()` so CI catches missing or
mismatched native payloads.

## Hotaudio streaming

hotaudio writes concatenated fragmented MP4. Desktop downloads go to the cache
directory under `hotaudio/`, and a loopback HTTP server exposes the growing file
to VLC on `http://127.0.0.1:<port>/stream/<track-id>`. Live responses use
chunked transfer so playback continues while the download appends; seeks inside
the already-downloaded prefix use byte ranges. The server binds to loopback
only and stops when the app quits.

Each download attempt uses its own `<track-id>.gen<N>.m4a.part` file, because
Windows cannot truncate or delete a file that the player still has open. The
completed `.done` marker records the final path, and downloading copies that
file into the library. A successful clearance capture also records the IP
family/local address used for `hotaudio.net` so playback and the native
extractor use the same network stack.

## State and logs

- Linux: `$XDG_CONFIG_HOME/dawngasm` (or `~/.config/dawngasm`)
- Windows: `%APPDATA%\Dawngasm`
- macOS: `~/Library/Application Support/Dawngasm`

Settings are a UTF-8 JSON file written atomically. Logs live under the
`logs/` subdirectory. Downloads go to `~/Music/Dawngasm` unless a folder is
chosen in Settings. `DAWNGASM_CONFIG_DIR`, `DAWNGASM_CACHE_DIR`, and
`DAWNGASM_MUSIC_DIR` override those locations for portable or test runs.

## Native desktop shell (spike & Phase 21/22 migration)

Phase 21 validates a Kotlin/Native desktop shell built on `compose-native`
(`dev.brahmkshatriya.compose`), `kmplayer`, and `kmedianotification`. It eliminates
the JVM, Java runtime, and `jlink` runtime image completely from the desktop
distribution.

### Stack and dependencies

- **Compose Native** (`1.13.0-beta01`): Pure Kotlin/Native Compose UI fork using SDL3
  for windowing and rendering.
- **KMPlayer** (`0.2.1`): Multiplatform native audio/video playback mapping directly to
  host platform multimedia APIs:
  - Linux: GStreamer
  - macOS: AVFoundation
  - Windows: Microsoft Media Foundation
- **KMediaNotification** (`0.1.1`): Desktop OS media control integration:
  - Linux: D-Bus MPRIS (`org.mpris.MediaPlayer2`)
  - Windows: System Media Transport Controls (SMTC)
  - macOS: Now Playing info center
- **Kotlin/Native** (`2.4.21`): Direct machine code compilation via LLVM.

### Build and package targets

The native desktop application lives in the `desktopApp/` Gradle subproject:

- **Linux x64**: `cd desktopApp && ./gradlew :app:packageLinuxX64ReleaseAppImage`
- **Windows x64**: `cd desktopApp && ./gradlew :app:packageWindowsX64ReleaseZip`
- **macOS Arm64**: `cd desktopApp && ./gradlew :app:packageMacosArm64ReleaseDmg`
- **macOS x64 (Intel)**: `cd desktopApp && ./gradlew :app:packageMacosX64ReleaseDmg`

Distribution packages are output to `desktopApp/app/build/distributions/`.

### Headless self-test

The binary provides a `--self-test` early exit that executes without creating a UI
window, verifying platform player initialization and media notification sessions:

```bash
# General self-test (Linux and macOS)
./desktopApp/app/build/bin/linuxX64/releaseExecutable/app.kexe --self-test

# Windows headless CI (skips WASAPI audio render endpoint check where no sound device exists)
desktopApp/app/build/bin/mingwX64/releaseExecutable/app.exe --self-test --skip-audio-probe
```

### Compiler cache handling (macOS Arm64)

Kotlin 2.4.21 defaults to static klib caching on Tier 1 targets like `macos_arm64`,
which encounters IR constructor binding errors on `ui-unit-macosarm64.klib`. CI
provisions the native distribution via `:app:compileKotlinMacosArm64` and strips
`macos_arm64` from `cacheableTargets` in `konan.properties`, cleanly compiling
without static klib cache conflicts (matching `macosX64` Tier 2 behavior).

### Native Release Distribution Sizes (Phase 23)

The native shell links the full Compose Multiplatform UI (`DawngasmApp`), presenters,
models, settings, and player backends into standalone binaries without any JVM
dependency. Production release distributions are stripped and compressed:

| Platform | Format | Release Package | Exact Package Size |
| :--- | :--- | :--- | :--- |
| **Linux (x64)** | AppImage | `Dawngasm-2.0.0-linux-x86_64.AppImage` | **19,556,856 bytes** (18.65 MiB) |
| **Windows (x64)** | Zip | `Dawngasm-2.0.0-windows-x86_64.zip` | **19,657,301 bytes** (18.75 MiB) |
| **macOS (arm64)** | DMG | `Dawngasm-2.0.0-macos-arm64.dmg` | **14,236,008 bytes** (13.58 MiB) |
| **macOS (x64)** | DMG | `Dawngasm-2.0.0-macos-x86_64.dmg` | **15,567,425 bytes** (14.85 MiB) |

*Compared to the original Phase 19 JVM AppImage baseline of 137 MiB (and Phase 20 optimized JVM AppImage of 72 MiB), the standalone native Linux AppImage achieves an **85.8% size reduction** while booting instantly with zero JVM, JRE, or VLC dependencies.*

### Architecture and Instruction Set Compatibility

To ensure compatibility across all x86-64 microarchitectures (including older AMD Puma/Jaguar and Intel pre-Haswell CPUs lacking AVX2/AVX-512):
- SDL3 on Linux x86_64 is compiled in CI with explicit generic flags: `-DCMAKE_C_FLAGS="-march=x86-64 -mtune=generic" -DCMAKE_CXX_FLAGS="-march=x86-64 -mtune=generic" -DSDL_AVX=OFF -DSDL_AVX2=OFF -DSDL_AVX512F=OFF`.
- This prevents the compiler from emitting AVX/AVX2 vector instructions (such as `vinserti128`), ensuring the binary executes cleanly without `SIGILL` (Illegal instruction) on baseline x86-64 hardware.
- Coroutine dispatching on native desktop relies on `Dispatchers.Default` for `AppPresenter` and audio event processing to avoid the unimplemented platform main dispatcher error on native targets.
- Networking uses target-native HTTP engines instead of pure Kotlin CIO (`io.ktor:ktor-client-curl` on Linux via system `libcurl.so.4`, `io.ktor:ktor-client-winhttp` on Windows via `winhttp.dll`, and `io.ktor:ktor-client-darwin` on macOS via `NSURLSession`). This enables full native TLS sessions and avoids the `TLS sessions are not supported on Native platform` limitation of Ktor CIO on non-JVM platforms.

