import { join } from "node:path";

const [directory, ...models] = Bun.argv.slice(2);
if (!directory || !models.length) throw new Error("Usage: bun run scripts/asr-benchmark/score.ts DIRECTORY MODEL...");
type Clip = { file: string; language: string; reference: string };
type Prediction = { clip_index: number; repeat: number; load_ms: number; decode_ms: number; audio_ms: number; peak_rss_kib: number; result: { text: string } };
const clips: Clip[] = await Bun.file(join(directory, "manifest.json")).json();
const words = (value: string) => value.normalize("NFC").toLocaleLowerCase("en").replace(/[^\p{L}\p{N}]+/gu, " ").trim().split(/\s+/).filter(Boolean);
function distance(a: string[], b: string[]) {
  let row = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const next = [i];
    for (let j = 1; j <= b.length; j++) next.push(Math.min(row[j] + 1, next[j - 1] + 1, row[j - 1] + Number(a[i - 1] !== b[j - 1])));
    row = next;
  }
  return row[b.length];
}
const quantile = (values: number[], q: number) => [...values].sort((a, b) => a - b)[Math.ceil(values.length * q) - 1];
const summary = [];
for (const model of models) {
  const predictions: Prediction[] = (await Bun.file(join(directory, `${model}.jsonl`)).text()).trim().split("\n").map(line => JSON.parse(line));
  if (predictions.length !== clips.length * 2) throw new Error(`${model}: incomplete run`);
  const pairs = new Set(predictions.map(p => `${p.repeat}:${p.clip_index}`));
  if (pairs.size !== predictions.length || predictions.some(p => ![0, 1].includes(p.repeat) || !Number.isInteger(p.clip_index) || !clips[p.clip_index] || !Number.isFinite(p.decode_ms) || p.decode_ms <= 0 || p.audio_ms <= 0)) throw new Error(`${model}: invalid prediction rows`);
  const first = predictions.filter(p => p.repeat === 0);
  const warm = predictions.filter(p => p.repeat === 1 && clips[p.clip_index].language !== "none");
  const byLanguage = ["en", "it"].map(language => {
    const selected = first.filter(p => clips[p.clip_index].language === language);
    const referenceWords = selected.reduce((sum, p) => sum + words(clips[p.clip_index].reference).length, 0);
    const errors = selected.reduce((sum, p) => sum + distance(words(clips[p.clip_index].reference), words(p.result.text)), 0);
    return { language, clips: selected.length, referenceWords, errors, wer: errors / referenceWords };
  });
  summary.push({ model, byLanguage, loadMs: first[0].load_ms, firstDecodeMs: first[0].decode_ms, warmMedianMs: quantile(warm.map(p => p.decode_ms), .5), warmP95Ms: quantile(warm.map(p => p.decode_ms), .95), warmRtf: warm.reduce((s, p) => s + p.decode_ms, 0) / warm.reduce((s, p) => s + p.audio_ms, 0), peakRssMiB: Math.max(...predictions.map(p => p.peak_rss_kib)) / 1024, silence: predictions.filter(p => clips[p.clip_index].language === "none").map(p => p.result.text), changedPredictions: first.filter(p => p.result.text !== predictions.find(q => q.repeat === 1 && q.clip_index === p.clip_index)?.result.text).length });
}
await Bun.write(join(directory, "summary.json"), JSON.stringify(summary, null, 2) + "\n");
console.log(JSON.stringify(summary, null, 2));
