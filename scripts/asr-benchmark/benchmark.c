#include "c-api.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <time.h>

static double now_ms(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1000.0 + t.tv_nsec / 1000000.0;
}

int main(int argc, char **argv) {
  if (argc < 7) {
    fprintf(stderr, "Usage: benchmark tiny|base|small|parakeet MODEL_DIR LANGUAGE THREADS REPEATS WAV...\n");
    return 2;
  }
  int parakeet = strcmp(argv[1], "parakeet") == 0;
  if (!parakeet && strcmp(argv[1], "tiny") && strcmp(argv[1], "base") && strcmp(argv[1], "small")) return 2;
  int threads = atoi(argv[4]), repeats = atoi(argv[5]);
  if (threads < 1 || threads > 8 || repeats < 1 || repeats > 20) return 2;
  char encoder[4096], decoder[4096], joiner[4096], tokens[4096];
  if (strlen(argv[2]) > 3900) return 2;
  SherpaOnnxOfflineRecognizerConfig config = {0};
  config.feat_config.sample_rate = 16000;
  config.feat_config.feature_dim = parakeet ? 128 : 80;
  config.model_config.provider = "cpu";
  config.model_config.num_threads = threads;
  config.decoding_method = "greedy_search";
  if (parakeet) {
    snprintf(encoder, sizeof encoder, "%s/encoder.int8.onnx", argv[2]);
    snprintf(decoder, sizeof decoder, "%s/decoder.int8.onnx", argv[2]);
    snprintf(joiner, sizeof joiner, "%s/joiner.int8.onnx", argv[2]);
    snprintf(tokens, sizeof tokens, "%s/tokens.txt", argv[2]);
    config.model_config.transducer.encoder = encoder;
    config.model_config.transducer.decoder = decoder;
    config.model_config.transducer.joiner = joiner;
    config.model_config.model_type = "nemo_transducer";
  } else {
    snprintf(encoder, sizeof encoder, "%s/%s-encoder.int8.onnx", argv[2], argv[1]);
    snprintf(decoder, sizeof decoder, "%s/%s-decoder.int8.onnx", argv[2], argv[1]);
    snprintf(tokens, sizeof tokens, "%s/%s-tokens.txt", argv[2], argv[1]);
    config.model_config.whisper.encoder = encoder;
    config.model_config.whisper.decoder = decoder;
    config.model_config.whisper.language = strcmp(argv[3], "auto") ? argv[3] : "";
    config.model_config.whisper.task = "transcribe";
    config.model_config.whisper.tail_paddings = 300;
    config.model_config.model_type = "whisper";
  }
  config.model_config.tokens = tokens;
  double start = now_ms();
  const SherpaOnnxOfflineRecognizer *recognizer = SherpaOnnxCreateOfflineRecognizer(&config);
  if (!recognizer) return 3;
  double load_ms = now_ms() - start;
  for (int repeat = 0; repeat < repeats; repeat++) {
    for (int i = 6; i < argc; i++) {
      const SherpaOnnxWave *wave = SherpaOnnxReadWave(argv[i]);
      if (!wave || wave->sample_rate != 16000 || wave->num_samples < 1) return 4;
      start = now_ms();
      const SherpaOnnxOfflineStream *stream = SherpaOnnxCreateOfflineStream(recognizer);
      if (!stream) return 5;
      SherpaOnnxAcceptWaveformOffline(stream, wave->sample_rate, wave->samples, wave->num_samples);
      SherpaOnnxDecodeOfflineStream(recognizer, stream);
      double decode_ms = now_ms() - start;
      const char *result = SherpaOnnxGetOfflineStreamResultAsJson(stream);
      if (!result) return 6;
      struct rusage usage;
      getrusage(RUSAGE_SELF, &usage);
      printf("{\"model\":\"%s\",\"threads\":%d,\"repeat\":%d,\"clip_index\":%d,\"load_ms\":%.3f,\"decode_ms\":%.3f,\"audio_ms\":%.3f,\"peak_rss_kib\":%ld,\"result\":%s}\n",
             argv[1], threads, repeat, i - 6, load_ms, decode_ms,
             wave->num_samples * 1000.0 / wave->sample_rate, usage.ru_maxrss, result);
      fflush(stdout);
      SherpaOnnxDestroyOfflineStreamResultJson(result);
      SherpaOnnxDestroyOfflineStream(stream);
      SherpaOnnxFreeWave(wave);
    }
  }
  SherpaOnnxDestroyOfflineRecognizer(recognizer);
  return 0;
}
