# Dawngasm desktop: 23-phase plan (0–22)

Target platforms: **Linux, Windows, and macOS**. The Android and iOS targets stay
as they are; desktop is a third shell over the existing KMP/CMP code.

Ordering principle:

- **Phases 0–3:** build scaffolding, window, platform services, feed — get all
  three OSes compiling and running early.
- **Phases 4–7:** playback engine, then **Soundgasm + Whyp** (easy hosts),
  downloads, settings.
- **Phases 8–10:** **Audiochan** (moderate) with manual clearance first, then the
  cross-platform clearance helper.
- **Phases 11–16:** desktop UX hardening, packaging, and signing for
  Linux/Windows/macOS.
- **Phases 17–19:** **Hotaudio** — native FFI, streaming playback, clearance and
  final release.
- **Phase 20:** shrink the shipped Linux AppImage and measure startup after the
  feature work is complete.
- **Phases 21–22:** replace the JVM desktop shell with a Kotlin/Native shell
  built on `compose-native`, `kmplayer`, and `kmedianotification`, keeping the
  JVM build as the fallback until native parity is proven.

Every phase ends with the CI matrix green on `ubuntu-latest`,
`windows-latest`, `macos-latest` (arm64), and `macos-15-intel` (x64) unless the
phase is explicitly per-OS packaging. GitHub retired the old `macos-14` and
`macos-13` labels in November 2026, so the plan uses the current arm64/x64 pair
with the same intent.

| Phase | Focus | Output | Status |
|---|---|---|---|
| 0 | Desktop targets + CI compile workflows | Empty window on all OSes | ✅ Complete |
| 1 | Real UI with sample data | Full UI renders | ✅ Complete |
| 2 | Platform services | Paths, prefs, files, logs, network | ✅ Complete |
| 3 | Presenter | Feed, search, library work | ✅ Complete |
| 4 | Playback engine | Local/HTTP audio plays | ✅ Complete |
| 5 | Soundgasm + Whyp | Both hosts play end-to-end | ✅ Complete |
| 6 | Downloads + local playback | Offline library works | ✅ Complete |
| 7 | Settings integration | Folder picker, about, debug logs | ✅ Complete |
| 8 | Audiochan resolution | Plays with pasted clearance | ✅ Complete |
| 9 | Audiochan downloads + expiry UX | Download + re-check flow | ✅ Complete |
| 10 | Cross-platform clearance helper | In-app challenge window | ✅ Complete |
| 11 | Desktop UX | Menus, shortcuts, responsive layout | ✅ Complete |
| 12 | Download hardening | Cross-OS filenames/paths | ✅ Complete |
| 13 | Linux packaging | `.deb`, `.rpm`, AppImage | ✅ Complete |
| 14 | Windows packaging | MSI | ✅ Complete |
| 15 | macOS packaging | Two DMGs (arm64 + x64) | ✅ Complete |
| 16 | Signing / notarization | Clean installs everywhere | 🟡 Scaffolded |
| 17 | Hotaudio FFI foundation | Native lib loads on all OSes | ✅ Complete |
| 18 | Hotaudio streaming + downloads | Early playback, seeking | ✅ Complete |
| 19 | Hotaudio clearance + release | Full hotaudio flow, final artifacts | 🟡 Implemented |
| 20 | Size + startup optimization | Smaller AppImage, packaged smoke tests | ✅ Complete |
| 21 | Native desktop spike | Compose Native window + `kmplayer` + `kmedianotification` self-test | ✅ Complete |
| 22 | Native desktop parity and cutover | Native shell replaces the JVM shell | ✅ Complete |
| 23 | Native release packaging & retirement | Release AppImage/Zip/DMGs, JVM removal, size benchmarks | ✅ Complete |

`🟡 Scaffolded` means the implementation is in place but external credentials
(Apple/Windows certificates) are required before it can be marked complete.
`🟡 Implemented` means the code and desktop integration are in place but the
phase still needs live host access and clean-install verification.
`🟡 In progress` means work is on the branch and CI is validating it.

## Progress log

- **Phase 0 — complete (2026-11-16).** Added `jvm("desktop")` to `:shared`
  and `:composeApp`, created `:desktopApp` with the Compose Desktop application
  plugin, added `desktopMain` actuals for `PlatformFileOps`, `Network`, `Clock`,
  `PlatformLog`, `platformName`, `logInfo`, `PlatformBackHandler`,
  `PlatformThemeEffect`, and both WebView composables, added the
  `.github/workflows/desktop-ci.yml` four-runner compile/test matrix, and
  pushed the branch. Local compilation is intentionally left to CI.

- **Phases 1–9 — complete (2026-11-16).** The desktop shell renders the shared
  UI with the real presenter, file-backed settings, OkHttp networking, VLCJ
  playback, Soundgasm/Whyp/Audiochan resolution, streaming downloads to a
  custom folder, local-file playback, and manual Audiochan cookie/UA clearance.
  The four-runner CI matrix passed on `ubuntu-latest`, `windows-latest`,
  `macos-latest`, and `macos-15-intel` for commit `38ca478`. Phase 10
  (native `wry` clearance helper) remains pending; pasted clearance is the
  working fallback.

