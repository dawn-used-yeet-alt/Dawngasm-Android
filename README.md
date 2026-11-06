# Dawngasm for Android

A native Android client for the [Dawngasm](https://dawngasm.dawnusedyeet.workers.dev) audio index. Browse, search, stream, save, and download posts from **soundgasm**, **audiochan**, and **whyp** — with a Material 3 Expressive interface written in Kotlin and Jetpack Compose.

This repository is separate from the desktop/TUI Dawngasm project. It is an independent Android app, not a wrapper around the web UI.

## Features

- **Feed browsing** with paging, pull-to-refresh, and a grouped-card layout.
- **Discord-style filters** in the search box: `top:day`, `top:week`, `top:month`, `top:year`, `top:all`, and custom windows such as `top:7d` or `top:90d`; `sort:top`, `sort:new`, `sort:relevance`.
- **Playback** through ExoPlayer with the app's shared HTTP stack, including audiochan's cookie-gated play-intent flow.
- **Library** with listening history, saved tracks, and downloaded tracks.
- **Downloads** into a folder you choose with the Android document picker, or app storage under `Music/Dawngasm`.
- **Blocked tags** and a persisted feed sort/window in Settings.
- **Material 3 Expressive UI** with dynamic-feeling spring animations, grouped cards, a floating search pill, an animated mini player, and a bottom navigation bar.
- **No analytics, tracking, Firebase, or ad SDKs.** Only the index API and the three supported audio hosts are contacted.

## Supported hosts

| Host | Playback | Downloads |
| --- | --- | --- |
| soundgasm | page scrape | yes |
| audiochan | play-intent + stream URL | yes |
| whyp | page scrape | yes |

hotaudio is intentionally not supported in this first pass.

## Search syntax

Typing `top:` opens suggestions while you type. Recognised tokens are removed from the text query and applied as feed filters; unknown values stay as ordinary search text.

| Token | Meaning |
| --- | --- |
| `top:day` | past day (default) |
| `top:week` | past week |
| `top:month` | past month |
| `top:year` | past year |
| `top:all` | all time |
| `top:Nd` | last N days (1–3650) |
| `sort:top` | highest score |
| `sort:new` | newest |
| `sort:relevance` | relevance |

Examples:

```text
rain top:week
[F4M] quiet sort:new top:30d
```

## Architecture

```
app/src/main/java/dev/dawngasm/app/
├── data/          index API, link resolver, downloads, preferences
├── model/         Track, host detection, search token parser
├── state/         UI state models
├── ui/
│   ├── components/ expressive cards, search sheet, mini player, …
│   ├── screens/    Home, Library, Settings
│   └── theme/      pink Material 3 palette, type, dimensions
└── util/          formatting and file-name helpers
```

- **Kotlin + Compose**, package `dev.dawngasm.app`.
- **Media3/ExoPlayer** with an OkHttp data source and a shared cookie jar.
- **SharedPreferences** for saved/history/downloads snapshots, feed filters, blocked tags, and the download tree URI.
- **No Room/DataStore**, no background media service yet.

## Requirements

- Android Studio (or a command-line Android SDK) with **JDK 17+**
- Android SDK **36**
- Gradle is provided by the wrapper (`8.14.3`)

## Build

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

### Release signing

Release builds read signing material from environment variables:

```bash
export DAWNGASM_KEYSTORE_PATH=/absolute/path/to/release.jks
export DAWNGASM_KEYSTORE_PASSWORD=…
export DAWNGASM_KEY_ALIAS=dawngasm
export DAWNGASM_KEY_PASSWORD=…
./gradlew :app:assembleRelease
```

If the variables are absent, the release build is produced unsigned. Keystores and passwords are never committed; `.gitignore` excludes `*.jks`, `*.keystore`, and signing property files.

## Continuous integration

`.github/workflows/build.yml` runs on pull requests, pushes to `main`, tags, and manual dispatches:

- **Pull requests:** unit tests + debug APK artifact.
- **Pushes to `main`:** unit tests + signed release APK artifact (when signing secrets exist).
- **Tags (`v*`):** the same release build plus a GitHub release with the APK attached.

Gradle and configuration caches are handled by `gradle/actions/setup-gradle`. Concurrency groups cancel superseded runs.

Signing secrets used by CI:

| Secret | Value |
| --- | --- |
| `DAWNGASM_KEYSTORE_BASE64` | base64-encoded release keystore |
| `DAWNGASM_KEYSTORE_PASSWORD` | keystore password |
| `DAWNGASM_KEY_ALIAS` | key alias |
| `DAWNGASM_KEY_PASSWORD` | key password |

## Privacy

The app talks only to the Dawngasm index (`dawngasm.dawnusedyeet.workers.dev`) and the supported audio hosts. Saved libraries, history, and settings stay in the app's private storage. There is no telemetry.

## License

GPL-3.0. See [LICENSE](LICENSE).
