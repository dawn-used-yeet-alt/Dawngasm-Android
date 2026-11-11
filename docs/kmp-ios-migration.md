# KMP + iOS migration plan

Status: planning
Branch: `kmp-ios-migration`
Last updated: 2026-11-11

## Goal

Move Dawngasm from an Android-only Gradle app to a Kotlin Multiplatform project with:

- one shared Compose Multiplatform UI that looks and behaves exactly like today's Android UI;
- shared models, search, networking, settings, source resolvers, and playback contracts;
- an Android app shell and an iOS app shell;
- iOS compilation running in GitHub Actions on macOS runners, so no local Mac is required.

The migration order is deliberate:

1. add iOS as a compilation target;
2. port the Android-only UI to Compose Multiplatform with pixel parity;
3. make the simple sources (soundgasm and whyp) work on both platforms;
4. add audiochan, which needs a WebView + cookie flow;
5. add hotaudio, using the Rust crate's iOS bindings;
6. harden parity and release.

## Non-goals for the first pass

- No visual redesign, no new screens, no "while we're here" UI cleanups.
- No attempt to run Media3/ExoPlayer on iOS. Share the playback contract, not the engine.
- No App Store/TestFlight signing until Phase 6. Unsigned simulator and device builds are enough until then.
- No local iOS toolchain requirement. All iOS compilation happens in CI.

## Ground rules

- Keep the Android build and existing Android CI green at every merge.
- Clear lint as you go: every phase runs `./gradlew :app:lintDebug :shared:lintDebug :composeApp:lintDebug` and fixes new errors and warnings before merging. Do not let lint debt accumulate across phases.
- One phase per pull request where practical; each phase must leave both platforms buildable.
- `commonMain` must not import `android.*`, `androidx.*`, `java.*`, `javax.*`, `okhttp3.*`, or `org.json.*`.
- Platform-specific code goes behind `expect`/`actual` in `androidMain` or `iosMain`.
- Android visuals and behavior must not change unless a phase explicitly says so.
- Pin Kotlin, Compose Multiplatform, AGP, Ktor, and kotlinx versions in `gradle/libs.versions.toml`; verify the Kotlin/Compose Multiplatform compatibility matrix before merging Phase 1.
- Compose Multiplatform 1.12.x requires AGP 9.1+ and `compileSdk = 37` for its Android artifacts. Phase 1 stays on Compose Multiplatform 1.11.1 with AGP 8.13.2 and `compileSdk = 36` so the existing Android app stays green. Moving to CMP 1.12+ is a separate AGP 9 / Gradle 9 / SDK 37 upgrade.
- Keep `hotaudioExtractorRef` as the single source of truth for Rust bindings and native libraries.

## Target module layout

```text
app/                  Android app shell: MainActivity, PlaybackService, manifest, resources
shared/               KMP: models, search, networking, settings, resolvers, playback contract
  commonMain/
  androidMain/
  iosMain/
  commonTest/
composeApp/           KMP: Compose Multiplatform UI, Android library, iOS framework
  commonMain/
  androidMain/
  iosMain/
iosApp/               Xcode project and SwiftUI host
  iosApp.xcodeproj/
  iosApp/
    iOSApp.swift
    Info.plist
    Assets.xcassets/
```

Dependency direction:

```text
app  -> composeApp -> shared
iosApp -> ComposeApp.framework (produced by composeApp)
shared -> no Compose dependency
```

If two KMP modules become too much overhead, `shared` and `composeApp` can be merged later. The important boundary is "no platform imports in common code", not the module count.

## iOS compilation workflow

`.github/workflows/ios.yml` is added in Phase 0 and runs on `macos-latest`:

- JDK 21 + Gradle;
- checks whether `:composeApp` and its iOS framework task exist;
- skips cleanly with a notice until Phase 1 lands;
- builds `:composeApp:linkDebugFrameworkIosSimulatorArm64`;
- runs `xcodebuild` for the simulator and for a generic iOS device with signing disabled;
- uploads the simulator `.app` artifact.

The workflow is tuned for repeated use and runner conservation:

