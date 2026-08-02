# AGENTS.md

## Project scope

ShelfDrive is a native Kotlin/AndroidX Media3 audiobook app for Audiobookshelf. It provides browsing, search, playback, metadata, queues, and controls through the vehicle's native media host.

The primary target is the Polestar 2. Keep reasonable compatibility with common AAOS vehicles, but do not add complexity for hypothetical or exceptionally rare cases. The project targets AAOS only, not Android Auto, phones, or tablets.

Audiobookshelf is the source of truth for the catalog and listening progress. The AAOS media host renders the primary UI; ShelfDrive provides the Media3 library/session, playback behavior, and settings.

Current non-goals are:

- Android Auto, phone, or tablet support.
- Podcast support.
- Offline downloads.
- A custom in-app player.
- Local listening history that intentionally diverges from Audiobookshelf.

Do not add these capabilities or remove existing features without prior discussion and explicit approval.

The app is in a closed Play Store test. Existing data matters, but compatibility does not automatically outweigh a simpler design. Breaking changes are acceptable after their benefits and consequences are approved. After a public release, discuss compatibility and migrations before breaking behavior.

## Required workflow

Analysis and non-mutating diagnostics may proceed without approval.

Before implementing a change:

1. Inspect the relevant code, tests, and worktree state.
2. Present a proportionate implementation plan.
3. Wait for explicit approval and resolve any open questions.
4. Implement only the approved scope.

Keep plans short for small changes and detailed enough to review complex trade-offs. Cover the goal, smallest reasonable solution, affected areas, dependencies, risks, verification, missing test resources, and completion criteria.

Stop and discuss material scope growth, unexpected complexity, or uncertain decisions. Report unrelated problems instead of fixing them incidentally; propose an updated plan if one blocks the work.

Explicit approval is required before:

- Adding or replacing a dependency.
- Starting a complex extension or cross-cutting refactor.
- Adding substantial abstraction, indirection, or infrastructure.
- Adding behavior mainly for compatibility or rare edge cases.
- Breaking persistence, APIs, media IDs, or established behavior.
- Proceeding with uncertain requirements or without essential test resources.
- Removing an existing feature.
- Committing, pushing, tagging, publishing, or releasing.

Refactoring is welcome when it has a concrete benefit. Explain that benefit before implementation.

## Engineering principles

Use this priority order:

1. Correctness.
2. Simplicity.
3. Readability.
4. Maintainability.
5. Performance.

Optimize performance only with evidence of a real problem. Prefer the smallest direct implementation that satisfies the agreed behavior.

Handle normal and realistically expected failures. If required runtime data is absent or contradictory, expose a clear error or user-visible state and record useful diagnostics. Never silently invent plausible defaults or state.

Do not introduce speculative workarounds, legacy layers, duplicate paths, dodge flags, or redirect-only wrappers. Complete approved migrations and remove obsolete code, comments, wrappers, and tests unless an active consumer needs them.

Follow the established Kotlin style and local architecture unless an approved refactor intentionally changes them. Code, identifiers, comments, and this file use English. Communicate with the project owner in German unless they use another language.

For a dependency proposal, explain the need, alternatives, maintenance status, and complexity impact.

Use technical sources in this order:

1. Current project code and tests.
2. Official Android and AndroidX Media3 documentation.
3. Official Audiobookshelf documentation.
4. Clearly identified supplementary third-party sources.

Gradle files and `gradle/libs.versions.toml` are the source of truth for SDK, plugin, and dependency versions. Do not duplicate changing versions here.

## Testing and verification

Agree on appropriate verification in the implementation plan and run the narrowest meaningful checks first. Common commands are:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Use the Gradle wrapper. Bug fixes should receive a regression test when practical, and new logic should receive focused tests when this does not require disproportionate infrastructure.

After plan approval, the agent may autonomously build, install, launch, reset, clear data, exercise scenarios, and inspect logs in the AAOS emulator.

The Polestar 2 has no ADB access; the project owner performs device tests. Provide concrete steps and request useful test data, diagnostic packages, server logs, screenshots, or observed media-host behavior.

Never claim verification that was not performed. Distinguish automated checks, static review, emulator tests, remaining Polestar tests, and checks that could not run. Suggest inputs that would improve confidence.

Room changes must address existing closed-test data, migration or an approved destructive change, focused tests, and the checked-in schema under `app/schemas/`.

Update documentation only when usage, setup, important architecture, or diagnostics actually change.

## Repository orientation

Search targeted symbols and filenames before reading large parts of the repository.

- `app/src/main/AndroidManifest.xml`: AAOS registration, permissions, components, and Media3 service declarations.
- `app/src/main/java/io/audiobookshelf/aaos/`: Kotlin application source.
- `app/src/main/res/`: Android resources and settings UI.
- `app/src/test/` and `app/src/androidTest/`: local and device tests.
- `app/schemas/`: checked-in Room schema history.
- `app/build.gradle.kts` and `gradle/libs.versions.toml`: app configuration and versions.
- `README.md` and `docs/`: user-facing and release documentation.
- `tools/diagnostics-server/`: diagnostics upload server; inspect or change it only for diagnostics-related tasks.

Avoid build outputs and generated artifacts when source files are sufficient.

## Worktree, sensitive files, and Git

Inspect the worktree before editing. Preserve existing modifications and avoid overlapping edits. Never discard, overwrite, reset, or clean unrelated work.

Do not access local configuration, credentials, tokens, signing material, or generated artifacts unless the approved task requires it. Update checked-in Room schemas only through the normal schema workflow.

Working branches and staging are allowed. Remove only branches created for the task and unstage only files staged by the agent. Never delete or reset source changes as cleanup.

Do not commit, push, tag, publish, upload, or release without separate explicit approval.

## Completion report

Report the changes, affected areas, checks and results, unverified items, risks, Polestar test steps, and remaining Git state. Do not call work complete while an agreed criterion remains unmet.