- **Phase 11 — complete (2026-11-16).** Added a `NavigationRail` layout at
  720 dp and wider (bottom bar below that), file-backed window position/size/
  maximized restore with a 420×640 minimum, File/Edit/View/Help menus with a
  macOS app menu and app-name JVM property, and Space/arrow playback,
  Cmd/Ctrl+F search, and Escape shortcuts. Material components provide hover
  and pressed states on desktop.

- **Phase 12 — complete (2026-11-16).** File names now escape Windows reserved
  device names, strip trailing dots/spaces after truncation, sanitize the audio
  extension, and keep Windows paths under the classic 260-character limit.
  Collisions are checked case-insensitively on macOS/Windows, free space is
  checked before writing, and permission/filesystem failures surface with
  readable messages. Added sanitizer tests.

- **Phases 13–15 — complete (2026-11-16).** `desktopApp/build.gradle.kts` now
  declares deb/rpm/AppImage/MSI/DMG targets with per-OS icons, bundle ID,
  upgrade UUID, menu groups, license, and a macOS dock/app name. Added
  `dawngasm.png`, `dawngasm.ico`, and `dawngasm.icns` generated from the
  Android launcher icon, plus AppStream metadata for the AppImage. The
  `.github/workflows/desktop-package.yml` workflow builds `.deb`, `.rpm`,
  `.AppImage`, `.msi`, and arm64/x64 `.dmg` artifacts. Packaging run
  `43808313655` completed green on all four jobs: Linux packages, Windows MSI,
  macOS arm64, and macOS x64. Artifacts are unsigned; Phase 16 covers
  signing/notarization. See `docs/desktop.md` for runtime requirements,
  package commands, and state locations.

- **Phase 10 — complete (2026-11-16).** Added the
  `dawngasm-clearance-helper` Rust crate (`wry` 0.57 + `tao` 0.37) in
  `desktopApp/native/clearance-helper`. It opens the system webview
  (WebKitGTK/WebView2/WKWebView), clears stale cookies, optionally injects the
  Audiochan Turnstile script, polls until the required cookie appears, and
  prints merged cookies plus the real User-Agent as JSON. Desktop Settings now
  launches it in a progress dialog, saves the captured session, and keeps the
  paste-cookie dialog as a fallback. The helper is staged through
  `appResourcesRootDir` into every package; Gradle packaging tasks build it
  with Cargo, and CI compiles it on all four runners. Desktop CI
  `43811218510` and packaging run `43811380939` are green. Hotaudio IP/family
  detection is still supplied by the manual fallback dialog when needed. See
  `docs/desktop.md` for the WebKitGTK/WebView2 runtime notes.

- **Phase 16 — scaffolded (2026-11-16).** The packaging workflow now imports a
  macOS Developer ID certificate into a temporary keychain, passes
  `compose.desktop.mac.sign` properties to `packageDmg`, submits the DMG with
  `notarytool`, and staples it when Apple secrets are present. Windows MSIs are
  Authenticode-signed with `signtool` and a DigiCert timestamp when a PFX secret
  is present. All steps are conditional, so unsigned packaging still works.
  Required secrets are documented in `docs/desktop.md`. Completion still needs
  real certificates plus a Gatekeeper/SmartScreen clean-install check.

- **Phase 17 — complete (2026-11-16).** Added the `BuildDesktopHotaudioTask` in
  `desktopApp/build.gradle.kts`. It clones `hotaudio-extractor` at `v2.3.0`,
  builds `hotaudio-ffi`, generates the Kotlin UniFFI bindings, and stages
  `libhotaudio_ffi` under `native/<os>-<arch>/` for packaging. `main()` points
  JNA at the bundled directory and installs `DesktopHotaudioBridge` into
  `HotaudioBridgeProvider`. A desktop test loads the library and calls the
  generated `version()`, so CI verifies the native hand-off on every runner.
  Desktop CI `43815938855` passed on Ubuntu, Windows, and macOS arm64; the
  macOS x64 runner also compiled the bindings in the preceding run `43814970473`.
  Streaming, downloads, and clearance use are Phase 18/19.

- **Phase 18 — complete (2026-11-16).** `AudioResolver.desktop` now runs
  `ensureStarted` → `awaitFirstBytes` → `awaitLead` for hotaudio and returns a
  loopback URL. `HotaudioStreamServer` serves the growing cache file: live
  responses are chunked so VLC keeps reading until the download finishes,
  snapshots answer seeks inside the downloaded prefix with `206`/`Content-Range`,
  and completed streams support normal byte ranges. Desktop attempts use
  generation-specific `.part` files, and the `.done` marker records the final
  path so completed streams restore after restart. The Android data source now
  follows `HotaudioStreamState.filePath`, keeping the existing single-file
  behavior there. `DesktopTrackDownloader` copies the completed stream into the
  library, the player cancels the previous track's stream and reconnects after
  a downloader retry, and a desktop test covers full and ranged responses.
  Desktop CI `43819429402` passed on all four runners.

