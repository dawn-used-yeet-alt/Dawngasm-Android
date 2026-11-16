# Dawngasm desktop: 20-phase plan (0–19)

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

Every phase ends with the CI matrix green on `ubuntu-latest`,
`windows-latest`, `macos-14` (arm64), and `macos-13` (x64) unless the phase is
explicitly per-OS packaging.

| Phase | Focus | Output | Status |
|---|---|---|---|
| 0 | Desktop targets + CI compile workflows | Empty window on all OSes | ✅ Complete |
| 1 | Real UI with sample data | Full UI renders | ⬜ Pending |
| 2 | Platform services | Paths, prefs, files, logs, network | ⬜ Pending |
| 3 | Presenter | Feed, search, library work | ⬜ Pending |
| 4 | Playback engine | Local/HTTP audio plays | ⬜ Pending |
| 5 | Soundgasm + Whyp | Both hosts play end-to-end | ⬜ Pending |
| 6 | Downloads + local playback | Offline library works | ⬜ Pending |
| 7 | Settings integration | Folder picker, about, debug logs | ⬜ Pending |
| 8 | Audiochan resolution | Plays with pasted clearance | ⬜ Pending |
| 9 | Audiochan downloads + expiry UX | Download + re-check flow | ⬜ Pending |
| 10 | Cross-platform clearance helper | In-app challenge window | ⬜ Pending |
| 11 | Desktop UX | Menus, shortcuts, responsive layout | ⬜ Pending |
| 12 | Download hardening | Cross-OS filenames/paths | ⬜ Pending |
| 13 | Linux packaging | `.deb`, `.rpm`, AppImage | ⬜ Pending |
| 14 | Windows packaging | MSI | ⬜ Pending |
| 15 | macOS packaging | Two DMGs (arm64 + x64) | ⬜ Pending |
| 16 | Signing / notarization | Clean installs everywhere | ⬜ Pending |
| 17 | Hotaudio FFI foundation | Native lib loads on all OSes | ⬜ Pending |
| 18 | Hotaudio streaming + downloads | Early playback, seeking | ⬜ Pending |
| 19 | Hotaudio clearance + release | Full hotaudio flow, final artifacts | ⬜ Pending |

## Progress log

- **Phase 0 — complete (2026-11-16).** Added `jvm("desktop")` to `:shared`
  and `:composeApp`, created `:desktopApp` with the Compose Desktop application
  plugin, added `desktopMain` actuals for `PlatformFileOps`, `Network`, `Clock`,
  `PlatformLog`, `platformName`, `logInfo`, `PlatformBackHandler`,
  `PlatformThemeEffect`, and both WebView composables, added the
  `.github/workflows/desktop-ci.yml` four-runner compile/test matrix, and
  pushed the branch. Local compilation is intentionally left to CI.

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

- Two builds: `macos-14` (arm64) and `macos-13` (x64); DMG each.
- `.icns`, bundle ID, dock name, app menu name.
- Bundle dylibs and helper.
- **Exit:** app launches on clean Apple Silicon and Intel macOS.

### Phase 16 — Signing and notarization

- macOS: Developer ID sign the `.app`, nested `.dylib`, and helper; hardened
  runtime; notarize; staple.
- Windows: Authenticode-sign MSI/EXE with timestamp.
- Release workflow: versioned artifacts, checksums, release notes.
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

## Cross-cutting rules for all phases

- Keep the four-runner CI matrix green; each phase adds tests where it changes
  behavior.
- `:shared:desktopTest` runs the existing common tests on every OS — the
  cheapest regression signal.
- Never add `windowsMain`/`macosMain` source sets; use `DesktopPlatform` runtime
  detection.
- The Kotlin hotaudio binding is shared across OSes; only native libraries
  differ. `HotaudioTls.kt` and `initAndroidTls` are Android-only.
- `jpackage` cannot cross-build; packaging phases run on their matching runners.
- Keep the `AudioPlayer`, `FileSaver`, and clearance helper behind interfaces so
  the hotaudio work at the end does not destabilize the easy-host path.
