# Local speech recognition for Talkt

Research date: 2026-09-05. FRA-523 concerns dictation. Required languages are English and Italian, with the existing English, Italian, Spanish, French, German and Portuguese choices preserved. This document separates published capabilities from on-device measurements.

## Recommendation

**Choose Parakeet TDT 0.6B v3 INT8 for the Fold 8.** It beat Whisper tiny, base and small on both English and Italian word error rate in the device test below, with the lowest median warm decode time. It covers all six Talkt languages. The cost is a 487.2 MB download, about 670.5 MB of selected model files and 1.48 GiB measured peak native process memory.

Use Whisper base INT8 as the smaller-device candidate and retain tiny where memory or storage prevents larger models. Base's Italian accuracy remains substantially worse than Parakeet here. Qwen3-ASR and Omnilingual remain unmeasured alternatives, so this result establishes a winner among the four tested models, not every available ASR model.

## Fold 8 results

Measured on the USB-connected Samsung SM-F971B, SoC SM8850, Android 17. Sherpa-ONNX 1.13.6 ARM64 CPU, two threads, automatic language detection. The C API and ONNX Runtime libraries are SHA-256 identical to the libraries inside Talkt's AAR. The benchmark ran in a separate native shell process. No APK, app settings or production backend changed.

The sample has 20 English and 20 Italian FLEURS test recordings with distinct sentence IDs within each language, 448.94 seconds total, plus five seconds of generated digital silence. All four models ran the identical inputs twice, one model at a time. Predictions were unchanged between passes.

| Model, INT8 | English WER | Italian WER | Warm median | Warm p95 | Aggregate RTF | Peak RSS | Model load |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Whisper tiny | 15.2% | 34.6% | 0.740 s | 1.543 s | 0.075 | 482 MiB | 0.523 s |
| Whisper base | 10.9% | 21.4% | 0.953 s | 2.724 s | 0.109 | 661 MiB | 1.023 s |
| Whisper small | 8.4% | 9.3% | 2.914 s | 7.590 s | 0.313 | 1,168 MiB | 2.674 s |
| Parakeet TDT v3 | 7.3% | 2.7% | 0.598 s | 1.145 s | 0.061 | 1,518 MiB | 2.313 s |

On Italian, Parakeet made 13 word errors against 168 for tiny over 486 reference words, a 92.3% relative error reduction in this sample. English had 32 versus 67 errors over 440 words. Parakeet's 0.623-second first decode followed the 2.313-second model load, so a newly started process took about 2.94 seconds before its first result. Warm-up matters.

Tiny and base emitted text for digital silence on both passes. Small and Parakeet returned empty text. This bypassed Talkt's capture/VAD path, so it is a model-level result, not evidence that the app uploaded silence or displayed those outputs. Preserve speech detection and the terminal no-speech behavior.

These are convenience-sample results from read speech. They do not establish performance on Francesco's voice, short commands, code names, noise, mixed-language sentences or all other supported languages. WER ignores punctuation and capitalization, preserves accents and counts digit-versus-word formatting differences. The phone was USB-connected with ordinary apps present; battery temperature ranged from 33.9 to 36.5 degrees C. Run order was tiny, base, small, Parakeet. No controlled cooldown or CPU temperature experiment was performed.

Model load used a new process, with OS page-cache state uncontrolled. Native RSS excludes Talkt's UI process. Microphone capture, Binder, app lifecycle recovery and cloud fallback were not exercised by this benchmark. The subsequent [integration report](../parakeet-integration.md) records the packaged-app tests separately.

