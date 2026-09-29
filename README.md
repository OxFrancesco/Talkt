# Talkt

Android dictation with Clerk accounts and cloud transcription through OpenRouter
`x-ai/grok-stt-1.0`.

Sign in or create an account, allow microphone access, then record. Talkt saves the
transcript in History and copies it. With accessibility enabled, dictation also
inserts text into the active editor. Settings contains recording shortcuts,
silence timeout, language, and optional cloud history backup.

Audio is sent through the Clerk-authenticated Cloudflare Worker. The OpenRouter
key stays on the server. Failed recordings stay on the phone for retry, scoped
to the account that recorded them. Successful audio is deleted after the
transcript is saved. Agent runs, conversations, connectors, Telegram commands,
and local transcription are removed.

## Development

Use Bun for JavaScript and the Android Studio JDK for Gradle:

```sh
bun install
bun run check
bun run test
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Set `OPENROUTER_API_KEY` in `worker/.dev.vars` for local development and as a
Cloudflare Worker secret for deployment. The public Clerk verification settings
live in `worker/wrangler.jsonc`; the Android publishable key can be supplied with
`CLERK_PUBLISHABLE_KEY`. The checked-in defaults use Talkt's existing development
Clerk instance. A public release needs matching production Clerk configuration.

The optional wake-word trigger still uses Sherpa. The APK excludes the retired
Parakeet model assets, so there is no transcription-model download step.

## Verification

`worker/test/` covers signed Clerk sessions, account-isolated cloud history,
removed endpoints, and the Grok request/response contract. Android unit tests
cover capture, recovery, account storage, and wake-word behavior.

The opt-in `ClerkIsolationInstrumentedTest` creates disposable users in the
Talkt development instance. It sends only `cloud-test.wav`, generated locally
from the text documented in `cloud-test.txt`. Run it on a dedicated emulator,
never over a personal signed-in session.

Legacy Durable Object classes retain stored data but cannot execute agent jobs.
The retired `runner/` directory remains to preserve pending local work; it has
no reachable runner endpoints. Unassigned recordings from older versions stay
on disk and are never automatically uploaded as a different account.
