# Talkt accounts

Talkt signs users in with Clerk's Android SDK. Every cloud request carries a short-lived Clerk session token. The Worker verifies its signature, expiration, issuer, session ID and user ID. If the token includes a browser origin, it must be on the configured allowlist. Native tokens omit that claim.

The authenticated issuer and user ID select a Durable Object. Each object has its own OpenCode database, provider credentials and conversation sessions. This is one deployed Worker with a separate Durable Object for each account. Clients cannot select another account through a header, query parameter or request body.

All agent routes and cloud transcription require authentication. On-device transcription still works while signed out. Signed-in transcript archives and latest results are separated by account. Earlier transcripts remain in the signed-out device archive.

## Configuration

The Clerk application is named Talkt in Francesco's personal workspace. Its current instance is a development instance at `unique-aardvark-9804.clerk.accounts.dev`. Native API is enabled. Email and Google sign-in are enabled.

Android uses `CLERK_PUBLISHABLE_KEY` from the environment or a Gradle property, with Talkt's development publishable key as the default. No shared app token or Clerk admin secret is embedded in the APK.

The Worker uses these settings in `worker/wrangler.jsonc`:

- `CLERK_ISSUER`, the exact issuer URL.
- `CLERK_JWT_KEY`, the public PEM signing key. Update this when rotating Clerk's signing key.
- `CLERK_AUTHORIZED_PARTIES`, comma-separated allowed browser origins.
- `GEMINI_API_KEY`, an existing Worker secret used for cloud transcription.

Before a public release, create a Clerk production instance and replace the Android publishable key and Worker issuer and public key together. Configure Android package `org.buddytools.talkt`, its signing certificate, and Clerk's native SSO redirect URLs for production. The SDK declares its callback activities in the merged Android manifest.

The former shared `owner` Durable Object is left untouched. It is no longer reachable through the API. Users sign in and connect their own OpenRouter API key. Credentials and conversations are not automatically assigned from the old shared account. The obsolete `TALKT_APP_TOKEN` Worker secret has no authentication role.

## Verification

Run Worker checks with Bun:

```sh
bun install
bun run --cwd worker types
bun run check
bun run test
```

The tests verify signed native tokens, expired and forged tokens, wrong issuers and browser origins, missing configuration, all protected routes, and cross-account routing with actual Durable Object storage. They substitute an offline provider adapter for OpenCode in the test runtime. The deployed Android integration test exercises the real OpenCode SDK.

Build Android with the Android Studio JBR:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Restore the ignored speech models as described in AGENTS.md before assembling an installable APK.

The live integration test is opt-in and only runs against a Clerk development instance with no current user session. It creates test email accounts using Clerk's test OTP flow. No email is delivered and no paid model inference runs. It connects a synthetic provider key for Alice, verifies Bob has no provider connection, checks local archive separation, and verifies cloud access fails after sign-out. It attempts to remove its Clerk test users and clears its local transcript fixture.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class org.buddytools.talkt.auth.ClerkIsolationInstrumentedTest \
  -e clerkE2E true \
  org.buddytools.talkt.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s TalktClerkTest:I
```

An optional `talktApiUrl` instrumentation argument targets the validation Worker. The test restores the previous API URL afterwards, or the explicit `restoreApiUrl` argument when supplied. Synthetic provider keys do not prove that a funded OpenRouter key can run a model.

## Dependency fixes

Clerk Android 1.1.5 requires Kotlin 2.4.10. The app uses the compilerOptions DSL and excludes a duplicate OSGi metadata manifest from transitive dependencies.

The former OpenCode development package did not ship its Worker creation function. The Worker now pins `@opencode-ai/sdk` to `0.0.0-dev-19146`, which contains the function. Startup no longer holds a Durable Object concurrency lock across asynchronous SDK loading.

## Deployed result

The main Talkt API passed the two-account test on the Fold. Its log confirmed the main API URL, distinct users, Alice connected, Bob disconnected, separate archives and blocked access after sign-out. The installed sign-in screen was visually checked after unlocking the device. A native Android screen recording captures opening and closing the screen because Cap cannot directly capture the connected Android display.

## Google callback recovery

The first browser Google attempt returned to the sign-in screen without creating a session. The callback was the bare app URL. Clerk's server had marked the OAuth verification as transferable, but the callback omitted the status and error fields expected by Android SDK 1.1.5. Starting through sign-up created the account, while a second login reproduced the same omission in the reverse transfer.

Talkt now starts Google through sign-up and receives callbacks in `ClerkCallbackActivity`. For a bare callback only, it fetches the current Clerk attempt. It restores the SDK transfer marker only when the server returns an OAuth verification marked `TRANSFERABLE` with the matching external-account error. The SDK completes the transfer and activates the session. Callbacks containing a result pass through unchanged. The adapter checks the exact app callback scheme, host and path. It never creates a success nonce.

The callback regression checks exercise both transfer directions, preserve successful and explicit-error callbacks, and reject unrelated destinations. Before the fix, the missing-marker and destination checks failed on the Fold. Run them with:

```sh
adb shell am instrument -w \
  -e class org.buddytools.talkt.auth.ClerkCallbackInstrumentedTest \
  org.buddytools.talkt.test/androidx.test.runner.AndroidJUnitRunner
```

Reference: [Clerk AuthView options](https://clerk.com/docs/android/reference/views/authentication/auth-view). The original email-account integration test did not cover browser Google sign-in. Future auth verification must include both a first Google login and a returning Google account.

The clean installed build passed all six callback tests and two returning-account Google logins on the Fold. The account screen loaded provider status after authentication. Temporary diagnostics were removed. The full Google recording is shared privately because it includes the account chooser.
