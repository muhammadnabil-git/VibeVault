# VibeVault — secure release build

The signing key and its passwords are **not** stored in the project any more.

## Keep the existing key
Installed copies of the app only accept updates signed with the SAME key. Keep using your current keystore
(`mediasaver.keystore`, alias `mediasaver`). Do **not** generate a new one unless you accept that users must uninstall first.
The keystore file was removed from this source tree and is git-ignored: keep your own copy somewhere safe (outside the repo).

## Local signed build
Put this in `~/.gradle/gradle.properties` (NOT in the project):

    VV_KEYSTORE_FILE=/absolute/path/to/mediasaver.keystore
    VV_KEYSTORE_PASSWORD=...
    VV_KEY_ALIAS=mediasaver
    VV_KEY_PASSWORD=...

then run `gradle assembleRelease`. Without these values the release APK is built UNSIGNED (and Gradle prints a warning).
Debug builds now use the normal Android debug key (they can no longer be installed over a release build).

## GitHub Actions
Repository → Settings → Secrets and variables → Actions → add:
`VV_KEYSTORE_BASE64` (`base64 -w0 mediasaver.keystore`), `VV_KEYSTORE_PASSWORD`, `VV_KEY_ALIAS`, `VV_KEY_PASSWORD`.
The workflow restores the key into a temporary file, builds, and deletes it. Nothing is published unless the key secret exists.

## IMPORTANT: the old key was exposed
The previous version had the keystore AND its password committed in the repository. If that repository was ever public, or
shared, treat the key as compromised:
1. Remove it from history (`git filter-repo --path app/mediasaver.keystore --invert-paths`) and change the keystore password
   (`keytool -storepasswd` / `-keypasswd` — this does not change the signature, so updates keep working).
2. If the app is on Google Play, enrol in Play App Signing and use an upload key; Play can then rotate the app-signing key.
3. For sideloaded installs a signing-key rotation requires APK Signature Scheme v3 key rotation (apksigner `rotate`).