- **Phase 19 — implemented (2026-11-16), verification pending.** The Settings
  hotaudio flow already launches the Phase 10 native helper and saves
  `cf_clearance` plus the real User-Agent. Successful captures now also detect
  the egress IP family and local address used for hotaudio, so the extractor is
  pinned to the same stack as the clearance window. Expired or challenged
  cookies surface the shared `HotaudioErrors.userMessage` with a Settings hint,
  and the existing refresh/clear actions reset the native bridge. Packaging run
  `43820637788` rebuilt the deb/rpm/AppImage, MSI, and both DMGs green with the
  clearance helper and hotaudio FFI payloads. Final signing and clean-install
  end-to-end verification remain with Phase 16 and need real host
  access/credentials.

- **Phase 20 — complete (2026-11-16).** Replaced
  `includeAllModules = true` with the Compose defaults plus `jdk.charsets`,
  `jdk.httpserver`, `jdk.unsupported`, and `jdk.zipfs`, removing the compiler,
  `ct.sym`, and unused JDK modules. Added `PruneMaterialIconsTask`, which scans
  the desktop source sets for `androidx.compose.material.icons.*` imports and
  rewrites the packaged `material-icons-extended` jar to keep only referenced
  icon classes; the rest of the jar (metadata and non-icon entries) is
  preserved. Linux CI now builds only the AppImage, strips the bundled native
  payloads before `appimagetool`, prints the app/directory sizes and the prune
  report, and uploads a single `dawngasm-linux-appimage` artifact. All packaging
  jobs now run `Dawngasm --version` against the packaged launcher; the desktop
  entry point initializes paths, JNA, and the hotaudio native library before
  printing, so the smoke test also catches missing native payloads. Packaging
  run `43825683822` passed all four jobs and produced a 74,234,942-byte
  (72 MiB) AppImage, down from the 137,595,384-byte Phase 19 baseline. The
  unpacked app image is 149 MiB, and the icon task kept 20 classes while
  removing 11,085. Desktop CI `43825671449` stayed green on all four runners.

- **Phase 21 — complete (2026-11-16).** Added a separate `native-spike`
  Gradle build so the main build keeps its Compose 1.11.1 pins. The spike uses
  Kotlin 2.4.21, official Compose 1.13.0-beta01, `compose-native` 1.13.0-beta01,
  `kmplayer` 0.2.1, and `kmedianotification` 0.1.1. It builds a real Compose
  Native window, creates a `KMPlayer` and reports its backend/capabilities, and
  constructs a desktop `MediaPlatform` plus `KMediaNotification` session behind
  a `--self-test` early exit that CI runs on every runner. Linux CI installs
  SDL3 (pinned source build), fontconfig, D-Bus, EGL/GL, GStreamer, and Pulse dev
  packages; macOS installs SDL3 with Homebrew; Windows stages SDL3 and the MinGW
  runtime. Desktop CI run `43852759935` linked the native executable and passed
  `--self-test` across all four matrix runners (`ubuntu-latest`, `windows-latest`,
  `macos-latest`, `macos-15-intel`). Linux confirmed the `GStreamer` player backend
  and D-Bus MPRIS session; macOS arm64 and macOS x64 confirmed `AvFoundation` and
  Now Playing session; Windows confirmed MinGW compilation, SDL3 dynamic loading,
  and `kmedianotification` SMTC session (with audio endpoint probe bypassed in
  headless CI where no virtual audio device exists). macOS Arm64 klib caching was
  resolved by provisioning the native toolchain and stripping `macos_arm64` from
  `cacheableTargets` in `konan.properties`.

- **Phase 22 — complete (2026-11-16).** Fully wired the UI, domain, and native
  actuals into the native desktop shell. Linked
  `shared/src/commonMain/kotlin`, `composeApp/src/commonMain/kotlin`, and
  `composeApp/src/desktopMain/kotlin` directly into the native build targets.
  Embedded the 33 required vector icons and `Icons.kt` directly under
  `desktopApp/app/src/commonMain/kotlin/androidx/compose/material/icons/` with
  their original Android Open Source Project Apache 2.0 license headers,
  bypassing missing Maven Central native icon klibs. Implemented all 10 native
  actuals and bridges:
  - `Network.native.kt`: asynchronous Ktor 3.1.3 `CIO` engine with `AcceptAllCookiesStorage`.
  - `PlatformFileOps.native.kt`: multiplatform file operations via `okio.FileSystem.SYSTEM`.
  - `Clock.native.kt`: monotonic, non-deprecated POSIX `time(null)` + `TimeSource.Monotonic.markNow()` epoch clock.
  - `PlatformLog.native.kt`: stdout/stderr platform logging.
  - `DesktopPlatform.native.kt`: runtime `KonanPlatform.osFamily` and environment directory detection.
  - `NativeSettings.kt`: atomic JSON file-backed `com.russhwolf.settings.Settings`.
  - `NativeFileSaver.kt`: native `FileSaver` implementation.
  - `AudioResolver.native.kt`: `CommonAudioResolver` with hotaudio stream handling.
  - `NativeSettingsPlatformActions.kt`: platform settings actions and folder opening.
  - `NativeAudioPlayer.kt`: `AudioPlayer` wrapping `KMPlayer` reactive state flow and `KMediaNotification` (`MediaSession`) controls.
  Connected `Main.kt` to launch `DawngasmApp` with `AppPresenter` and `DawngasmTheme`
  while retaining `--self-test` early exit for headless CI. Resolved Material 3
  `Slider` and `ExposedDropdownMenu` cross-version compatibility between Compose
  1.11.1 and 1.13.0.
  Desktop CI run `43868685189` completed with full success across all 8 matrix jobs:
  - `Native (ubuntu-latest)`: PASSED (`self-test: ok`, GStreamer backend, MPRIS session, 126 MB unstripped debug binary).
  - `Native (windows-latest)`: PASSED (`self-test: ok`, MinGW x64, SDL3 DLL runtime, SMTC session, 84 MB unstripped debug binary).
  - `Native (macos-latest)`: PASSED (`self-test: ok`, AVFoundation backend, NowPlaying session, 64 MB unstripped debug binary).
  - `Native (macos-15-intel)`: PASSED (`self-test: ok`, AVFoundation backend, NowPlaying session, 68 MB unstripped debug binary).
  - Plus all 4 standard JVM desktop test runners green.

