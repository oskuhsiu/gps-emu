# Route Mock

An Android 15+ mock-location prototype with fixed positions, walking routes, adjustable speed, pause/resume and notification Stop. The application interface is currently in Traditional Chinese.

**Pokemon GO and Pikmin Bloom compatibility has not been verified.** This application uses public mock-location APIs; receiving applications can identify and reject their output. The [verification record](docs/verification.md) separates emulator evidence from physical-device and game compatibility.

## Project documents

- [GitHub specification: issue #1](https://github.com/oskuhsiu/gps-emu/issues/1), published with `ready-for-agent`.
- [OSS scouting](docs/research/oss-scout.md): Luna evaluated four candidates; the implementation was built independently from official APIs rather than copied from a candidate application.
- [Android feasibility research](docs/research/android-feasibility.md).
- [Design](docs/design.md), [glossary](CONTEXT.md), and [ADRs](docs/adr/).
- [Security and dependency provenance review](docs/security/2026-09-12/README.md).
- [Third-party notices](THIRD_PARTY_NOTICES.md).

The initial specification is already implemented as version 0.1.0. The issue preserves the specification and remaining verification work; it does not mean implementation should start over.

Git tracks implementation/design documents, research conclusions, security evidence and compact verification summaries so they stay with the code they describe. The duplicate local issue specification, interview drafts, raw emulator logs/screenshots, local agent handoff files, build outputs and signing credentials are excluded by [.gitignore](.gitignore). GitHub Issues remains the source of requirements and task status; it does not replace these implementation and verification records.

## Build

Install JDK 17 or 21, Android SDK Platform 36 and Build Tools 35.0.0. Open the directory in Android Studio, or set `ANDROID_HOME` / the `sdk.dir` entry in local `local.properties`.

```bash
./gradlew :core:test :app:assembleDebug :probe:assembleDebug :app:lintDebug :probe:lintDebug
```

Outputs:

- Product: `app/build/outputs/apk/debug/app-debug.apk`.
- Independent receiver: `probe/build/outputs/apk/debug/probe-debug.apk`.

These are debug builds. The receiver has a separate package and UID and does not inject locations or read the product's draft. Retained artifact sizes and hashes are in [artifacts.json](docs/evidence/2026-09-12/artifacts.json).

In a restricted workspace, place Gradle and Android user files under writable local directories:

```bash
GRADLE_USER_HOME="$PWD/.gradle-local" \
ANDROID_USER_HOME="$PWD/.runtime/android-user" \
./gradlew :core:test :app:assembleDebug :probe:assembleDebug :app:lintDebug :probe:lintDebug
```

The wrapper pins Gradle 8.13 and verifies the distribution SHA-256. SDK and dependency configuration is defined in the Gradle files. No SDK or system JDK modifications are required for normal builds.

## Tag-triggered releases

[The release workflow](.github/workflows/release.yml) runs on tag pushes. Use stable version tags such as `v0.1.0` or `0.1.0`; other formats fail validation. The tag supplies `versionName`, and `versionCode` is `major * 1000000 + minor * 1000 + patch + 1`. Major is limited to 2099, minor/patch to 999, with no leading zeros. Publish increasing versions so Android can update existing installations.

Configure these repository **Actions secrets** once in [GitHub settings](https://github.com/oskuhsiu/gps-emu/settings/secrets/actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of the release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Alias of the signing key in the keystore |
| `ANDROID_KEY_PASSWORD` | Password of that private-key entry |

Reuse and back up the same release key for future updates. The workflow does not create a new signing identity per run or fall back to a debug key. If no release key exists yet, generate one once outside the repository, for example with the JDK's interactive tool:

```bash
keytool -genkeypair -storetype JKS -keystore /secure/path/route-mock-release.jks \
  -alias route-mock -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Route Mock'
```

With an authenticated local GitHub CLI, the encoded keystore can be uploaded without printing it to the terminal:

```bash
openssl base64 -A -in /secure/path/route-mock-release.jks | \
  gh secret set ANDROID_KEYSTORE_BASE64 --repo oskuhsiu/gps-emu
gh secret set ANDROID_KEYSTORE_PASSWORD --repo oskuhsiu/gps-emu
gh secret set ANDROID_KEY_ALIAS --repo oskuhsiu/gps-emu
gh secret set ANDROID_KEY_PASSWORD --repo oskuhsiu/gps-emu
```

First commit and push the complete project, including the workflow. Then push a tag pointing to that source commit:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow runs core tests and release lint, builds the product release variant, aligns and signs the APK, and verifies its signature before transferring it to a separate publishing job. Only that publishing job has repository write permission. It verifies the tag still points to the built commit, uploads `route-mock-0.1.0-release.apk` and `SHA256SUMS.txt` to a draft, then publishes the GitHub Release with generated notes. A failed upload leaves a draft that a rerun can resume; an already published release is not overwritten. Missing signing secrets fail before the build.

The release contains the product APK, not the test receiver. These APKs use the configured release certificate; an existing debug-signed installation generally needs to be uninstalled first because its certificate differs. No Google Play deployment is performed.

For a local unsigned release build with the same version mapping, use:

```bash
./gradlew :core:test :app:lintRelease :app:assembleRelease \
  -PreleaseVersionName=0.1.0 -PreleaseVersionCode=1001
```

The unsigned output requires signing before installation. References: [GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets), [Android versioning](https://developer.android.com/studio/publish/versioning), [APK signing](https://developer.android.com/tools/apksigner).

## Use

The control names below are English translations of the current interface.

1. Install the product, enable system location and grant precise-location permission. Allow notifications to use the notification Stop action.
2. In Developer options, select **Route Mock** as the mock-location application. The in-app Settings button provides guidance and a shortcut.
3. Tap the map or enter coordinates to add a point. Hold uses the last selected waypoint. For a walking route, choose 2-8 waypoints, request walking directions, inspect the preview, then start.
4. Speed defaults to 5 km/h and can be adjusted from 0.5 to 30 km/h. A foreground service continues output when switching to another application.
5. Pause and arrival continue holding position with zero speed. Use Stop in the application or notification to release mock output.

The last route and speed are saved locally. Reopening does not resume an active trip. Downloaded geometry can play offline, although map tiles still require a connection. Editing waypoints invalidates the previous route and requires replanning.

Force-stopping the process can leave mock state behind. On the next launch, use the interrupted-session cleanup action. If another mock application has been selected, select Route Mock again before retrying cleanup. Receiving applications may cache old positions until a fresh fix is available, even after successful Stop.

## Services and data

- Map: OpenStreetMap tiles and locally bundled Leaflet 1.9.4 scripts/styles.
- Walking routes: FOSSGIS `routing.openstreetmap.de/routed-foot`. Coordinates are submitted only when planning is requested; the server logs requests. Requests use an identifiable User-Agent, maintain at least 1.5 seconds between starts, and are not automatically retried.
- Attribution and map-correction links remain visible. The [routing policy](https://routing.openstreetmap.de/about.html) and [tile policy](https://operations.osmfoundation.org/policies/tiles/) apply. Shared services provide no project-specific SLA; public-scale distribution needs a capacity review or another deployment/provider.
- The app's own code has no account system, analytics or real-location upload path. Route drafts are excluded from cloud backup and device transfer. Third-party SDK and system-service behavior has not been fully audited; see the [security review](docs/security/2026-09-12/README.md).
- Healthy GMS enables GPS, network and Google fused mock output. Platform-only mode is used only when GMS is absent; installed but unhealthy GMS produces an explicit error.

Root integration, mock concealment, game modification, GPX import, route loops and Google Maps share-link import are outside this version. Possible follow-ups include GPX, round trips/loops and waypoint dwell times.

## Verification

The retained checks passed 12 core tests, product/probe debug builds and Android lint, with warnings documented in the [verification record](docs/verification.md). Independent receivers observed all three mock-location channels on Android 15 and 16 emulators. The record also covers stop cleanup, selected lifecycle cases, route errors and offline playback, with a per-platform matrix and screenshots.

Physical devices, long-term Doze/OEM behavior and the named games remain unverified. The [retained summaries](docs/evidence/2026-09-12/) contain APK fingerprints and numeric receiver assertions. Raw logs and screenshots remain local and are excluded from Git; the verification record distinguishes them from published evidence.

`core` contains the pure Java engine; `app` provides the screen, routing, persistence and location service; `probe` is the independent receiver. Tools under `tools/` support device interaction and numeric log assertions. Starting the debug UiDriver restarts the probe process, so launch the receiver separately after UI operations and leave instrumentation idle while collecting samples.
