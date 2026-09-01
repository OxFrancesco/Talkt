# Local-first transcription

Talkt records one canonical 16 kHz mono PCM WAV. A single `Transcriber` first hands that exact file to a private `:local_asr` process running Sherpa-ONNX Whisper tiny multilingual int8. If the runtime is unavailable, low on memory, over the 60-second local limit, killed, or fails inference, the same WAV is uploaded to the existing Cloudflare endpoint for Gemini 3.5 Transcribe. Silence is terminal and is not uploaded.

The private process is deliberate: a native crash or OOM can kill local inference without killing the capture UI, leaving Gemini fallback reachable. The recognizer is lazy, serialized, uses two CPU threads, and is released under Android memory pressure.

`GEMINI_API_KEY` exists only as a Cloudflare Worker secret. The APK contains the local model and native arm64 runtime, plus the existing Worker app token; it never contains a Gemini credential.

Arena synthesis used candidate 1 as the base because its cache-backed clip and process boundary make audio identity and crash recovery structural. It grafted candidate 2's 60-second local ceiling and verification matrix. It rejected Android `SpeechRecognizer` because that service owns a different microphone capture, a downloadable first-run model because it makes fresh installs cloud-first, and in-activity orchestration because it leaks fallback policy into UI code.
