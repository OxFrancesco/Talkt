# Parakeet dictation integration

Verified on 5 September 2026 with the USB-connected Fold 8, SM-F971B, Android 17.

Talkt's local dictation path now uses Parakeet TDT 0.6B v3 INT8 through Sherpa-ONNX 1.13.6. Recognition stays in the private ASR process. Language selection is automatic, with no recognizer reload when the language hint changes. The model does not return a detected-language label.

The [model comparison](research/local-asr-models.md) measured English WER at 7.3% and Italian WER at 2.7%, versus 15.2% and 34.6% for Whisper tiny, on 40 FLEURS recordings. These measurements describe that sample, not personal dictation accuracy.

## What changed

- Bundled Parakeet replaces Whisper tiny. `bun run models:download` fetches four files from a pinned revision and verifies their size and SHA-256. Gradle rejects missing or corrupt assets before packaging.
- Cold loading requires at least 1,792 MiB of available memory and no Android low-memory signal. A resident recognizer can be reused without repeating the cold-load threshold.
- Memory trimming releases the recognizer on the inference queue. A later capture reloads it. Prewarming lasts five minutes; starting capture in cloud-only mode releases the warm binding.
- Local failures retain the existing cloud fallback. Blank local output remains terminal and is not uploaded. Model attribution and the CC BY 4.0 license ship inside the APK.

## Verification

All 21 JVM tests, 11 Worker tests and four Android instrumentation tests passed. Worker and downloader TypeScript checks passed. Debug app and test APKs built successfully.

The four instrumentation tests ran in 23.207 seconds on the Fold through the actual packaged model and private Binder service:

| Test | Result |
| --- | --- |
| English and Italian fixtures | Expected speech returned locally, with no invented language label |
| Five seconds of digital silence | No-speech result; remote decoder never called |
| Sixty seconds of audio, then memory trimming | Both decodes succeeded with identical text; logs confirmed model reload |
| Kill the ASR process during a request | Fallback received the same captured WAV; the next local request recovered |

Service logs recorded model loads between 2.356 and 2.833 seconds. Warm decoding took 409 ms for 6.625 seconds of English, 261 ms for 4.140 seconds of Italian, and 4.054 seconds for the 60-second fixture. The repeated 60-second decode after trimming took 4.089 seconds.

The missing-token build check failed as intended. The downloader restored the pinned token file, skipped the valid ONNX files and reproduced the original bytes. APK inspection confirmed the four model files and both license files, with no Whisper assets.

## Costs and limits

The selected weights total 670,478,772 bytes. The tested debug APK is 760,410,368 bytes, about 725 MiB. Model files remain outside Git. A fresh checkout must download them before building.

The native comparison measured 1,518 MiB peak ASR process memory; a separate 60-second native run reached 1,682 MiB. These measurements exclude the app's UI process. Devices with insufficient available memory use the existing fallback.

The device tests inject WAV fixtures. They do not verify microphone capture, keyboard insertion, personal speech, background noise or real Gemini requests. The process-death test uses a fake remote decoder to verify fallback routing and audio identity. No UI recording was made for these headless tests.

Another Talkt task also uses the connected phone. The APK was installed for this test run, but this report does not assert which build remains installed afterward.
