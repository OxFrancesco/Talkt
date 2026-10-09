
## Build, test, debug (Android app)

Gradle needs a JDK 17-21; the Homebrew default (`java` 26) fails with a bare `26.0.1` error.
Use the Android Studio JBR:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest -q      # JVM unit tests (~5s warm)
./gradlew :app:assembleDebug -q && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Every app log line uses the tag `Talkt`: `adb logcat -s Talkt:*` shows why a recording stopped
(reason, duration, noise floor, silenced flag), transcription engine and time, agent run time, and
the editor path (`editor target: captured=ime|node|none`, `editor commit ... inserted|rejected`,
`transcript delivered: Inserted|Copied|CopiedAccessibilityOff`).
Launch a capture without touching the phone, in its own task like the real triggers do (without
`-f` the bubble drags MainActivity in front of the editor and insertion falls back to copy):
`adb shell am start -f 0x18000000 -n org.buddytools.talkt/.capture.CaptureActivity`.
Screenshots on the Fold need the active physical display id: inspect
`adb shell dumpsys SurfaceFlinger --display-id`, then use `adb shell screencap -d <id> -p /sdcard/x.png`.
The inner display is `4630947004648141459`; the cover display is `4630947123231501204`.
Capturing the inactive display produces a black image.

Text insertion needs the accessibility service. Android turns it off on every `adb install -r`
and every force-stop (Samsung's app sleep does that too). After installing, re-enable it:

```
adb shell settings put secure enabled_accessibility_services org.buddytools.talkt/.trigger.TalktAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility | grep "Enabled services"
```

`./gradlew :app:connectedDebugAndroidTest` uninstalls the app afterwards, which wipes app data
(settings, Clerk session, Cabinet notes) and resets the assistant role. Never run it against the
daily phone without backing up `run-as org.buddytools.talkt` `shared_prefs/` and `files/` first.
Wake-word tuning has a device loop there: `WakeWordModelInstrumentedTest` sweeps quiet, noisy and
frame-offset variants of `androidTest/assets/wake_up.wav` and logs misses under the tag `TalktTest`.

Recordings stream to `files/recordings/*.part` while the mic is open and become `.wav` on stop; a
file left there means the bubble died or transcription failed. `PendingTranscriptionService`
transcribes them (auto from MainActivity resume, manual from the Cabinet's "Saved recordings").

The status-bar green microphone dot is Android's privacy indicator. Talkt cannot hide it, and on
this Fold (Android 17) even `adb shell device_config put privacy camera_mic_icons_enabled false` is
refused (flag not in the shell allowlist). Only privileged assistants with `HotwordDetectionService`
are exempt.

## Cloud transcription

Sign-in and account creation use Clerk. Recordings require a signed-in account and are sent
through the authenticated Worker to OpenRouter `x-ai/grok-stt-1.0`. Store `OPENROUTER_API_KEY`
as a Worker secret. No provider key belongs in the Android app.

The APK excludes the retired ONNX Parakeet assets. Sherpa remains only for the optional local
wake-word trigger. The opt-in Parakeet test mode uses QVAC's C++ GGUF engine from PR #290;
cloud remains the default. Do not restore the retired local agent routes.

Parakeet models are imported through Settings and stored in `files/parakeet/`, outside the APK.
CPU and Adreno OpenCL are built normally. Explicit accelerator selection must fail rather than
silently substitute CPU. Logs and the result show the resolved primary backend; unsupported
operations may still run on CPU. Cloud backup remains a separate opt-in setting.

Hexagon requires a compatible Qualcomm SDK and tools: set `HEXAGON_SDK_ROOT` and
`HEXAGON_TOOLS_ROOT`, then build with `-PtalktHexagon=true`. The optional DSP library currently
targets v81, the attached SM8850 phone. Do not label a GPU demo as an NPU demo.

The opt-in device test is `ParakeetDeviceTest`, with `-e verifyParakeet true` and a QVAC Q8 CTC
model staged at private `files/parakeet-test.gguf`. Build `:app:assembleDebugAndroidTest` and
install/run the test APK directly with adb; do not use `connectedDebugAndroidTest` on the daily
phone. The test checks GGUF import, real speech inference, explicit OpenCL backend identity,
and CPU/GPU transcript parity. It restores the user's settings after ordinary test completion.
UI and capture behavior must also be tested on-device with screenshots and video. The current
Espresso dependency fails on this phone's Android 17 because `InputManager.getInstance()` was
removed; the native device test deliberately does not depend on Espresso.

Android OpenCL uses a generated dispatch bridge that loads the public `libOpenCL.so` directly.
The Khronos desktop ICD loader hangs with `OCL_ICD_FILENAMES` pointing at this phone's vendor
library; manually registering that library still discovers zero platforms. Direct dispatch passes
CPU/GPU transcript parity on the SM_F971B. Native code uses RelWithDebInfo even in the debug
APK; unoptimized inference was about 80 seconds for a 6.6-second fixture. Warm OpenCL inference
is about 0.15 seconds on this phone, excluding model/kernel initialization. An interrupted
instrumentation run can leave test settings selected; check Settings before normal use.

Pending recordings are isolated by Clerk account. Older unassigned recordings remain in
`files/recordings/`; never automatically upload them as a newly signed-in account.

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
- `resources/mcp-remote` — Native OAuth MCP proxy for Talkt connectors
- `resources/google-workspace-java` — Official Google Drive API examples for file creation and uploads
- `resources/clerk-android` — Official Clerk Android SDK and authentication UI reference
- `resources/wear-os-samples` — Official Android Wear OS samples for modules, Compose, Data Layer, and device behavior
- `resources/android-compose` — Official Material 3 and adaptive Compose UI samples
- `resources/qvac-fabric-speech` — QVAC Parakeet GGUF speech engine and Android accelerator integration
- `resources/qvac-ext-ggml` — QVAC GGML Hexagon HTP and OpenCL speech backends

<!-- codeview:end -->
