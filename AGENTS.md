
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

<!-- codeview:end -->