- `gradle/actions/setup-gradle` persists the Gradle dependency and build caches;
- `~/.konan` (Kotlin/Native toolchain and platform libs) is cached by Kotlin version;
- Xcode `DerivedData` is cached per branch with a fallback chain;
- `cancel-in-progress: false` ensures long-running linking jobs complete and save their caches to disk;
- Trigger policy: runs on PRs, pushes to `main`, manual dispatch (`gh workflow run ios.yml`), or commits containing `[ios]` / `[run ios]` in their message. Does not trigger on `app/**` changes;
- Simulator-first compilation: on feature branches, only `:composeApp:linkDebugFrameworkIosSimulatorArm64` is linked by default, cutting compile time in half (~15m). Physical device (`iosArm64`) is built on `main` or when `build_device: true` is dispatched.

On `macos-latest`, a cold run takes roughly 9-11 minutes; a warm run with the Kotlin/Native and DerivedData caches restored takes about 2 minutes.

Limitations:

- Unsigned builds cannot be installed on a physical device or uploaded to TestFlight. That needs an Apple Developer account and signing secrets in Phase 6.
- macOS runners are slower and more expensive than Ubuntu runners. The workflow is limited to relevant paths plus manual dispatch.
- Phase 5 extends this workflow with Rust iOS targets, an XCFramework build, and hotaudio simulator smoke tests.

## Screenshot and golden parity testing workflow

Pixel-perfect parity between Android and iOS is guarded by automated screenshot workflows in GitHub Actions:

### Android golden testing (Roborazzi + Robolectric)

- **Test suite:** `app/src/test/java/dev/dawngasm/app/screenshot/AppScreenshotTest.kt` renders `sampleAppUiState()` in light theme under pinned Robolectric qualifiers (`w411dp-h891dp-xxhdpi`, SDK 36, NATIVE graphics mode).
- **Screens covered:** Home, Library, Settings, Search Sheet, Mini Player.
- **Recording baselines:**
  - On demand or via CI, `./gradlew :app:recordRoborazziDebug` outputs baseline PNGs into `app/src/test/screenshots/`.
  - The dedicated `.github/workflows/record-screenshots.yml` workflow (and `ci.yml` `record_screenshots` dispatch input) executes this task in CI and uploads `roborazzi-reference-screenshots` as workflow artifacts so they can be committed to the repository.
- **Verification:**
  - The `verify-screenshots` job in `.github/workflows/ci.yml` runs `./gradlew :app:verifyRoborazziDebug` on every push and pull request.
  - If screens diverge from the committed baselines, the build fails and visual diff reports from `app/build/outputs/roborazzi/` and `app/build/reports/roborazzi/` are uploaded as `roborazzi-diff-report`.

### iOS simulator screenshot capture (`xcrun simctl`)

- **Workflow:** `.github/workflows/ios.yml` boots an available iPhone simulator headlessly after building the simulator `.app`.
- **Execution:**
  - Installs `iosApp.app` and launches `dev.dawngasm.app`.
  - Waits 5 seconds for the Compose Multiplatform UI to render `sampleAppUiState()`.
  - Captures the active screen using `xcrun simctl io "$SIMULATOR_UDID" screenshot "$RUNNER_TEMP/screenshots-ios/ios_home.png"`.
  - Terminates the app and cleanly shuts down the simulator.
- **Artifacts:** Uploads screenshots as `ios-screenshots` for visual inspection and cross-platform parity checks against the Android golden baselines.

## Phase 0 - Plan and guardrails

- [x] Add this plan.
- [x] Add `.github/workflows/ios.yml` with the preflight guard.
- [x] Confirm Android CI is unchanged and still green.
- [x] Decide module names, package names, and the `expect`/`actual` package layout.
- [ ] Freeze current Android screenshots as the parity baseline: Home, Library, Settings, Search sheet, Mini player, hotaudio clearance, audiochan clearance.

Acceptance:

- This plan is reviewed and merged.
- The iOS workflow exists and skips with a clear notice while no iOS target exists.
- No Android source or behavior changes.

## Phase 1 - Add KMP and an iOS compilation target

Goal: the iOS app builds and launches a placeholder; Android still builds unchanged.

Tasks:

