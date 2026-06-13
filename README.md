# OpenWeigh

A free, open-source, **no-account** Android app for Bluetooth Low Energy (BLE) weight scales.
Connect to your scale, read weight and body composition, store everything locally
(offline-first), export to Android **Health Connect**, and optionally back up to **your own**
Google Drive — there is no OpenWeigh server, ever.

Licensed under the **GNU General Public License v3.0** (see [`LICENSE`](LICENSE)).

## Features

- **BLE scale support** via a pluggable protocol layer. Ships with the two standard Bluetooth
  SIG GATT services:
  - Weight Scale Service (`0x181D`) / Weight Measurement (`0x2A9D`)
  - Body Composition Service (`0x181B`) / Body Composition Measurement (`0x2A9C`)
  Body fat, lean mass, body water, bone mass, basal metabolism and impedance are read when the
  scale reports them. Adding a new scale family means implementing a single `ScaleProtocol`.
- **Offline-first local storage** with Room. Your data lives on your device.
- **Health Connect export** — weight, body fat, lean body mass, body water, bone mass and basal
  metabolic rate, with idempotent upserts and a "backfill all" option.
- **Optional Google Drive backup** (your account, your Drive):
  - Hidden auto-backup of an app snapshot to `appDataFolder` via WorkManager.
  - Visible, timestamped CSV/JSON export you can open and share.
- **Material 3 UI** — dynamic color on Android 12+, full light/dark, bottom navigation
  (Measure / History / Settings), live animated weight readout, trend charts, and a guided
  onboarding flow.

## Privacy / no-account

OpenWeigh has **no backend** and **no account**. The app is fully functional with no internet
connection and no Google sign-in. All measurements are stored locally. Google Drive backup and
Sign in with Google are entirely **optional** and use *your* Drive via incremental scopes
(`drive.appdata` for hidden snapshots, `drive.file` for visible exports). We never see your data.

## Build

Open the project in **Android Studio** (latest stable) and run the `app` configuration, or from
the command line:

```bash
# Debug APKs for both flavors (play + foss):
./gradlew assembleDebug
# A single flavor:
./gradlew assemblePlayDebug      # or assembleFossDebug
# R8-shrunk release APKs:
./gradlew assembleRelease
```

Requirements: JDK 17, Android SDK 35. Dependency versions are centralized in the Gradle version
catalog at [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

- `applicationId` / namespace: `io.github.openweigh` (the foss flavor uses `io.github.openweigh.foss`)
- minSdk 26, targetSdk/compileSdk 35, JVM target 17
- Kotlin + Jetpack Compose (Material 3), MVVM, Hilt, Coroutines/Flow, Room

### Product flavors

Two flavors on the `store` dimension:

- **`play`** — the full build: Google Drive backup + Sign in with Google (depends on Google Play
  Services).
- **`foss`** — a **GMS-free** build for F-Droid. Cloud backup/sign-in is replaced by a no-op (see
  [`app/src/foss`](app/src/foss)); BLE, local storage, CSV export and Health Connect are identical.
  Verified to contain **zero** Google Play Services / Drive classes.

### Release signing

`release` builds are R8-minified and resource-shrunk. Signing credentials are read from a
git-ignored `keystore.properties` at the repo root:

```properties
storeFile=/absolute/path/to/openweigh.jks
storePassword=…
keyAlias=…
keyPassword=…
```

If that file is absent (CI, fresh checkout), the release build falls back to the debug signing key
so `assembleRelease` still produces a runnable APK — just not one suitable for Play Store upload.

### Continuous integration

[`.github/workflows/build.yml`](.github/workflows/build.yml) runs `assembleDebug` (both flavors) +
unit tests + lint on every push/PR and uploads the debug APKs as artifacts.

## A note on F-Droid purity (GMS)

The **`play`** flavor depends on **Google Mobile Services** (Play Services Auth, Credential Manager
with the Google ID helper, and the Google Drive REST client) for the optional Drive backup — it is
not GMS-free. The **`foss`** flavor (`./gradlew assembleFossDebug`) strips all of that out: the
Google/Drive code lives only in [`app/src/play`](app/src/play), and the foss build binds no-op
implementations from [`app/src/foss`](app/src/foss), so it links without Google Play Services and is
a clean F-Droid candidate. BLE, local storage, CSV export and Health Connect are identical across
both flavors (Health Connect is an AndroidX library and never requires GMS).

## Contributing

Contributions are welcome under the GPLv3. To add support for a new scale, implement
`io.github.openweigh.ble.protocol.ScaleProtocol` and contribute it into the protocol set via a
Hilt `@IntoSet` binding in your own module.
