# Route Mock

Route Mock 0.2.0 is an Android 9+ (API 28) mock-location app with fixed positions, walking routes with single-trip, back-and-forth and closed-loop modes, adjustable speed, pause/resume, and separate controls to stop walking or restore real location. The interface is in Traditional Chinese.

It uses public Android mock-location APIs. Receiving apps can identify and reject the output. Pikmin Bloom 152.0 map movement was observed on one OPPO Android 11 phone; full gameplay and Pokémon GO compatibility have not been verified. See the [FAQ](docs/faq.md) for supported devices, setup, behavior and verification limits.

## Quick start

1. Install the product APK from [Releases](https://github.com/oskuhsiu/gps-emu/releases), or build it below. Release APKs use a **public test signing key**; read the signing notice below.
2. Enable system location, grant precise-location permission, and select **Route Mock** as the mock-location app in Developer options. Allow notifications for convenient session controls.
3. Wait for a fresh real location, or press **定位** (Locate). Walking starts from this automatic origin; tap the map or use the coordinate icon to add up to 7 destinations. Choose 單次 (single trip), 原路往返 (back and forth), or 閉合循環 (closed loop), then plan, review and start. Planning sends the real origin and destinations to the routing service. Speed ranges from 0.5 to 30 km/h. Hold uses the last selected point.
4. Pause preserves progress for Resume. **Stop walking** holds the last position; **Restore real location** ends mock output. Single-trip arrival holds the endpoint. Pressing **定位** during mock output also releases it before requesting a fresh real position.
5. If updates stop after switching apps, use Settings → **背景執行設定** to open the app's system settings and allow background activity. See the [background settings FAQ](docs/faq.md).

Planning can reuse a validated real fix from the previous 30 seconds; starting a walk requires a new fix after pressing Start. If the origin has moved beyond the accuracy allowance, the app preserves destinations but clears the route for replanning. Route planning and uncached map tiles need network access; saved geometry can play offline if the fresh-origin check passes. Sessions never restart automatically. See the [FAQ](docs/faq.md) for location accuracy, timeout and verification limits.

## Documentation

- [FAQ／常見問題](docs/faq.md): usage, troubleshooting, data handling and verified limits.
- [Domain glossary](CONTEXT.md) and [architecture decisions](docs/adr/).
- [Third-party notices](THIRD_PARTY_NOTICES.md) and [signing identity](signing/README.md).
- [Project specification](https://github.com/oskuhsiu/gps-emu/issues/1).

## Build

Install JDK 17 or 21, Android SDK Platform 36 and Build Tools 35.0.0. Open the directory in Android Studio, or set `ANDROID_HOME` / the `sdk.dir` entry in local `local.properties`.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :probe:assembleDebug :app:lintDebug :probe:lintDebug
python3 -m unittest discover -s tools -p 'test_*.py'
```

Outputs:

- Product: `app/build/outputs/apk/debug/app-debug.apk`.
- Independent receiver: `probe/build/outputs/apk/debug/probe-debug.apk`.

These are debug builds. The receiver has a separate package and UID and does not inject locations or read the product's draft.

In a restricted workspace, place Gradle and Android user files under writable local directories:

```bash
GRADLE_USER_HOME="$PWD/.gradle-local" \
ANDROID_USER_HOME="$PWD/.runtime/android-user" \
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :probe:assembleDebug :app:lintDebug :probe:lintDebug
```

The wrapper pins Gradle 8.13 and verifies the distribution SHA-256. SDK and dependency configuration is defined in the Gradle files. No SDK or system JDK modifications are required for normal builds.

## Tag-triggered releases

[The release workflow](.github/workflows/release.yml) runs on tag pushes. Use stable version tags such as `v0.2.0` or `0.2.0`; other formats fail validation. The tag supplies `versionName`, and `versionCode` is `major * 1000000 + minor * 1000 + patch + 1`. Major is limited to 2099, minor/patch to 999, with no leading zeros. Publish increasing versions so Android can update existing installations.

No signing Secrets need to be configured. At the owner's request, the workflow uses the committed [public test keystore](signing/README.md) and verifies its SHA-256 before building. This fixed key is reused across versions; it is not regenerated per run.

**This is a public test signing identity.** Anyone can sign an APK with this key, so matching its certificate does not prove publisher authenticity. Release titles and notes disclose this limitation. Download APKs from this repository's Releases page. Use a separate private identity for future production distribution.

Pushing `main` alone does not run this workflow. First push the complete project, then push a new version tag pointing to that source commit:

```bash
git tag v0.2.0
git push origin v0.2.0
```

The workflow runs core and app unit tests and release lint, builds the product release variant, aligns and signs the APK, and verifies its signature before transferring it to a separate publishing job. Only that publishing job has repository write permission. It verifies the tag still points to the built commit, uploads `route-mock-0.2.0-release.apk` and `SHA256SUMS.txt` to a draft, then publishes the GitHub Release with generated notes and the public-key notice. A failed upload leaves a draft that a rerun can resume; an already published release is not overwritten. Missing or changed keystore bytes fail before the build.

The release contains the product APK, not the test receiver. These APKs use the persistent public test certificate; an existing debug-signed installation generally needs to be uninstalled first because its certificate differs. No Google Play deployment is performed.

For a local unsigned release build with the same version mapping, use:

```bash
./gradlew :core:test :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease \
  -PreleaseVersionName=0.2.0 -PreleaseVersionCode=2001
```

The unsigned output requires signing before installation. References: [Android versioning](https://developer.android.com/studio/publish/versioning), [APK signing](https://developer.android.com/tools/apksigner).

## Source and checks

`core` contains the pure Java playback engine; `app` provides the screen, routing, persistence and location service; `probe` is an independent location receiver with a separate package and UID. Reusable tools under `tools/` support device interaction and numerical receiver assertions.

The probe's debug UI driver restarts the probe process. Complete UI operations first, then launch the receiver separately and leave instrumentation idle while collecting samples. Build success, cross-app location reception and game compatibility are separate checks; the [FAQ](docs/faq.md) describes the demonstrated scope.
