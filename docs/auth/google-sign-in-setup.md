# Google sign-in setup for Aura

## Current code boundary

The Android app already initializes Firebase App Check from `MainActivity`. This change enables the Firebase Authentication and Credential Manager libraries and adds a read-only observer for Firebase auth state.

It does not yet show a sign-in screen or start Google sign-in. Those steps depend on the Firebase/Google OAuth client configuration below. Do not enable cloud personal-context sharing as part of sign-in.

## Firebase Console steps

1. Open the existing Firebase project that owns Android package `com.aistudio.auraai.qvxmk`.
2. In **Authentication → Sign-in method** (or **Authentication → Sign-in providers**), enable **Google**.
3. Set the support email if Firebase asks for one, then save.
4. In **Project settings → General → Your apps → Android app**, add the SHA-1 and SHA-256 fingerprints for the signing certificate used by the installed build. For local debug builds, obtain them with Gradle's `signingReport` task. Release signing fingerprints must also be added before release sign-in testing.
5. After Google sign-in is enabled, download a fresh `google-services.json`. Confirm its Android client still has package `com.aistudio.auraai.qvxmk` and that its `oauth_client` array includes a **Web client** entry (`client_type: 3`). The current uploaded configuration had an empty `oauth_client` array, so the web client ID is not available yet.
6. Keep the downloaded configuration private to the build workspace; do not commit it to this public repository. The Google web client ID is an OAuth identifier, not an API secret, but should still be configured through the normal Firebase-generated resource rather than hard-coded.
7. In **App Check**, verify the Android app's provider is configured and enforcement is enabled only for the Firebase products Aura actually uses after a successful test. Do not switch enforcement on blindly; first confirm valid app tokens are being received.

## Android implementation sequence after console setup

1. Verify the regenerated Google Services resources include `default_web_client_id`.
2. Add the Credential Manager Google ID-token request flow using that web client ID.
3. Exchange the Google ID token with `FirebaseAuth.signInWithCredential`.
4. Expose signed-in/signed-out/loading/error state in the app UI and add explicit sign-out.
5. Test first sign-in, returning sign-in, cancellation, invalid configuration, and sign-out on a debug build.
6. Only then make the authenticated Firebase ID token and App Check token available to the Aura backend client. The backend must independently verify both tokens.

## Privacy and account boundaries

- Google sign-in establishes an account identity; it is not consent to upload chats, memories, files, or project data.
- Aura must continue excluding personal context from cloud AI requests until the separate consent and sensitivity-filtering work is implemented.
- Never place an OpenAI API key in the Android app, `google-services.json`, or this repository.
- Do not log Google ID tokens, Firebase ID tokens, App Check tokens, or full private prompts.