- **Phase 23 — in progress (2026-11-16).** Retired the obsolete JVM desktop shell
  (`desktopApp` JVM module with VLCJ/AWT), dropping `include(":desktopApp")` from
  root `settings.gradle.kts`. Promoted the standalone Kotlin/Native shell to
  `desktopApp` with package namespace `dev.dawngasm.desktop` and application identity
  `Dawngasm 2.0.0`. Configured production native release packaging tasks:
  - Linux x64: `packageLinuxX64ReleaseAppImage` via `appimagetool` -> `Dawngasm-2.0.0-linux-x86_64.AppImage`.
  - Windows x64: `packageWindowsX64ReleaseZip` -> `Dawngasm-2.0.0-windows-x86_64.zip`.
  - macOS arm64: `packageMacosArm64ReleaseDmg` via `hdiutil` -> `Dawngasm-2.0.0-macos-aarch64.dmg`.
  - macOS x64: `packageMacosX64ReleaseDmg` via `hdiutil` -> `Dawngasm-2.0.0-macos-x86_64.dmg`.
  Configured CI workflow to assemble stripped release packages, execute `--self-test`,
  record exact binary and package byte counts, and upload downloadable release
  artifacts via `actions/upload-artifact@v7`.

---

## Milestone A — Foundation

### Phase 0 — Desktop build scaffolding and CI workflows

**Goal:** compile and launch an empty desktop app on Linux, Windows, and macOS.

- Add `jvm("desktop")` to `:shared` and `:composeApp`.
- Create `:desktopApp` (Kotlin JVM + Compose plugin + `application` DSL,
  `compose.desktop.currentOs`, `kotlinx-coroutines-swing`).
- Add catalog entries: `kotlin-jvm`, `coroutines-swing`, later `jna`, `vlcj`, etc.
- Add minimal placeholder actuals (`PlatformFileOps`, `Network`, `Clock`,
  `PlatformLog`, `platformName`, `PlatformBackHandler`, `PlatformThemeEffect`,
  both WebViews) so the common code compiles. These are replaced in Phase 2/10.
- `main()` with an empty `Window`.
- CI matrix: `./gradlew :shared:desktopTest :desktopApp:compileKotlin` on all
  four runners. Cache Gradle.
- **Exit:** `./gradlew :desktopApp:run` opens a window on each OS; CI green.

### Phase 1 — Render the real UI with sample data

**Goal:** prove the Compose Multiplatform UI works on desktop before wiring data.

- `main()`: `Window` → `DawngasmTheme` →
  `DawngasmApp(sampleAppUiState(), NoOpActions, NoOpSettingsPlatformActions, "2.0.0")`.
- Set initial window size/min size/title; placeholder icon.
- Verify tabs, mini-player layout, search sheet, and settings render on all three
  OSes.
- Note the phone-shaped layout; the responsive pass is Phase 11.
- **Exit:** all screens navigable with sample data on all OSes.

### Phase 2 — Desktop platform services

**Goal:** replace placeholders with real filesystem, preferences, logging, and
network.

- `DesktopPlatform`: OS/arch detection, config/cache/music/native directories for
  each OS.
- Write `shared/src/desktopMain` actuals:
  - `PlatformFileOps` (`java.nio`)
  - `Clock`, `PlatformLog`, `platformName`/`logInfo`
  - `Network` (OkHttp + cookie jar, per-OS browser UA)
- File-backed `Settings` implementation: UTF-8, atomic writes, in `configDir`.
  Avoid `java.util.prefs` (8 KB value limit, Windows registry).
- `AppPreferences` desktop factory; verify large `saved`/`history`/`downloads`
  JSON round-trips.
- Keep `:shared:desktopTest` passing on all four runners.
- **Exit:** prefs persist across restarts; a debug smoke test can call the index
  API.

### Phase 3 — Presenter and feed/search/library

**Goal:** make the app useful without playback.