Evidence: [raw summary](fra-523/summary.json), [corpus references](fra-523/manifest.json), [model hashes](fra-523/model-files.json), [device/runtime record](fra-523/device.json), [reproduction instructions and scripts](../../scripts/asr-benchmark/README.md). Raw predictions and per-run battery snapshots are in `docs/research/fra-523/`. FLEURS is a Google dataset under CC BY 4.0. [Dataset and license](https://huggingface.co/datasets/google/fleurs), [paper](https://arxiv.org/abs/2205.12446).

## Shortlist and concrete artifacts

| Model | Fit for Talkt | Files and size | Priority |
| --- | --- | --- | --- |
| Parakeet TDT 0.6B v3 INT8 | 25 European languages, including all six Talkt languages. Offline model; sherpa provides simulated-streaming Android APKs. | `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2`, 487.2 MB download. Published extracted ONNX sizes total about 640 MiB. | First broad challenger. |
| Whisper base multilingual INT8 | Same model family and runtime as the existing tiny path. 74M parameters. | `base-encoder.int8.onnx`, `base-decoder.int8.onnx`, `base-tokens.txt`: 160.6 MB combined. | First smaller upgrade. |
| Whisper small multilingual INT8 | Same integration route; 244M parameters. | `small-encoder.int8.onnx`, `small-decoder.int8.onnx`, `small-tokens.txt`: 375.5 MB combined. | Compare if latency/memory allow. |
| Qwen3-ASR 0.6B INT8 | 30 languages plus Chinese dialects. All six Talkt languages. sherpa documents Android and Kotlin/Java support. | `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2`, 878.7 MB download. Requires convolution frontend, encoder, decoder and tokenizer directory. | Second-stage accuracy test. |
| Omnilingual ASR CTC 300M v2 INT8 | Broad 1,600+ language coverage; CTC alternative with sherpa support. Validate English and Italian normalization/accuracy. | `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2`, 292.3 MB download. | Secondary non-autoregressive comparator. |
| Whisper large-v3-turbo INT8 | Multilingual transcription, 809M parameters. Reduced decoder depth relative to large-v3. | `turbo-encoder.int8.onnx`, `turbo-decoder.int8.onnx`, `turbo-tokens.txt`: 1,036.6 MB combined. | Quality reference if smaller models fail. |

Sources: [Parakeet model](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [sherpa Parakeet files and Android route](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html#sherpa-onnx-nemo-parakeet-tdt-0-6b-v3-int8-25-european-languages), [Whisper model family](https://github.com/openai/whisper), [base files](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/tree/main), [small files](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/tree/main), [turbo files](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/tree/main), [Qwen Android compatibility](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html), [Qwen package](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html), [Omnilingual integration](https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/index.html).

Sizes were checked against the live Hugging Face file metadata and [sherpa release asset API](https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models). MB means decimal megabytes. Compressed package size, selected model-file size and peak RAM are different measurements. Whisper archives can include additional precision variants, so download only the required INT8 encoder/decoder and matching tokens when measuring app growth. Never select `.en` weights for Italian.

## Integration and limitations

Parakeet uses sherpa's offline transducer configuration with `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt` and model type `nemo_transducer`. Its Android demo runs the offline model repeatedly over audio segments; that is not proof of native incremental decoding. Measure final transcript delay after the user stops talking. [sherpa configuration](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html)

Qwen3-ASR 0.6B already has a sherpa Android route, so introducing MNN is unnecessary for the first comparison. MNN's Qwen3-ASR exporter/runtime change merged on August 11, 2026, but its stated validation is Linux C++ output parity. That does not establish Android speed or integration. Qwen3-ASR 1.7B is a larger accuracy reference, not the first mobile candidate; the reviewed sherpa package targets 0.6B. [sherpa platforms](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html), [MNN change](https://github.com/alibaba/MNN/pull/4478), [Qwen models](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)

Whisper turbo's published relative speed and VRAM table use an A100 GPU, not Android INT8 CPU inference. Likewise, Qwen's high-concurrency throughput and Omnilingual's A100 figures cannot predict a phone's single-utterance latency. [Whisper benchmark setup](https://github.com/openai/whisper), [Qwen benchmark context](https://huggingface.co/Qwen/Qwen3-ASR-0.6B), [Omnilingual benchmark setup](https://huggingface.co/facebook/omniASR-CTC-300M)

## Candidates excluded from a multilingual default

Moonshine's current upstream catalog includes streaming English, German and Spanish, among other languages, but no Italian, French or Portuguese. It is a useful English-only streaming comparison, not a replacement for Talkt's language selector. Its shipped quantized models also have different measured WER from the floating-point paper models. Do not transfer the headline benchmark directly to the Android artifacts. [Current Moonshine models](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/), [quantized accuracy](https://moonshine-voice.readthedocs.io/en/latest/models/accuracy/)

SenseVoiceSmall supports Mandarin, Cantonese, English, Japanese and Korean. The broader research discusses more languages, but the released checkpoint does not support Italian. Exclude it from this selection. [Released checkpoint scope](https://github.com/QwenAudio/SenseVoice)

Whisper code and weights are MIT. Parakeet v3 weights are CC-BY-4.0. Qwen3-ASR and Omnilingual model cards identify Apache-2.0. Include the selected model's license and attribution with its downloadable assets. [Whisper](https://github.com/openai/whisper), [Parakeet](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [Qwen](https://huggingface.co/Qwen/Qwen3-ASR-0.6B), [Omnilingual](https://huggingface.co/facebook/omniASR-CTC-300M)

## Integration checks still required

Keep recognition in Talkt's private process. Parakeet requires the transducer configuration rather than Whisper encoder/decoder configuration. Budget for the measured 1.48 GiB process, warm it asynchronously and preserve a smaller-model or remote fallback when loading fails or Android kills the process. Do not infer that every phone with the same total RAM has sufficient available memory.

Before shipping, test personal dictation, short commands, names, code terms, numbers, accents, background noise and speech followed by silence through Talkt's capture and output path. Record raw predictions, references, punctuation/number errors, silence hallucinations and final transcript delay.

Measure cold load, warm inference time, peak process memory, app background/restart behavior and sustained thermal behavior. Repeat with other ordinary phone apps present. Keep model hashes, runtime version, thread count, device/OS identity and audio files with the results. Synthetic speech and one clean sample can check executable compatibility; they cannot establish the best dictation model.
