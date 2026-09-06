
## Build, test, debug (Android app)

Gradle needs a JDK 17-21; the Homebrew default (`java` 26) fails with a bare `26.0.1` error.
Use the Android Studio JBR:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest -q      # JVM unit tests (~5s warm)
./gradlew :app:assembleDebug -q && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Every app log line uses the tag `Talkt`: `adb logcat -s Talkt:*` shows why a recording stopped
(reason, duration, noise floor, silenced flag), transcription engine and time, and agent run time.
Launch a capture without touching the phone: `adb shell am start -n org.buddytools.talkt/.capture.CaptureActivity`.
Screenshots on the Fold need the physical display id: `adb shell screencap -d 4630947123231501204 -p /sdcard/x.png`.

## On-device ASR models (not in git)

Run `bun run models:download` before building a fresh clone. This downloads the pinned
Parakeet TDT 0.6B v3 INT8 files to `app/src/main/assets/models/parakeet-tdt-v3/` and
verifies their SHA-256 hashes. `bun run models:check` verifies existing files offline.
The Android asset-merge tasks also reject missing or invalid model files.

The model files total 670.5 MB and remain gitignored. Remove any obsolete local
`models/whisper-tiny/` assets before building to avoid shipping both models.
The APK bundles Parakeet, so a fresh install can transcribe without a model download.
The pinned source and file hashes are in `models/parakeet-tdt-v3.json`.

<!-- codeview:start -->

## Reference codebases (codeview)

The `resources/` folder contains read-only clones of reference codebases.
If you need to implement code specific to one of these codebases, read the relevant
folder to gather information, feedback, patterns, and templates before writing code.

- `resources/opencode` — Official OpenCode source — CLI model discovery, run flags, and reasoning variants
- `resources/cloudflare-workers-sdk` — Official Cloudflare Workers SDK and Wrangler reference for Worker deployment and R2 bindings
- `resources/cloudflare-sandbox-sdk` — Official Cloudflare Sandbox SDK source, current API types, lifecycle, tunnels, and examples for iChef runtime proof
- `resources/aosp-latinime` — AOSP LatinIME reference for pointer tracking, key detection, layout state, editor integration, and compatibility
- `resources/sherpa-onnx` — Official Sherpa-ONNX source — Android offline ASR JNI/AAR integration, models, and streaming/offline examples
- `resources/thinking-orbs-kotlin` — Native Jetpack Compose Thinking Orbs band animation reference

- `resources/clerk-android` — Official Clerk Android SDK and authentication UI reference

<!-- codeview:end -->