- [x] Add the Kotlin Multiplatform and Compose Multiplatform plugins to the version catalog and root build.
- [x] Create `:shared` with `androidTarget()`, `iosArm64()`, and `iosSimulatorArm64()`; add `commonMain`, `androidMain`, `iosMain`, and `commonTest`.
- [x] Create `:composeApp` with Compose Multiplatform, an Android library target, and the same iOS targets. Configure a static framework named `ComposeApp`.
- [x] Create `iosApp/iosApp.xcodeproj` and a SwiftUI host that imports `ComposeApp` and shows the placeholder screen.
- [x] Add `MainViewController()` in `composeApp/iosMain` and a placeholder `DawngasmApp()` in `composeApp/commonMain`.
- [x] Keep `:app` working; point it at the placeholder only if needed for wiring.
- [ ] Add `expect`/`actual` skeletons for the platform services the later phases need: `PlatformLog`, `Settings`, `HttpClientFactory`, `AudioPlayer`, `FileSystem`, `AppInfo`.
- [x] Run `./gradlew :app:lintDebug :shared:lintDebug :composeApp:lintDebug` and fix every new issue in the KMP modules.
- [x] Run the iOS workflow and fix framework search paths, Xcode scheme, and signing-disabled build issues.
- [ ] Add a minimal launch smoke test (XCUITest) if it can be made reliable in CI.

Acceptance:

- `./gradlew :app:assembleDebug` passes.
- `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` passes.
- `xcodebuild ... -sdk iphonesimulator` and `xcodebuild ... -sdk iphoneos` pass with `CODE_SIGNING_ALLOWED=NO`.
- The iOS app shows the placeholder screen.
- Android UI is unchanged.
- `:app`, `:shared`, and `:composeApp` all pass Android lint with no new issues.

Risks:

- Kotlin/Native toolchain download size and CI time.
- Compose Multiplatform version alignment with Kotlin 2.4.x.
- Xcode framework embedding/search paths.
- `:app` currently owns the Android manifest, service, and resources; keep it as the Android shell instead of trying to make it a KMP module.

## Phase 2 - Port the UI to Compose Multiplatform with pixel parity

Goal: Home, Library, Settings, Search, and the mini player render identically on Android and iOS.

Tasks:

- [ ] Upgrade the toolchain to AGP 9.1+, Gradle 9.x, `compileSdk = 37`, the AGP KMP library plugin, and Compose Multiplatform 1.12+. Deferred: the UI is ported on Compose Multiplatform 1.11.1 first so the existing AGP 8.13 / SDK 36 Android app stays green. The upgrade is a separate change once the shared UI is stable.
- [x] Move the theme to `composeApp/commonMain`: colors, typography, dimens, shapes.
- [x] Replace Android-only theme hooks with `expect`/`actual`:
  - status bar / navigation bar appearance (`WindowCompat`, `LocalView`);
  - dynamic color (currently disabled, so the Android actual can keep the current behavior);
  - `LocalContext` usages.
- [x] Move reusable components: `AppNavBar`, `TrackCard`, `SearchSheet`, `SearchRow`, `SearchFab`, `PillTabs`, `MiniPlayer`, `MorphingProgressIconButton`, `EmptyState`, `CardGroupShape`.
- [x] Move screens: `HomeScreen`, `LibraryScreen`, `SettingsScreen`.
- [x] Move `DawngasmApp()` navigation, snackbar, tab transitions, and back handling.
- [x] Replace Android-only UI APIs with `expect`/`actual` or Compose Multiplatform equivalents:
  - `BackHandler` -> `PlatformBackHandler`;
  - `AndroidView` -> `ClearanceWebView` (stub on iOS until Phase 4);
  - `rememberLauncherForActivityResult` -> platform folder picker and clearance launcher contracts;
  - `LocalContext` -> `PlatformContext` / `AppInfo`;
  - haptics, keyboard controller, IME actions -> CMP equivalents or expect/actual;
  - `BuildConfig.VERSION_NAME` -> `appVersion`.
- [x] Keep `MainActivity` thin: `setContent { AndroidDawngasmApp() }`.
- [x] Add Android screenshot/golden tests for the main screens (`AppScreenshotTest` with Roborazzi + Robolectric).
- [x] Add CI jobs for reference screenshot capture (`recordRoborazziDebug`) and verification (`verifyRoborazziDebug`).
- [x] Capture iOS simulator screenshots in CI and upload them as artifacts (`xcrun simctl`).
- [x] Compare against the Phase 0 baseline at the same device size and theme.

