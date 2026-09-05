# Fold ASR benchmark

This runs models directly on an Android ARM64 phone using the same Sherpa-ONNX 1.13.6 native library as Talkt. It does not install an APK or alter Talkt settings. The native process measures recognition, not microphone capture, Binder, UI or cloud fallback.

Dependencies are Bun, ADB, curl, ffmpeg, Python 3 with its standard library, and Android NDK 27.1.12297006. Use a dedicated directory, such as `/tmp/talkt-fra523`. Replace `SERIAL` below with the selected device from `adb devices -l`.

## Prepare inputs

Download [Sherpa Android 1.13.6](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6/sherpa-onnx-v1.13.6-android.tar.bz2) and extract it in the benchmark directory. Download [the matching C header](https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.6/sherpa-onnx/c-api/c-api.h) as `c-api.h` there. Keep header and library versions equal.

Prepare model subdirectories named `tiny`, `base`, `small` and `parakeet`. The first three need the matching `<size>-encoder.int8.onnx`, `<size>-decoder.int8.onnx` and `<size>-tokens.txt` files. Use multilingual weights, without `.en` in the model name.

- Tiny uses the existing Talkt assets. Their exact hashes are in [model-files.json](../../docs/research/fra-523/model-files.json).
- [Base revision bb53ee2](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/tree/bb53ee204431c90d314c1cc08d28d23e5b7927cc).
- [Small revision 8f3c18b](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/tree/8f3c18b358db4d1f2fc1eae49d75cd20989e4309).
- [Parakeet v3 INT8 archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2). Put its `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx` and `tokens.txt` in `parakeet`.

Collect 20 distinct reference sentences per language. The collector pins the FLEURS revision, takes the first qualifying files in each archive, converts float WAV to 16 kHz mono PCM16 and adds five seconds of digital silence. This is a convenience sample, not a random or full test-set evaluation. It downloads only the archive prefix needed for the sample.

```sh
/usr/bin/python3 scripts/asr-benchmark/collect_fleurs.py /tmp/talkt-fra523 20
```

FLEURS is by Google and distributed under [CC BY 4.0](https://huggingface.co/datasets/google/fleurs). The manifest retains references and source names. [FLEURS paper](https://arxiv.org/abs/2205.12446). Model licenses are listed in [the research report](../../docs/research/local-asr-models.md).

## Compile and run

```sh
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang" \
  -O2 -Wall -Wextra -Werror -I /tmp/talkt-fra523 \
  scripts/asr-benchmark/benchmark.c \
  -L /tmp/talkt-fra523/jniLibs/arm64-v8a -lsherpa-onnx-c-api \
  -o /tmp/talkt-fra523/benchmark

adb -s SERIAL shell mkdir -p /data/local/tmp/talkt-fra523
adb -s SERIAL push /tmp/talkt-fra523/benchmark \
  /tmp/talkt-fra523/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so \
  /tmp/talkt-fra523/jniLibs/arm64-v8a/libonnxruntime.so \
  /data/local/tmp/talkt-fra523/
adb -s SERIAL shell chmod 755 /data/local/tmp/talkt-fra523/benchmark
adb -s SERIAL push /tmp/talkt-fra523/audio /tmp/talkt-fra523/models/tiny \
  /tmp/talkt-fra523/models/base /tmp/talkt-fra523/models/small \
  /tmp/talkt-fra523/models/parakeet /data/local/tmp/talkt-fra523/

bun run scripts/asr-benchmark/run.ts SERIAL /tmp/talkt-fra523 tiny base small parakeet
bun run scripts/asr-benchmark/score.ts /tmp/talkt-fra523 tiny base small parakeet
```

The runner starts one model process at a time, uses two CPU threads and automatic language detection, and records two passes in the manifest's order. Finish all file transfers before timing inference. Model load time starts in a new process; OS page-cache state is uncontrolled, so this is not a cold-storage benchmark.

## Scoring

WER is total word-level Levenshtein errors divided by reference words, separately for English and Italian on pass one. Text is NFC-normalized, lowercased and split on punctuation and spaces; accents and digits remain. Numbers written as digits versus words count as different. Punctuation and capitalization quality are not scored. Pass two supplies median, nearest-rank p95 and aggregate RTF. Silence is scored separately, and changed predictions between passes are counted.

Peak RSS comes from Android `getrusage`, includes native runtime/model allocations, and is not whole-app RAM. Battery snapshots show test conditions, not CPU junction temperature or a controlled thermal test. These results do not establish personal dictation accuracy, noisy-input quality or end-to-end Talkt behavior.
