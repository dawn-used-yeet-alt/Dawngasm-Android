# Dawngasm native desktop spike

This is a deliberately separate Gradle build. It proves the no-JVM desktop
stack links and starts before any of `:shared` or `:composeApp` is ported to a
Kotlin/Native target.

The root build stays on Compose 1.11.1. This build uses:

| Component | Version |
| --- | --- |
| Kotlin | 2.4.21 |
| JetBrains Compose | 1.13.0-beta01 |
| `compose-native` | 1.13.0-beta01 |
| `kmplayer` | 0.2.1 |
| `kmedianotification` | 0.1.1 |

## Build and run

```sh
cd native-spike
./gradlew :app:linkDebugExecutableLinuxX64    # Linux x64
./gradlew :app:linkDebugExecutableMingwX64    # Windows x64
./gradlew :app:linkDebugExecutableMacosArm64  # Apple Silicon
./gradlew :app:linkDebugExecutableMacosX64    # Intel macOS
```

The executable lands under `app/build/bin/<target>/debugExecutable/`. Running it
without arguments opens a small Compose Native window.

## Self-test

```sh
./app/build/bin/linuxX64/debugExecutable/dawngasm-native-spike.kexe --self-test
```

`--self-test` and `--version` both print the OS/architecture, create a real
`kmplayer` backend and report its capabilities, construct a desktop
`kmedianotification` session, then exit without opening a window. CI runs this
so a missing SDL3/GStreamer/D-Bus dependency fails the job.

## Linux requirements

- SDL3 (`libsdl3-dev`, or build from source)
- Fontconfig, D-Bus, EGL/OpenGL development libraries
- GStreamer app/video plugins and base development packages
- PulseAudio development headers

CI builds SDL3 from a pinned commit because older Ubuntu images do not ship
`libsdl3-dev`. macOS uses `brew install sdl3`; Windows needs no extra SDK
because the Compose Native plugin stages SDL3 and the MinGW runtime.

## Layout

- `app/src/desktopNativeMain/kotlin/dev/dawngasm/spike/Main.kt` — entry point,
  window, self-test switch.
- `PlayerProbe.kt` — `KMPlayer.create` and capability report.
- `NotificationProbe.kt` — `MediaPlatform.desktop` and
  `KMediaNotification.create`.
- `SpikeInfo.kt` — OS/architecture identity.

## Next step

Phase 22 of `docs/desktop-plan.md` moves `:shared` and `:composeApp` to a
`desktopNative` target, keeps the JVM shell until parity, and cuts over only
after playback, hotaudio streaming, downloads, clearance, and settings pass on
every runner.
