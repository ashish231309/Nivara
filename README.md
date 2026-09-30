# Nivara

Nivara is a native Android application for privacy and security. It is built to keep a
person's private space — locked apps, hidden apps, and encrypted files — on their own device,
under their own control.

The project is developed in the open and in small, reviewable increments. Everything that
ships is written for the platform: no cross-platform wrappers, no unnecessary third-party UI
frameworks, and no dependencies that are not justified by code that exists.

## Project status

This repository currently contains the **foundation release**: the application shell, the
navigation and architecture skeleton, and the build, test and security baseline that later
features are built on.

The application launches, shows the Nivara home screen and an about screen, and reports the
device's screen-lock status. Security features are not implemented yet — they arrive in the
releases that follow.

## Planned capabilities

These are the areas Nivara is being built for. They are listed here so the direction of the
project is clear; each one is implemented in its own release and is not present yet.

- Authentication and biometric unlock
- App locking and app hiding
- An optional home-screen (launcher) experience
- An encrypted file vault, including media, albums and a recycle bin
- Recovery and session management
- Security settings, themes and the final visual design

## Technology stack

| Area | Choice |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose with Material 3 |
| Platform | AndroidX, single `:app` module |
| Concurrency | Kotlin Coroutines and Flow |
| Build | Gradle with Kotlin DSL and a Gradle version catalog |
| Minimum Android version | Android 9 (API 28) |
| Compile / target SDK | API 36 |
| JVM target | Java 17 |

Dependency versions are declared in one place: [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Project structure

```
app/src/main/java/com/nivara/app/
├── NivaraApplication.kt        Application entry point; owns the dependency container
├── MainActivity.kt             The single activity; hosts the Compose UI
├── di/                         Hand-written composition root (AppContainer)
├── core/common/                Types shared across layers (NivaraResult)
├── domain/                     Contracts the app depends on (DeviceSecurityProvider)
├── data/                       Platform-backed implementations of those contracts
└── ui/                         Compose UI
    ├── NivaraApp.kt            Root composable: app bar + navigation host
    ├── theme/                  Material 3 colour scheme and typography
    ├── navigation/             Destinations and the navigation graph
    ├── components/             Reusable loading and error states
    ├── home/                   Home screen, state and view model
    └── about/                  About screen
```

The layering is deliberately small: `ui` depends on `domain` contracts, `domain` has no
Android dependencies, and `data` provides the platform implementations that `di` wires
together. Nothing is registered that is not used, so the structure can be trusted while it
grows.

## Requirements

- JDK 17
- Android SDK with platform 36 and build-tools 36 (Android Studio installs both)
- No local configuration is required beyond the Android SDK; `local.properties` is generated
  by Android Studio and is not committed.

## Building

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (R8 shrinking and resource shrinking enabled)
./gradlew :app:assembleRelease

# Install on a connected device or emulator
./gradlew :app:installDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

Release builds are not signed from this repository. Signing material is supplied at build
time — for example with a `keystore.properties` file or CI secrets — and keystores, passwords
and `local.properties` are excluded by `.gitignore`.

## Testing

```bash
# Unit tests (JVM, no device required)
./gradlew :app:testDebugUnitTest

# Instrumented tests (device or emulator required)
./gradlew :app:connectedDebugAndroidTest

# Lint
./gradlew :app:lintDebug
```

Unit tests cover the pieces that carry logic but no Android dependency: the result wrapper
used between layers, the home screen state machine, and the navigation route table.

## Security defaults

Security behaviour is built into the project's defaults rather than added at the end:

- **No permissions.** The manifest declares none. Every future permission has to be justified
  by a feature that exists.
- **No cleartext traffic.** `android:usesCleartextTraffic="false"` is set on the application.
- **No backup exposure.** `android:allowBackup="false"`, with
  `res/xml/data_extraction_rules.xml` excluding every storage domain from cloud backup and
  device-to-device transfer.
- **Nothing sensitive in logs or UI.** Failures are reported internally and surfaced to the UI
  as generic messages, and no secret is ever committed to the repository.
- **Shrinking is on for release builds**, so release-only problems surface early rather than
  at the end of the project.

Any change that weakens one of these defaults must explain why in the same change.

## Continuous integration

The workflow in [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml) builds
the debug and release variants, compiles the instrumented tests, runs the unit tests and runs
lint.

It is intentionally not wired to every push: Gradle builds are expensive and nothing is
learned by rebuilding the same code repeatedly. A run is triggered when a pull request or a
push to `main` changes build configuration — Gradle, the version catalog, the wrapper or the
module build files — and it can also be started manually from the Actions tab
(`workflow_dispatch`) whenever a change needs verification.

## Contributing

- Keep the dependency list short; add a library only when it removes more code than it adds.
- Prefer platform and AndroidX APIs over new dependencies.
- Keep `domain` free of Android types and keep screen composables stateless.
- Add a test when you add logic.
- Open a pull request; do not commit directly to `main`.

## License

Nivara is released under the [MIT License](LICENSE).

Copyright (c) Ashish Kumar
