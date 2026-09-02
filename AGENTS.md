
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

`app/src/main/assets/models/whisper-tiny/` is gitignored (the ONNX files are ~100 MB).
A fresh clone needs these files before building:

- `tiny-encoder.int8.onnx`, `tiny-decoder.int8.onnx`, `tiny-tokens.txt`
- Source: sherpa-onnx Whisper tiny int8 export — https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny

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

<!-- codeview:end -->
