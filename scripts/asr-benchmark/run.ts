import { join } from "node:path";

const [serial, directory, ...models] = Bun.argv.slice(2);
if (!serial || !directory || !models.length || models.some(model => !["tiny", "base", "small", "parakeet"].includes(model))) {
  throw new Error("Usage: bun run scripts/asr-benchmark/run.ts SERIAL DIRECTORY tiny base small parakeet");
}
const manifest: { file: string }[] = await Bun.file(join(directory, "manifest.json")).json();
if (manifest.some(clip => !/^[a-z0-9_-]+\.wav$/.test(clip.file))) throw new Error("Invalid audio filename");
const remote = "/data/local/tmp/talkt-fra523";
const quote = (value: string) => "'" + value.replaceAll("'", "'\\''") + "'";
async function adb(args: string[]) {
  const child = Bun.spawn(["adb", "-s", serial, ...args], { stdout: "pipe", stderr: "pipe" });
  const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
  if (code !== 0) throw new Error(`adb failed ${code}: ${stderr}`);
  return { stdout, stderr };
}
for (const model of models) {
  const before = await adb(["shell", "dumpsys battery | head -35"]);
  const command = `cd ${quote(remote)} && LD_LIBRARY_PATH=. ./benchmark ${quote(model)} ${quote(model)} auto 2 2 ${manifest.map(clip => quote(`audio/${clip.file}`)).join(" ")}`;
  console.log(`Running ${model}: ${manifest.length} clips, two passes, two CPU threads`);
  const startedAt = new Date().toISOString();
  const result = await adb(["shell", command]);
  const after = await adb(["shell", "dumpsys battery | head -35"]);
  const lines = result.stdout.trim().split("\n").map(line => JSON.parse(line));
  if (lines.length !== manifest.length * 2) throw new Error(`Incomplete ${model} run`);
  await Bun.write(join(directory, `${model}.jsonl`), result.stdout);
  await Bun.write(join(directory, `${model}.log`), result.stderr);
  await Bun.write(join(directory, `${model}-device.json`), JSON.stringify({ startedAt, endedAt: new Date().toISOString(), batteryBefore: before.stdout, batteryAfter: after.stdout }, null, 2) + "\n");
  console.log(`${model}: ${lines.length} predictions saved`);
}
