# Public test signing identity

The repository owner explicitly requested a public keystore for downloadable test APKs. `public-test.jks` contains the private key and is intentionally committed. It is a persistent test identity, not a secret or a production signing credential.

| Setting | Value |
| --- | --- |
| Keystore | `signing/public-test.jks` |
| Store type | JKS |
| Alias | `route-mock-public-test` |
| Store password | `public-test-password` |
| Key password | `public-test-password` |
| Algorithm | RSA 3072 / SHA256withRSA |
| Certificate SHA-256 | `8102c4b00d6fe26b649b84881076f50100c4747aa48c08169328d74b2484ba7f` |
| Valid through | 2054-01-28 |

Anyone can use this private key to sign an APK with the same identity. Android signature verification therefore does **not** establish that an APK came from this project's owner. Obtain builds from the [repository Releases page](https://github.com/oskuhsiu/gps-emu/releases) and compare their checksums. Public-key signing does not establish that third-party APKs are trustworthy.

The [release workflow](../.github/workflows/release.yml) uses this exact keystore on every version tag, so test installations can update without changing certificates. The APK is built with the Android `release` variant and is not debuggable; the public key does not turn it into a Gradle `debug` build. An earlier debug-signed installation generally needs to be uninstalled once before installing this build because its certificate differs.

Do not regenerate or replace this key for each build. Future production distribution should use a separate private signing identity and an explicit migration plan; this public key cannot be made secret later.

From the repository root, verify the keystore bytes with:

```bash
sha256sum --check signing/public-test.jks.sha256
```

The checksum protects against accidental byte changes. It is not an authenticity guarantee if someone can change both the keystore and the checksum. `.gitignore` permits only this named public keystore; other signing credentials remain ignored.
