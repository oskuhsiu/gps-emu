# Provenance evidence — 2026-09-12

Checked at `2026-09-12T13:50:42Z` from the project working tree. The checks fetched public bytes into memory and computed SHA-256/SRI values; no fetched script, JAR, or archive content was executed. The machine-readable record is [provenance.json](provenance.json).

The bundled Leaflet files match the official Leaflet 1.9.4 distribution:

| Local file | Bytes | SHA-256 | Result |
| --- | ---: | --- | --- |
| `app/src/main/assets/leaflet/leaflet.js` | 147,552 | `db49d009c841f5ca34a888c96511ae936fd9f5533e90d8b2c4d57596f4e5641a` | Matches official SRI `sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=` |
| `app/src/main/assets/leaflet/leaflet.css` | 14,806 | `a7837102824184820dfa198d1ebcd109ff6d0ff9a2672a074b9a1b4d147d04c6` | Matches official SRI `sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=` |
| `app/src/main/assets/leaflet/LICENSE` | 1,395 | `53e8dc25862014e4324741ca18fbe3611e11d42ef69f59f86ea8c5389647d4cb` | Exact match to npm package and upstream v1.9.4 source tag |

Sources checked: the [official Leaflet download page](https://leafletjs.com/download.html), [unpkg JS](https://unpkg.com/leaflet@1.9.4/dist/leaflet.js), [unpkg CSS](https://unpkg.com/leaflet@1.9.4/dist/leaflet.css), [npm 1.9.4 metadata](https://registry.npmjs.org/leaflet/1.9.4), [npm tarball](https://registry.npmjs.org/leaflet/-/leaflet-1.9.4.tgz), and the [upstream v1.9.4 source archive](https://github.com/Leaflet/Leaflet/archive/refs/tags/v1.9.4.tar.gz). The npm package's `dist/leaflet.js`, `dist/leaflet.css`, and `LICENSE` were exact byte matches. The upstream source archive contains no built `dist/leaflet.js`; that is consistent with the upstream distribution model and does not create a source-to-built comparison for that file.

The Gradle wrapper JAR is an exact match to the official [v8.13.0 upstream tag](https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar) and to the official [wrapper JAR checksum](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256):

`gradle/wrapper/gradle-wrapper.jar` — 43,705 bytes, SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.

The pinned Gradle distribution checksum in `gradle/wrapper/gradle-wrapper.properties` is `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`, matching the official [8.13 binary distribution checksum](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256). The 130.64 MB distribution itself was not downloaded.

The two wrapper scripts do not hash-match the official [v8.13.0 `gradlew`](https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradlew) or [`gradlew.bat`](https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradlew.bat). After normalizing line endings, each differs in one JVM-options line: upstream includes `-Dfile.encoding=UTF-8`; the local scripts omit it. The batch file also uses CRLF while the raw upstream file uses LF. This records the mismatch for primary review; it does not classify the difference as malicious or benign.

For Leaflet advisories, the [official repository advisories page](https://github.com/Leaflet/Leaflet/security/advisories) stated that there were no published maintainer advisories when checked. The [GitHub Advisory Database entry for CVE-2025-69993 / GHSA-h5cx-hfj5-x8v3](https://github.com/advisories/GHSA-h5cx-hfj5-x8v3), published April 14, 2026 and updated April 21, 2026, claims that versions up to and including 1.9.4 permit XSS through `bindPopup()` when attacker-controlled strings are rendered as raw HTML. The [official Leaflet maintainer statement](https://github.com/Leaflet/Leaflet/issues/10214), opened May 12, 2026, says this is documented and intentional HTML rendering, assigns sanitization to the calling application, and says the CVE was formally disputed with MITRE. Whether the finding applies here depends on the app's actual popup/tooltip inputs; the sources conflict, so this evidence does not resolve that product question.

For `com.google.android.gms:play-services-location:21.3.0`, Google's [release notes](https://developers.google.com/android/guides/releases?hl=en) list the artifact in the May 29, 2024 release that updated the minimum SDK and Google Play services dependencies. The read entry contains no explicit security advisory or security fix for this location artifact. Google's current [setup page](https://developers.google.com/android/guides/setup) now shows 21.4.0 as the location dependency example. The official [21.3.0 Maven POM](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-location/21.3.0/play-services-location-21.3.0.pom) is 2,045 bytes with SHA-256 `d39695bcf029afb9ace67bf04d03454a0622c956641353fba16064d1fdc7b791` and declares the six expected dependencies recorded in `provenance.json`.

These are bounded provenance and advisory observations. They do not establish that the app has no vulnerabilities, and the Gradle script mismatch plus the disputed Leaflet CVE remain for the primary agent's product-level review.