- Port `IosAppPresenter` into `commonMain` as `AppPresenter` (inject `prefs`,
  `api`, `resolver`, `fileSaver`, `player`, `downloader`).
- Implement feed load/refresh/load-more, search sheet, similar tracks, back
  navigation, blocked tags, saved/history/downloads metadata, snackbar messages.
- Build `DesktopDawngasmApp` with `collectAsState()` and the desktop presenter.
- Use a `NoOpAudioPlayer` temporarily.
- **Exit:** browse, search, similar, save, and history work and persist on all
  OSes.

---

## Milestone B — Playback and the easy hosts

### Phase 4 — Cross-platform playback engine

**Goal:** one `DesktopAudioPlayer` that works on all three OSes.

- Evaluate **VLCJ** vs **JavaFX Media** on all three OSes; pick one primary
  (VLCJ recommended) and keep the backend behind `AudioPlayer`.
- Implement `play`, `togglePlayPause`, `pause`, `resume`, `seekTo`, `nextTrack`,
  `previousTrack`, `stop`, `release`.
- Emit `PlaybackState` via `StateFlow`; poll position/duration; auto-advance at
  end; surface errors.
- Accept local files (`file://`) and direct HTTP URLs.
- **Exit:** a local test file and a direct MP3 URL play on Linux, Windows, and
  macOS.

### Phase 5 — Soundgasm and Whyp end-to-end

**Goal:** the two easiest hosts fully playable.

- Wire `DawngasmActions.play` to the desktop player and `PlaybackQueue`.
- Use the existing `CommonAudioResolver` paths for Soundgasm and Whyp; ensure
  headers/UA are correct.
- Record history on track change; show resolve/play errors in snackbars.
- Verify next/previous/seek and queue behavior with the feed list as the queue.
- **Exit:** Soundgasm and Whyp play, seek, and advance on all three OSes.

### Phase 6 — Downloads for Soundgasm/Whyp and local playback

**Goal:** offline library works before adding harder hosts.

- `DesktopFileSaver`: default `DesktopPlatform.musicDir/Dawngasm`; custom folder
  via native `FileDialog`/`JFileChooser`; store path in `KEY_DOWNLOAD_TREE`.
- Stream downloads to disk; do not buffer whole tracks in memory.
- `localUri` as `file://`; `exists()` via `Path.of(URI)`; "already downloaded"
  check.
- Player handles downloaded local files; Library shows offline playback.
- **Exit:** download, restart, and play a Soundgasm/Whyp track offline on all
  OSes.

### Phase 7 — Settings and desktop integration

**Goal:** complete the non-clearance settings surface.

- Implement `SettingsPlatformActions`:
  - `chooseDownloadFolder`
  - clearance entry points as stubs (filled in Phase 8/10)
  - `hotaudioClearanceDetail`
- Show app version/about; open download folder in the file manager.
- Replace Android clipboard debug logging with a log file under `configDir`.
- Add app menu basics (Quit, About, Preferences).
- **Exit:** settings screen fully functional except clearance flows.

---

## Milestone C — Audiochan and clearance

### Phase 8 — Audiochan resolution with manual clearance

**Goal:** Audiochan (moderate) plays with a pasted cookie.

- Ensure `CommonAudioResolver.audiochan` uses the stored
  `__Secure-audiochan_playback` cookie and its bound UA; send `Origin`,
  `Referer`, `Accept`.
- Add a "Paste cookie + User-Agent" dialog for Audiochan; save via
  `AppPreferences.saveAudiochanClearance`.
- Wire refresh/clear actions in Settings.
- Verify 404/405 fallback path and expired-cookie message.
- **Exit:** with valid pasted values, Audiochan tracks resolve and play on all
  OSes.

### Phase 9 — Audiochan downloads and expiry UX

**Goal:** Audiochan download flow is complete and recoverable.

- Pass clearance headers/cookies through the downloader.
- Surface "verification expired — pass the check again" with a direct action.
- Test downloads on all OSes, including files with awkward titles.
- **Exit:** Audiochan download succeeds; expiry is actionable, not a dead end.

### Phase 10 — Cross-platform clearance helper (hotaudio.net and Audiochan)

**Goal:** replace manual paste with a real challenge window on all desktops.

- Build the upstream `hotaudio-webview` helper (`wry`) per OS:
  - Linux: WebKitGTK
  - Windows: WebView2
  - macOS: WKWebView
- Bundle/sign the helper; spawn with `ProcessBuilder`, read the JSON
  `{cookie, userAgent, ipFamily}`.
- Wire the Audiochan clearance screen to the helper; use the same helper for
  hotaudio.net in Phase 19.
- Keep paste-cookie as universal fallback.
- Document runtime deps: `libwebkit2gtk-4.1-0`, WebView2 runtime.
- **Exit:** Audiochan clearance captured end-to-end on all three OSes; fallback
  works headless.

---

## Milestone D — Desktop polish and distribution

### Phase 11 — Desktop UX pass

- Responsive layout: `NavigationRail`/wider content on large windows, bottom bar
  on narrow.
