<p align="center">
  <img src="docs/assets/shelfdrive-logo.svg" alt="ShelfDrive logo" width="140">
</p>

<h1 align="center">ShelfDrive</h1>

<p align="center">
  An Android Automotive OS media app for Audiobookshelf.
</p>

<p align="center">
  <a href="https://github.com/patrickgh/shelfdrive/releases/latest"><img alt="Latest GitHub Release" src="https://img.shields.io/github/v/release/patrickgh/shelfdrive?style=flat-square"/></a>
  <img alt="Status" src="https://img.shields.io/badge/status-beta-2563eb">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20Automotive%20OS-3DDC84?logo=android">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-29-2563eb?logo=android">
  <img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue">
</p>

<p align="center">
  <img src="docs/play-store/shelfdrive-feature-graphic.png" alt="ShelfDrive feature graphic showing Audiobookshelf playback in an Android Automotive media host" width="820">
</p>

ShelfDrive connects an Android Automotive OS vehicle to a self-hosted
[Audiobookshelf](https://www.audiobookshelf.org/) server. It exposes your
audiobook library through the native AAOS media experience instead of shipping
a custom player UI.

The app is built as a Media3 library/session source: browsing, search, now
playing, transport controls, artwork, queue handling, and voice/media actions
are provided through Android media APIs and rendered by the vehicle media host.

ShelfDrive is an independent project and is not affiliated with Audiobookshelf.

> [!WARNING]
> AI Disclaimer:
> This project was built with substantial AI assistance. It works for my environment/use cases but may fail on other cars. The code, documentation, architecture, and implementation details should be reviewed before production use.

> [!NOTE]
> The app is currently only available via my closed test track on the Google Play Store. If you accept the BETA stage you can reach out (dsaos9632@gmail.com) and get invited to try it yourself. A public release via Play Store is planned after completion of the test.

## Screenshots

| Library | Now Playing | Settings |
| --- | --- | --- |
| <img src="docs/screenshots/library.jpg" alt="ShelfDrive library browser on Android Automotive OS" width="260"> | <img src="docs/screenshots/now-playing.jpg" alt="ShelfDrive now playing screen on Android Automotive OS" width="260"> | <img src="docs/screenshots/settings.jpg" alt="ShelfDrive settings screen" width="260"> |

## Status

ShelfDrive is beta software for personal testing on Android Automotive OS.

Current app metadata:

- App name: `ShelfDrive`
- Application ID: `io.shelfdrive.app`
- Minimum SDK: `29`
- Target SDK: `35`
- Supported form factor: Android Automotive OS
- Supported Audiobookshelf media type: book libraries
- Languages: English and German

## Features

- Browse Audiobookshelf book libraries as one combined catalog.
- Open sections for recently listened books, all audiobooks, and authors.
- Display server-provided audiobook covers and author images.
- Search the local synced audiobook catalog from the AAOS media host.
- Play MP3 and M4B audiobooks through ExoPlayer.
- Use native AAOS now-playing, skip, seek, speed, and media controls.
- Configure the skip interval to 5, 10, 15, 30, or 60 seconds.
- Cycle playback speed from the now-playing controls through AAOS-supported values.
- Reconcile playback progress with Audiobookshelf asynchronously while playback is active.
- Keep Audiobookshelf as the source of truth for listening progress.
- Restore the last local playback state after app or vehicle restarts, including immediate playback from cached audio while the server is unavailable.
- Optionally rewind 15 seconds when pausing, so resume starts with a short recap.
- Configure server credentials and playback preferences, inspect connection and sync state, manage the cache, and send diagnostics from Settings.
- Cache catalog data, artwork, and up to 128 MiB of audio locally, with a time-based forward buffer for fast resumes and network handovers.

## Non-Goals

- Podcast support.
- Offline downloads.
- A phone/tablet UI.
- A custom in-app player screen.
- Built-in VPN, WireGuard, reverse proxy, or tunnel management.
- Local listening history that diverges from Audiobookshelf.

## Requirements

To use the app:

- An Android Automotive OS vehicle or emulator with a compatible media host.
- An Audiobookshelf server reachable from the vehicle for sign-in, catalog sync, and new or uncached playback.
- At least one Audiobookshelf library with media type `books`.
- Audiobook files playable by ExoPlayer, such as MP3 or M4B.

For local development:

- Android Studio.
- JDK 17 or newer.
- Android SDK with an Android Automotive OS emulator image.

For emulator testing, use a server URL that is reachable from inside the
emulator. Desktop hostnames often do not resolve in AAOS images; use a LAN IP or
`10.0.2.2` where appropriate.

## Setup

1. Install ShelfDrive on the AAOS device or emulator.
2. Open ShelfDrive from the vehicle launcher or media app list.
3. Open Settings from the media host settings affordance.
4. Enter the Audiobookshelf server URL, username, and password.
5. Log in and run a catalog sync.
6. Return to the media view and browse recently listened books, audiobooks, or authors.

ShelfDrive prioritizes a fast local resume. When a previous playback item exists,
the app restores its saved queue, position, and AAOS metadata immediately. If the
required audio is already cached, playback can start without waiting for the
network. Session refresh and progress reconciliation run asynchronously and do
not block this local start.

Signing in, synchronizing the catalog, and starting new or uncached content still
require a reachable Audiobookshelf server. During an outage, playback can continue
for the portions of the current audiobook that are already cached.

## Playback And Progress

For new playback, ShelfDrive resolves the audiobook through the Audiobookshelf
API and builds an ExoPlayer queue from the returned audio tracks. The resulting
playback manifest and position are stored locally for subsequent fast resumes.

Progress behavior:

- An asynchronous progress cycle starts with playback and repeats every 30 seconds while playback is active.
- Each cycle reads the server position before deciding whether to update the server.
- If the server is at least 30 seconds ahead, ShelfDrive seeks forward once to the observed server position. Otherwise, the current local position is sent to the server.
- Pause, automatic track changes, and completion start additional asynchronous progress cycles. A manual seek is treated as an explicit local choice and sent asynchronously without being undone by an older server position.
- Finished books are hidden from the local continue-listening cache.
- If the network is unavailable, the current position remains stored locally. Reconciliation resumes when connectivity returns, without delaying playback.

The optional 15-second pause rewind changes the actual player position before
the pause progress is synced. Pressing play again resumes from the rewound
position.

After a restart, restored playback is kept idle until the user presses play.
This preserves the AAOS browse root while still keeping the last title available
in the mini-player.

## Settings

The Settings screen is intentionally focused and vehicle-friendly:

- Connection state.
- Authentication state.
- Synced catalog counts.
- Manual catalog resync.
- Server URL, username, and password.
- Login/logout action.
- Configurable skip interval.
- 15-second rewind-on-pause toggle.
- Cache usage and clear-cache action.
- Startup diagnostics and an explicit diagnostic-package upload action.
- App version.

## Cache Policy

ShelfDrive stores a local catalog cache in Room, artwork in the app cache
directory, and ExoPlayer audio in a persistent app-private cache. The audio cache
is limited to 128 MiB with least-recently-used eviction. ExoPlayer starts as soon
as a short playable segment is available, then maintains a 20-to-30-minute
time-based forward buffer for the active audiobook in the background. For
multi-file audiobooks, ShelfDrive gives the active stream priority and then
caches complete following MP3 files until they cover at least 20 minutes. This
preload is stopped during buffering or network loss and restarted after playback
and connectivity are stable. ShelfDrive does not enable cache fragmentation for
these files.

When the active book changes, audio entries from other books are removed so the
full 128 MiB remains available for current playback. Measured track bitrates and
the resulting capacity estimate are recorded before the cache size is reconsidered.
Diagnostics retain errors for up to 48 hours and record the affected track,
remaining track duration, last transition, contiguous cache coverage, span count,
and forward-cache progress without storing access tokens.

The caches support local browsing, media host presentation, immediate resumes,
and playback through temporary network outages for audio that has already been
cached. They are not offline downloads: uncached audio and new playback still
require the Audiobookshelf server. The server remains the authority for catalog,
stream authorization, and progress data.

All caches can be cleared from Settings. Android may clear artwork from its
disposable cache directory under storage pressure. The audio cache manages its
own size through LRU eviction. App uninstall removes all local app data.

## Security And Privacy

- Credentials and tokens are stored with AndroidX Security encrypted preferences.
- Android backup is disabled for ShelfDrive app data.
- The app communicates only with the Audiobookshelf server URL configured by the user.
- Cleartext HTTP is allowed for local/private Audiobookshelf servers, but HTTPS is recommended for remote access.
- No analytics, ads, or tracking SDKs are included.

For more detail, see the [Privacy Policy](docs/PRIVACY_POLICY.md).

## Architecture

ShelfDrive is built around Android media primitives:

- `MediaLibraryService` exposes the browsable audiobook catalog.
- Media3 `MediaSession` publishes metadata, playback state, and transport actions.
- Media3 custom commands provide configurable seek controls and playback-speed cycling.
- ExoPlayer handles audiobook playback with a bounded local audio cache.
- Room stores the local catalog and progress cache.
- AndroidX Security stores credentials and tokens.
- A content provider serves authenticated artwork to the media host.

The vehicle media host renders the primary UI. ShelfDrive only provides a
settings activity for account, connection, playback, and cache preferences.

## Build

`local.properties` is intentionally not committed. Android Studio creates it for
your local SDK path. For command-line builds, ensure `ANDROID_HOME` points to
your Android SDK.

Build a debug APK:

```bash
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

Run lint:

```bash
./gradlew lintDebug
```

Run the full local verification used before release builds:

```bash
./gradlew lintDebug testDebugUnitTest assembleRelease bundleRelease
```

## Release Builds

Release signing uses a local, untracked `keystore.properties` file. Copy
`keystore.properties.example` to `keystore.properties`, fill in your upload-key
data, then build the Android App Bundle:

```bash
./gradlew bundleRelease
```

The Play Console artifact is:

```text
app/build/outputs/bundle/release/app-release.aab
```

If `keystore.properties` does not exist, Gradle may still create an unsigned
release bundle for compile verification. Do not upload an unsigned bundle to
Google Play.

For Android Automotive OS internal testing, see
[PLAY_STORE_INTERNAL_TEST.md](docs/PLAY_STORE_INTERNAL_TEST.md).

## Repository Layout

```text
app/src/main/java/io/audiobookshelf/aaos/absapi      Audiobookshelf API client
app/src/main/java/io/audiobookshelf/aaos/artwork     Artwork content provider
app/src/main/java/io/audiobookshelf/aaos/auth        Authentication and encrypted storage
app/src/main/java/io/audiobookshelf/aaos/browser     Browse node IDs and catalog repository
app/src/main/java/io/audiobookshelf/aaos/cache       Local cache handling
app/src/main/java/io/audiobookshelf/aaos/catalog     Room database entities and DAOs
app/src/main/java/io/audiobookshelf/aaos/diagnostics Startup diagnostics and diagnostic package handling
app/src/main/java/io/audiobookshelf/aaos/media3      Media3 library/session service and catalog
app/src/main/java/io/audiobookshelf/aaos/playback    Playback resolution, queue math, and state storage
app/src/main/java/io/audiobookshelf/aaos/progress    Progress synchronization
app/src/main/java/io/audiobookshelf/aaos/settings    Settings activity
app/src/main/java/io/audiobookshelf/aaos/status      User-visible status mapping
app/src/main/java/io/audiobookshelf/aaos/sync        Catalog synchronization
```

## Contributing

Issues and pull requests are welcome. Please keep the AAOS media-host model in
mind: ShelfDrive should behave like a native automotive media app and avoid
custom driver-facing playback UI where the platform already provides one.

## License

ShelfDrive is licensed under the [Apache License 2.0](LICENSE).