Acceptance:

- Android screenshots match the Phase 0 baseline; no layout, color, typography, spacing, or animation changes.
- iOS renders Home, Library, Settings, Search sheet, and mini player with sample data.
- Navigation, back behavior, snackbars, search sheet, and tab transitions behave the same.
- `commonMain` contains no Android-only APIs (`android.*`, `androidx.activity.*`, `androidx.lifecycle.*`, `LocalContext`, `LocalView`, `BuildConfig`); only Compose Multiplatform APIs and `expect`/`actual` contracts.

## Phase 3 - Shared data, soundgasm, whyp, and iOS playback

Goal: search and play/download soundgasm and whyp on both platforms.

Tasks:

- [x] Move models to `shared/commonMain`: `Track`, `AudioHost`, `SearchRequest`, `SearchSyntax`, `AudioLinks`, `TagSuggestion`.
- [x] Replace `org.json` with `kotlinx.serialization`; keep JSON field names so saved/history/downloads remain readable.
- [ ] Replace OkHttp with Ktor:
  - `androidMain`: OkHttp engine, preserving current behavior;
  - `iosMain`: Darwin engine;
  - shared cookie-jar abstraction for the audiochan flow.
- [x] Replace `java.util.Locale` with Kotlin `lowercase()`/`uppercase()`.
- [ ] Replace JVM-only helpers: `TimeUnit`, `Date`, `ConcurrentHashMap`, `AtomicLong`, `Thread.sleep`, `System.currentTimeMillis`, `System.getProperty`.
- [ ] Replace `java.io.File` and `RandomAccessFile` with okio `FileSystem` / `Path`.
- [x] Settings: use `multiplatform-settings` or an `expect`/`actual` store; migrate the existing `dawngasm` SharedPreferences keys to iOS defaults.
- [x] Logging: `PlatformLog` actuals (Android `Log`, iOS `os_log`).
- [ ] Define `AudioPlayer` and move the playback contract to `shared`:
  - Android actual keeps Media3/ExoPlayer and the existing `PlaybackService`/MediaSession behavior;
  - iOS actual uses AVPlayer + AVAudioSession.
- [ ] Move `DawngasmApi`, `Network`, the soundgasm/whyp parts of `AudioResolver`, and `PlaybackQueue` to shared.
- [ ] Downloads: `FileSaver` expect/actual. Android keeps SAF/`DocumentFile`; iOS writes to app Documents and uses the document picker.
- [x] Move model/parser tests to `commonTest` with `kotlin.test`; keep Android instrumentation tests where they still add value.
- [ ] Run shared tests and the iOS build in CI.

Acceptance:

- Android search, soundgasm/whyp playback, and downloads are unchanged.
- iOS simulator can search, resolve, and play soundgasm and whyp.
- iOS can save a download to app storage.
- Android audiochan and hotaudio are not regressed.

## Phase 4 - audiochan (WebView + cookie flow)

Goal: audiochan playback and downloads work on both platforms.

Tasks:

- [ ] Define `expect @Composable ClearanceWebView(url, requiredCookie, onCaptured, onClose)`.
- [ ] Android actual: reuse the existing `AudiochanClearanceActivity` WebView, Turnstile injection, and cookie capture.
- [ ] iOS actual: `WKWebView` + `WKHTTPCookieStore` + `WKScriptMessageHandler`; inject the same Turnstile widget and capture `__Secure-audiochan_playback` plus the User-Agent.
- [ ] Persist clearance cookies in the platform settings store and inject them into the Ktor client (Darwin/OkHttp).
- [ ] Move the audiochan resolver to shared: slug parsing, GET/POST fallback, play-intent, stream-url, JSON field extraction.
- [ ] Make the Settings "audiochan check" entry work on both platforms and keep the connected state in sync.
- [ ] Add tests for slug parsing, JSON extraction, and cookie merging.

Acceptance:

- audiochan playback and download work on Android and the iOS simulator.
- The clearance cookie survives an app restart.
- soundgasm and whyp still work.
- Android audiochan behavior is unchanged.