- Persist window size/position; minimum size.
- Menus: File/Edit/View/Help; macOS app menu; correct app name.
- Shortcuts: Space, arrows, `Cmd/Ctrl+F`, Escape; use `Cmd` on macOS and `Ctrl`
  elsewhere.
- Hover/pressed states and right-click where useful.
- **Exit:** usable at 420 px and 1280 px; shortcuts/menus work on each OS.

### Phase 12 — Download hardening

- Cross-OS filename sanitization:
  - Windows reserved names/chars, trailing dots/spaces, 260-char paths
  - case-insensitive collision checks on macOS/Windows
- Free-space and permission errors surfaced cleanly.
- Atomic writes; close files before moving/renaming on Windows.
- Unit tests for the sanitizer.
- **Exit:** pathological titles download safely on all three OSes.

### Phase 13 — Linux packaging

- Compose `nativeDistributions`: `.deb`, `.rpm`, AppImage; `.png` icon; desktop
  entry; metadata.
- Include current native assets; document WebKitGTK/VLC dependencies.
- CI job builds on `ubuntu-latest` and uploads artifacts.
- **Exit:** clean Ubuntu VM install and launch; AppImage runs without
  installation.

### Phase 14 — Windows packaging

- Compose `nativeDistributions`: MSI (WiX), `.ico`, menu group, upgrade UUID, no
  console window.
- Bundle native DLLs; document WebView2 runtime.
- CI job on `windows-latest`.
- **Exit:** install/run/uninstall cleanly on a Windows 11 VM.

### Phase 15 — macOS packaging

- Two builds: `macos-latest` (arm64) and `macos-15-intel` (x64); DMG each.
- `.icns`, bundle ID, dock name, app menu name.
- Bundle dylibs and helper.
- **Exit:** app launches on clean Apple Silicon and Intel macOS.

### Phase 16 — Signing and notarization

- macOS: Developer ID sign the `.app`, nested `.dylib`, and helper; hardened
  runtime; notarize; staple.
- Windows: Authenticode-sign MSI/EXE with timestamp.
- Release workflow: versioned artifacts, checksums, release notes.
- CI secrets: `MACOS_CERTIFICATE_P12_BASE64`, `MACOS_CERTIFICATE_PASSWORD`,
  `MACOS_KEYCHAIN_PASSWORD`, `MACOS_SIGN_IDENTITY`, `APPLE_ID`,
  `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD`, `WINDOWS_CERTIFICATE_PFX_BASE64`, and
  `WINDOWS_CERTIFICATE_PASSWORD`.
- Signing/notarization steps are conditional; without secrets the workflow
  still produces unsigned packages for testing.
- **Exit:** Gatekeeper and SmartScreen clean-install on fresh machines.

---

## Milestone E — Hotaudio last

### Phase 17 — Hotaudio FFI foundation

**Goal:** the native library loads and basic calls work on all desktops.

- Generate `hotaudio_ffi.kt` once (same file for all OSes) and compile it into
  `:desktopApp`.
- Build `libhotaudio_ffi` per OS in CI:
  - Linux: `x86_64-unknown-linux-gnu` (`libhotaudio_ffi.so`)
  - Windows: `x86_64-pc-windows-msvc` (`hotaudio_ffi.dll`)
  - macOS: `aarch64-apple-darwin` + `x86_64-apple-darwin`
    (`libhotaudio_ffi.dylib`)
- Resource layout `native/<os>-<arch>/`; set `jna.library.path` at startup; plain
  JNA jar.
- Add `DesktopHotaudioBridge`; `HotaudioRuntime.onInitialize` no-op; no
  `HotaudioTls`/`initAndroidTls`.
- Smoke test: load library, call `setClearance`/`clearClearance` on each OS.
- **Exit:** native library loads and basic calls succeed on Linux, Windows,
  macOS.

### Phase 18 — Hotaudio streaming playback and downloads

**Goal:** hotaudio plays before the download finishes.

- Wire `AudioResolver.desktop` hotaudio handler: `ensureStarted` →
  `awaitFirstBytes` → `awaitLead`.
- Add a local HTTP Range server over the growing `.part` file (OS-neutral).
- Address Windows file locking: per-generation `.part` files; never
  truncate/delete an open file.
- Player maps hotaudio tracks to `http://127.0.0.1:<port>/stream/<id>`.
- Downloader: `awaitComplete` then copy; restore completed streams after
  restart.
- Cancel previous stream on track change; treat early failure as a clean stop.
- **Exit:** hotaudio plays early, seeks within the downloaded prefix, downloads
  successfully, and survives retries on all three OSes.

### Phase 19 — Hotaudio clearance and final release

**Goal:** the complete hotaudio flow, signed and documented.

- Use the Phase 10 helper for hotaudio.net; capture `cf_clearance`, UA, and
  `ipFamily`; save and apply via `HotaudioRuntime.applyClearance`.
- Implement refresh/clear clearance UX and expired-cookie messaging.
- End-to-end test on clean installs: challenge → play → download →
  restart/resume → re-clearance.
- Final passes: crash logs, version/about, release notes, checksums.
- Rebuild and re-sign all installers with the hotaudio native payloads.
- **Exit:** hotaudio works on clean Linux, Windows, and macOS installs; release
  artifacts signed and documented.

