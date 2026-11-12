# VibeVault
Android media app (Kotlin, Jetpack Compose, Media3, Firebase). Current version: 7.7.2.
Docs: RELEASE.md (signing), SECURITY_AUDIT.md, CHANGELOG.md, TESTING.md, PROJECT_STATUS.md.

## Vault PIN recovery

Vault recovery uses Firebase Authentication email-link sign-in and does not require Cloud Functions, SMTP credentials, or a Blaze upgrade. In Firebase Console:

1. Enable **Email/Password** and **Email link (passwordless sign-in)** under Authentication > Sign-in method.
2. Keep the project's `firebaseapp.com` domain in Authentication > Settings > Authorized domains and make sure Firebase Hosting is initialized for the project.
3. Add the Android app's SHA-256 signing-certificate fingerprint in Project settings. Add the debug fingerprint for local builds and the release fingerprint for distributed builds.

The app receives verification links through `https://mediasaver-c3ee7.firebaseapp.com/__/auth/links`. Firebase Authentication on the Spark plan allows **5 email-link sign-in emails per day per project**. A verified link is required before initially setting a vault PIN; PIN reset also requires a link to the saved recovery email.
