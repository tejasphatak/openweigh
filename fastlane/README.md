# Fastlane / F-Droid metadata

This directory holds **Fastlane Supply** metadata for OpenWeigh, in the standard
layout used by [F-Droid](https://f-droid.org/) to populate the app's listing.

Layout:

```
fastlane/metadata/android/en-US/
  title.txt               # App name on the listing
  short_description.txt   # Tagline (<= 80 chars)
  full_description.txt    # Full listing body (<= 4000 chars, plain text)
  changelogs/<code>.txt   # Per-versionCode changelog (1.txt == versionCode 1)
  images/                 # icon.png, featureGraphic.png, phoneScreenshots/, ...
                          # (text only for now; image binaries added later)
```

This is **text-only** metadata. Icons and screenshots will be added under
`images/` later and are not committed yet (only a `.gitkeep` placeholder).

## F-Droid build flavor: `foss`

OpenWeigh has two product flavors:

* **`play`** — the full build with optional Google Drive backup and Sign in with
  Google. These depend on Google Play Services (GMS), which is a **non-free
  dependency** (`NonFreeDep`). **This is NOT the flavor F-Droid ships.**
* **`foss`** — the GMS-free build. No Google Play Services, no Drive backup, no
  Google sign-in. BLE scale support, offline-first local storage, and Health
  Connect sync all work. **F-Droid must build this flavor.**

Anti-features for the `foss` flavor: **none**.

## Sample fdroiddata build recipe

For whoever submits to [fdroiddata](https://gitlab.com/fdroid/fdroiddata), the
build metadata should target the `foss` flavor. Sketch of the relevant fields:

```yaml
Categories:
  - Sports & Health
License: GPL-3.0-or-later
SourceCode: https://github.com/tejasphatak/openweigh
IssueTracker: https://github.com/tejasphatak/openweigh/issues

AutoName: OpenWeigh

RepoType: git
Repo: https://github.com/tejasphatak/openweigh

Builds:
  - versionName: 0.1.0
    versionCode: 1
    commit: v0.1.0
    subdir: app
    gradle:
      - foss

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags
CurrentVersion: 0.1.0
CurrentVersionCode: 1
```

Note: `gradle: [foss]` selects the `foss` product flavor, and `subdir: app`
points at the application module. Adjust `Repo`/`SourceCode` URLs to the real
project location before submitting.