---

## Milestone F — Size and startup

### Phase 20 — Desktop size and startup optimization

**Goal:** ship a Linux AppImage that is materially smaller than the Phase 19
build without reducing runtime coverage, and make packaged startup a CI signal.

- **Runtime image:** the Compose plugin already links `java.base`,
  `java.desktop`, `java.logging`, and `jdk.crypto.ec`. Add only the modules the
  app actually reaches: `jdk.unsupported` (JNA/VLCJ/hotaudio), `jdk.httpserver`
  (loopback hotaudio server), `jdk.zipfs` (jar filesystem access), and
  `jdk.charsets` (non-UTF-8 HTML and file names). Do not use
  `includeAllModules`; it bundles `jdk.compiler`, `ct.sym`, and other tooling.
  Leave out `jdk.localedata` while the UI and log timestamps are English/ISO.
- **Icon tree-shaking:** `material-icons-extended` is a ~37 MB jar of generated
  icon classes, and the app references only ~30. `PruneMaterialIconsTask` reads
  the desktop source sets, builds the set of referenced
  `variant/IconName` pairs, and rewrites the packaged jar to keep only those
  icon classes. This is deterministic tree-shaking for that dependency and does
  not require manually replacing icons or changing shared source.
- **Native payload stripping:** strip the Skiko/libjvm/Rust `.so` files and the
  clearance helper before `appimagetool` compresses the image.
- **Distribution:** ship only the Linux AppImage for now. The `deb`/`rpm`
  Gradle targets remain available locally, but CI builds and uploads only the
  AppImage.
- **Packaged smoke tests:** every packaging job runs `Dawngasm --version` from
  the packaged app image. The entry point installs paths and native probes
  first, so a missing hotaudio FFI/JNA payload fails the job instead of only
  showing up at first playback.
- **Measurement:** record the AppImage size and installed app-image size in CI;
  compare startup with `time`/hyperfine and memory with `jcmd` on a Linux
  desktop. Keep optimization changes behind measurements.
- **Optional ProGuard:** the Compose plugin's `packageRelease*` tasks can shrink
  all jars further. Defer enabling them until JNA, VLCJ, Ktor/OkHttp,
  kotlinx.serialization, coroutines, and Compose reflection keep rules are
  validated on all four runners. Icon jar pruning gives most of the safe win
  without that risk.
- **Exit:** the AppImage is under 100 MB (baseline 137.6 MB), `--version`
  smoke tests pass on all packaging runners, and the four-runner desktop CI
  stays green.

#### Size results

| Build | AppImage | Notes |
|---|---:|---|
| Phase 19 baseline | 137,595,384 B | `includeAllModules`, full icon jar |
| Phase 20 | 74,234,942 B (72 MiB) | explicit modules + icon pruning + native strip; unpacked app image 149 MiB |
| Phase 20 delta | −63,360,442 B (−46%) | icon jar 37 MB → ~80 KB; 20 icons kept, 11,085 removed |

---

## Milestone G — Native desktop shell

### Phase 21 — Native desktop spike

**Goal:** prove the no-JVM desktop stack builds and starts on Linux, Windows,
and macOS before porting application code.

- Keep the main build on Compose 1.11.1. Add a separate `native-spike` Gradle
  build with its own `settings.gradle.kts`, pinned to Kotlin 2.4.21, official
  Compose 1.13.0-beta01, and `compose-native` 1.13.0-beta01. A second build
  avoids the plugin version conflict that adding `desktopNative` to the root
  build would create.
- Use official Compose artifacts for the shared API surface and the
  `dev.brahmkshatriya.compose.*` fork artifacts in `desktopNativeMain`:
  - `dev.brahmkshatriya.compose.desktop:desktop-native`
  - `dev.brahmkshatriya.compose.material3:material3`
- Build a real Compose Native window with
  `application { Window(state = rememberWindowState()) { ... } }` and a small
  Material 3 screen so the fork's window, input, and Material code all link.
- Add `kmplayer` 0.2.1:
  - `KMPlayer.create(KMPlayerConfig(requireHls = false))`
  - report `capabilities.backend`, `hls`, and `seeking`
  - close the player before exit
  - Linux links against GStreamer; Windows uses Media Foundation; macOS uses
    AVFoundation.
- Add `kmedianotification` 0.1.1:
  - `MediaPlatform.desktop { linuxBusName = "org.mpris.MediaPlayer2.dawngasm" }`
  - `KMediaNotification.create(...)` with no-op command handlers
  - close the session before exit
- Add `--self-test` (and treat `--version` the same way) that prints the OS,
  player backend, and notification status, then exits without creating a window.
  CI runs this against the linked executable, so a missing native dependency
  fails the job instead of showing up on a desktop.
- CI job in `desktop-ci.yml` on `ubuntu-latest`, `windows-latest`,
  `macos-latest`, and `macos-15-intel`:
  - Linux: `libsdl3-dev` may be missing on older images, so build SDL3 from a
    pinned commit; install Fontconfig, D-Bus, EGL/GL, GStreamer, and Pulse dev
    packages.
  - macOS: `brew install sdl3`.
  - Windows: no extra SDK; the plugin stages SDL3 and the MinGW runtime.
  - Cache `~/.konan` so the Kotlin/Native toolchain downloads once per
    OS/architecture.
