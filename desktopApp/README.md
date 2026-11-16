# Dawngasm Native Desktop Application

Standalone, lightweight Kotlin/Native desktop shell for Dawngasm. It eliminates
all JVM runtime overhead (JRE, VLCJ, Swing/AWT) by compiling directly to
optimized native binaries using Compose Native (1.13.0-beta01), SDL3, KMPlayer,
and KMediaNotification.

This build is standalone to decouple from the Android AGP Gradle plugin:

| Component | Version |
| --- | --- |
| Kotlin | 2.4.21 |
| JetBrains Compose | 1.13.0-beta01 |
| `compose-native` | 1.13.0-beta01 |
| `kmplayer` | 0.2.1 |
| `kmedianotification` | 0.1.1 |

## Build and Package

```sh
cd desktopApp
./gradlew :app:packageLinuxX64ReleaseAppImage # Linux x64 AppImage
./gradlew :app:packageWindowsX64ReleaseZip     # Windows x64 Zip
./gradlew :app:packageMacosArm64ReleaseDmg    # Apple Silicon DMG
./gradlew :app:packageMacosX64ReleaseDmg      # Intel macOS DMG
```

Distributions land under `app/build/distributions/`.

## Self-test

```sh
./app/build/bin/linuxX64/releaseExecutable/app.kexe --self-test
```

`--self-test` and `--version` both print the OS/architecture, test the real
`kmplayer` audio backend and report its capabilities, construct a desktop
`kmedianotification` session, then exit cleanly without opening a window.

## Layout

- `app/src/desktopNativeMain/kotlin/dev/dawngasm/desktop/Main.kt` — entry point,
  window lifecycle, and self-test harness.
- `PlayerProbe.kt` — `KMPlayer.create` backend capability probe.
- `NotificationProbe.kt` — `MediaPlatform.desktop` and `KMediaNotification.create` probe.
- `DesktopInfo.kt` — OS/architecture/version identity.
- `icons/` — Application icon sets (.png, .icns, .ico, and AppStream metadata).
- `native/clearance-helper/` — Standalone Rust system webview helper.

## Licensing and third-party code

- `app/src/commonMain/kotlin/androidx/compose/material/icons/` embeds the
  exact 33 vector icons and `Icons.kt` used by Dawngasm, extracted directly
  from the Android Open Source Project / JetBrains Compose Multiplatform
  sources (`androidx.compose.material:material-icons-*-sources.jar`).
  These files are licensed under the **Apache License, Version 2.0** and retain
  their original copyright headers. Apache 2.0 is compatible with Dawngasm's GPL-3.0.
- `kmplayer` and `kmedianotification` are authored by brahmkshatriya and licensed
  under **GPL-3.0**.
- `compose-native` is authored by brahmkshatriya and licensed under **Apache-2.0**.

## Next step

Phase 22 of `docs/desktop-plan.md` moves `:shared` and `:composeApp` to a
`desktopNative` target, keeps the JVM shell until parity, and cuts over only
after playback, hotaudio streaming, downloads, clearance, and settings pass on
every runner.