## Phase 5 - hotaudio (Rust iOS bindings)

Goal: hotaudio search, streaming, and downloads work on both platforms.

Tasks:

- [ ] Add Rust iOS targets: `aarch64-apple-ios`, `aarch64-apple-ios-sim`, and `x86_64-apple-ios`.
- [ ] Build static libraries and create `HotaudioFFI.xcframework` in CI, pinned to `hotaudioExtractorRef`; cache the result.
- [ ] Generate UniFFI Swift bindings (or Kotlin/Native cinterop bindings) from the same crate and ref. Commit them or generate them in CI deterministically.
- [ ] Replace `initAndroidTls(Context)` with `expect fun initPlatformTls()`:
  - Android: existing JNI + rustls-platform-verifier path;
  - iOS: the crate's iOS platform-verifier path / Security framework.
- [ ] Move `HotaudioStreamer`, `HotaudioRuntime`, and `HotaudioErrors` to shared; replace `java.io.File` with okio/NSFileManager.
- [ ] Implement the iOS streaming bridge for the growing `.m4a.part` file: `AVAssetResourceLoaderDelegate` or a local HTTP server. Match the Android behavior: start after the first fragment, wait for appends, keep the stall watchdog and retry logic.
- [ ] Add hotaudio clearance on iOS: `WKWebView` captures `cf_clearance`, User-Agent, and IP family; persist and push them into the native extractor.
- [ ] Extend `.github/workflows/ios.yml` with Rust targets, XCFramework build, caching, and a hotaudio simulator smoke test.
- [ ] Keep the Android native build path unchanged: `cargo ndk` -> `src/main/jniLibs`.

Acceptance:

- The iOS app links `HotaudioFFI.xcframework` and builds for simulator and device (unsigned).
- hotaudio search, streaming, and download work on the iOS simulator.
- Android hotaudio behavior is unchanged.
- CI builds the iOS XCFramework from the pinned ref.

## Phase 6 - Parity, hardening, release

- [ ] Background audio, lock-screen/Now Playing metadata, remote commands, and interruption handling on iOS.
- [ ] Downloads, history, saved tracks, and library parity.
- [ ] Error reporting and diagnostic logs on both platforms.
- [ ] Accessibility and Dynamic Type.
- [ ] Performance and memory pass, especially hotaudio streaming.
- [ ] Release signing, provisioning, and TestFlight (requires an Apple Developer account and CI secrets; can be deferred).
- [ ] Final CI matrix: Android unit tests + APK, iOS simulator build, iOS device compile; optional nightly iOS build.

## Risks and decisions

- **Playback engine:** Media3 cannot run on iOS. Share `AudioPlayer` and queue logic; keep ExoPlayer and AVPlayer as platform actuals.
- **HTTP client:** Ktor is the pragmatic shared client. Keep the OkHttp engine on Android to minimize behavior changes.
- **JSON:** `org.json` is Android/JVM-only. Migrating to `kotlinx.serialization` is required; preserve existing field names so stored data survives.
- **Compose parity:** screenshot tests are the guardrail. Do not "improve" spacing or typography during the port.
- **Version alignment:** Kotlin 2.4.x + Compose Multiplatform + AGP must be checked together before Phase 1 merges. CMP 1.11.1 is the newest release whose Android artifacts accept AGP 8.13.2 and SDK 36; CMP 1.12+ needs AGP 9.1 and SDK 37, which is deferred until after the Phase 1 scaffold is stable.
- **iOS CI cost:** macOS minutes are limited. Cache Gradle and Kotlin/Native, and avoid running iOS CI on unrelated changes.
- **hotaudio:** the Rust crate already has iOS bindings. The hard parts are TLS initialization and feeding a growing file to AVPlayer, not building the crate.
- **No local Mac:** all iOS compilation is CI-only. Local development remains Android-first; iOS screenshots and build logs come from CI artifacts.

## Definition of done (v1)

- Android and iOS both compile in CI.
- Shared UI and shared data live in KMP modules.
- soundgasm, whyp, audiochan, and hotaudio work on both platforms.
- Android visuals and behavior are unchanged from the pre-migration baseline.
- Every relevant pull request produces an iOS simulator build artifact.