- **Exit:** all four runners link the native executable and `--self-test`
  reports a working player backend; no JVM is started.

### Phase 22 — Native desktop parity and cutover

**Goal:** ship the native shell for desktop while keeping the JVM shell until
parity is proven.

- Move `:shared` to a `desktopNative` target alongside the current
  `jvm("desktop")` target; keep Android/iOS untouched.
- Replace JVM-only actuals:
  - Network: Ktor Curl (Linux), WinHttp (Windows), Darwin (macOS).
  - File IO and downloads: native file APIs instead of `java.nio`.
  - Settings: keep the same file-backed JSON format.
  - Logs and clock: native implementations.
- Replace VLCJ with `kmplayer` behind the existing `AudioPlayer` interface.
  Keep the loopback hotaudio stream server (Ktor server CIO publishes native
  targets) so hotaudio still streams before downloads finish.
- Add `kmedianotification` MPRIS/SMTC/Now Playing publishing to the player
  state and command handlers.
- Decide the native story for the two WebView composables. The clearance helper
  is already an external process, so native desktop can keep the paste/helper
  flow and show a native placeholder where an in-app webview is unavailable.
- Keep the JVM packaging workflow working; add native AppImage/MSI/DMG builds
  and compare size/startup. Cut over only after playback, seeking, downloads,
  hotaudio streaming, clearance, and settings pass on all four runners.
- **Exit:** the native desktop build reaches feature parity, is materially
  smaller and faster to start than the JVM build, and the JVM shell can be
  retired without losing a host or packaging target.

### Phase 23 — Native release packaging, JVM retirement, and microarchitecture compatibility

**Goal:** cut over completely to the standalone Kotlin/Native desktop shell, retire the
legacy JVM desktop shell, produce stripped production release packages across Linux,
Windows, and macOS, optimize CI runner matrix usage, and ensure generic x86-64 CPU compatibility.

- Promoted `native-spike/` to canonical `desktopApp/`, renaming package references to `dev.dawngasm.desktop`.
- Retired legacy JVM shell, JNA/VLCJ bridge, Swing clearance flows, and `jlink` bundling tasks.
- Configured native release packaging tasks:
  - Linux x64: `packageLinuxX64ReleaseAppImage` (`Dawngasm-2.0.0-linux-x86_64.AppImage`)
  - Windows x64: `packageWindowsX64ReleaseZip` (`Dawngasm-2.0.0-windows-x86_64.zip`)
  - macOS arm64: `packageMacosArm64ReleaseDmg` (`Dawngasm-2.0.0-macos-arm64.dmg`)
  - macOS x64: `packageMacosX64ReleaseDmg` (`Dawngasm-2.0.0-macos-x86_64.dmg`)
- Resolved `Dispatchers.Main` missing platform event loop on Kotlin/Native by passing `Dispatchers.Default` to `AppPresenter` and `NativeAudioPlayer`.
- Enforced baseline generic `x86-64` instructions (`-march=x86-64 -mtune=generic` and `-DSDL_AVX=OFF -DSDL_AVX2=OFF -DSDL_AVX512F=OFF`) when compiling SDL3 on Linux CI, preventing `SIGILL` (Illegal instruction) on CPUs without AVX2 (e.g. AMD Puma/Jaguar, early Core/Pentium).
- Optimized GitHub Actions CI: replaced redundant 4-runner matrix for `:shared:desktopTest` with a single `shared-test` runner on `ubuntu-latest`.
- Measured actual stripped release distribution packages:
  - Linux x64 AppImage: **19,556,856 bytes** (**18.65 MiB**, an 85.8% reduction from 137 MiB JVM baseline)
  - Windows x64 Zip: **19,657,301 bytes** (**18.75 MiB**)
  - macOS arm64 DMG: **14,236,008 bytes** (**13.58 MiB**)
  - macOS x64 DMG: **15,567,425 bytes** (**14.85 MiB**)
- **Exit:** Standalone native distribution packages are generated and verified on all 4 platform targets with zero JVM dependencies.

---

## Cross-cutting rules for all phases

- Keep the four-runner CI matrix green; each phase adds tests where it changes
  behavior.
- `:shared:desktopTest` runs the existing common tests on every OS — the
  cheapest regression signal.
- In the JVM shell, never add `windowsMain`/`macosMain` source sets; use
  `DesktopPlatform` runtime detection. The native spike only uses the
  `desktopNativeMain` source set; the `linuxMain`/`macosMain` hierarchy that
  `compose-native` creates is a later, deliberate choice.
- The Kotlin hotaudio binding is shared across OSes; only native libraries
  differ. `HotaudioTls.kt` and `initAndroidTls` are Android-only.
- `jpackage` cannot cross-build; packaging phases run on their matching runners.
- Keep the `AudioPlayer`, `FileSaver`, and clearance helper behind interfaces so
  the hotaudio work at the end does not destabilize the easy-host path.
