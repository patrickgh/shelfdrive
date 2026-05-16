# AGENTS.MD

## Project overview

This is an Android Automotive OS media app for audiobooks, built with Kotlin and AndroidX Media3.

The app is intended for AAOS / Android Automotive head units, especially Polestar 2 / AAOS 13 style environments. It should integrate as a media app through Media3 session/browser APIs and the system media host. It is not a general Android Auto projection app.

Primary goals:
- Provide audiobook playback through AndroidX Media3.
- Expose media content through a MediaLibraryService / MediaSession.
- Remain compatible with Android Automotive OS media app expectations.
- Keep the app safe for vehicle use: no custom distracting UI while driving unless explicitly required and compliant.

## Important technologies

- Kotlin
- Gradle / Android Gradle Plugin
- AndroidX Media3
- Android Automotive OS
- MediaSession / MediaLibraryService
- ExoPlayer
- Android manifest media-service integration

## Repository map

Use this rough map before opening many files:

- `app/src/main/AndroidManifest.xml`
  - AAOS declarations, permissions, services, intent filters, exported flags.

- `app/src/main/java/...`
  - Main Kotlin source code.

- `app/src/main/res/`
  - Android resources.

- `app/build.gradle` or `app/build.gradle.kts`
  - App module config, SDK versions, dependencies.

- `build.gradle` or `build.gradle.kts`
  - Root Gradle config.

- `gradle/wrapper/`
  - Gradle wrapper files.

- `README.md`
  - Human-facing project overview. Read only if task needs user-facing docs.

Note: There is currently no Gradle version catalog. Dependency versions are declared directly in `app/build.gradle.kts`, and plugin versions are declared in the root `build.gradle.kts`.

Kotlin sources live under the conventional Android `app/src/main/java/...` source set. Do not assume files are Java because the directory is named `java`.

## Files and directories to avoid unless explicitly needed

Do not read, summarize, or modify these unless the user specifically asks:

- `build/`
- `app/build/`
- `.gradle/`
- `.idea/`
- `.kotlin/`
- `captures/`
- `*.apk`
- `*.aab`
- `*.aar`
- `*.class`
- `*.dex`
- `*.hprof`
- `*.log`
- `local.properties`
- `keystore.properties`
- `*.jks`
- `*.keystore`
- `google-services.json`
- generated files
- downloaded documentation dumps
- large copied API references
- screenshots or media files unless the task is about them

Prefer source files, Gradle files, manifests, and small project docs.

## General workflow

Before editing:
1. Identify the smallest set of files needed for the task.
2. Prefer searching symbols and filenames over reading entire directories.
3. Read Gradle and Manifest files only when compatibility, dependencies, SDK levels, or app registration are relevant.
4. Do not inspect build artifacts or generated files.

When editing:
1. Make minimal, focused changes.
2. Preserve existing architecture and naming.
3. Avoid broad refactors unless explicitly requested.
4. Prefer idiomatic Kotlin.
5. Do not introduce new dependencies unless clearly justified.

## Coding policy

Prefer loud failures over fake defaults. If required runtime data is missing, fail explicitly or surface a user-visible error instead of silently inventing state.

Prefer handling a case directly over adding a dodge flag or configuration switch.

When replacing or migrating a code path, carry the change through to the obvious finish line. Remove dead code, obsolete comments, compatibility wrappers, redirect-only helpers, and stale tests that only existed for the old path unless a concrete live consumer still needs them.

Do not keep two names or two active paths for one concept "for safety" without a specific compatibility requirement. Prefer one source of truth.

After editing:
1. Run the most specific available check.
2. If full build is expensive, run the narrowest Gradle task that verifies the change.
3. Report changed files and checks run.

## Build and test commands

Use these commands from the repository root.

List tasks:

```bash
./gradlew tasks
```

Run unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

Run Android lint:

```bash
./gradlew :app:lintDebug
```

Run instrumentation tests when an emulator or AAOS device is connected:

```bash
./gradlew :app:connectedDebugAndroidTest
```
