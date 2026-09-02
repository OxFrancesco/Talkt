# Local-first transcription

Talkt records one canonical 16 kHz mono PCM WAV. A single `Transcriber` first hands that exact file to a private `:local_asr` process running Sherpa-ONNX Whisper tiny multilingual int8. If the runtime is unavailable, low on memory, over the 60-second local limit, killed, or fails inference, the same WAV is uploaded to the existing Cloudflare endpoint for Gemini 3.5 Transcribe. Silence is terminal and is not uploaded.

The private process is deliberate: a native crash or OOM can kill local inference without killing the capture UI, leaving Gemini fallback reachable. The recognizer is lazy, serialized, uses two CPU threads, and is released under Android memory pressure.

Recording ends on trailing silence, on 8 s without any speech, or at a 60 s hard cap, whichever comes first. Speech is judged against a rolling noise floor (quietest 250 ms buffer in the last 4 s, times 2.5), not a fixed level: a fixed 450 RMS threshold sat below the room noise on the Fold next to a laptop, so the silence timer never started and captures ran until the user gave up. A mic silenced by Android's concurrent-capture policy (another app owns the input) is reported as such instead of being uploaded.

The capture bubble owns recording and transcription only. It shows the transcript with the engine and wall-clock transcription time, then closes. In agent mode it hands the transcript to `AgentRunService`, a foreground `dataSync` service that runs the prompt and posts the answer as a notification, so a slow agent never holds the overlay open.

`GEMINI_API_KEY` exists only as a Cloudflare Worker secret. The APK contains the local model and native arm64 runtime, plus the existing Worker app token; it never contains a Gemini credential.

Arena synthesis used candidate 1 as the base because its cache-backed clip and process boundary make audio identity and crash recovery structural. It grafted candidate 2's 60-second local ceiling and verification matrix. It rejected Android `SpeechRecognizer` because that service owns a different microphone capture, a downloadable first-run model because it makes fresh installs cloud-first, and in-activity orchestration because it leaks fallback policy into UI code.
