# VibeVault 7.7.0 — security audit notes

## Fixed in 7.7.0
- Signing passwords removed from `app/build.gradle.kts`; credentials come from environment / Gradle properties; CI uses secrets;
  `.gitignore` added (keystores, secrets); stray duplicate `build.yml` and `download` file removed.
- `DownloadEngine.saveToDownloads` and the Vault restore: no `!!`; the MediaStore entry is deleted on any failure, so no
  half-written or permanently "pending" file is left; the copy length is verified before the file is published.
- WebView bridge (`MSBridge`): a password-save request is accepted only when it is for the page the user is really on, over
  https, with size limits; auto-fill and form watching only run on https pages. Mixed content is never allowed; content/file
  access from file URLs disabled.
- `BootReceiver` is no longer exported (it only needs system broadcasts).
- `PlaybackService` (exported for the car / system): media items sent by OTHER apps are limited to http(s)/content URIs, so
  another app can no longer make VibeVault open `file://` paths in its private storage.
- Removed logging of recognition-service reply bodies.
- 8 unsafe `!!` removed (decoder buffers, file chooser, Bluetooth rule dialog, browser URL, detector, radio, platform links).
- Interrupt flag preserved after cancellable sleeps in the download engine.

## Reviewed, no change needed
- Firestore rules: every collection is limited to `users/{uid}` of the signed-in owner; everything else is closed.
- Saved browser passwords: AES-256-GCM with an Android Keystore key, saved only after the user confirms the dialog.
- TLS: `onReceivedSslError` cancels the load (certificates are validated).
- Vault is excluded from backups.

## OPEN — needs your decision
1. **Third-party service secrets are compiled into the app** (`DetectorKeys` in `RecognitionChain.kt`: ACRCloud access key +
   secret, RapidAPI key, Last.fm key). Anyone can extract them from the APK. Rotate them and, ideally, call those services
   through a small backend/Cloud Function of yours that holds the secrets. I did not move them to BuildConfig because that
   would not hide them and would break the detector if a build lacked the values.
2. **Firebase API key** (`google-services.json`): restrict it in Google Cloud Console to package `com.example.ytsaver` + your
   release SHA-1 and to the Firebase APIs only.
3. `usesCleartextTraffic="true"` stays: the in-app browser must open plain-http sites. Insecure sub-resources on https pages
   are blocked, and passwords are only handled on https.
4. `SYSTEM_ALERT_WINDOW` / `USE_FULL_SCREEN_INTENT` are kept for "open the app when Bluetooth connects"; Google Play may
   require a justification. About 100 `catch (_: Exception)` blocks remain; they were not individually audited.
