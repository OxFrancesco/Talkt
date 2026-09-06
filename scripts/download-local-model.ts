import { createHash, randomUUID } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { mkdir, rename, rm, stat } from "node:fs/promises";
import { join } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import manifest from "../models/parakeet-tdt-v3.json";

const checkOnly = process.argv.includes("--check");
const directory = fileURLToPath(new URL(`../app/src/main/assets/models/${manifest.directory}/`, import.meta.url));
await mkdir(directory, { recursive: true });

async function matches(path: string, expected: (typeof manifest.files)[number]) {
  try {
    if ((await stat(path)).size !== expected.bytes) return false;
  } catch (error) {
    if (error instanceof Error && "code" in error && error.code === "ENOENT") return false;
    throw error;
  }
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex") === expected.sha256;
}

for (const file of manifest.files) {
  const destination = join(directory, file.file);
  if (await matches(destination, file)) {
    process.stdout.write(`Verified ${file.file}\n`);
    continue;
  }
  if (checkOnly) throw new Error(`Missing or invalid ${file.file}. Run bun run models:download.`);
  const temporary = `${destination}.${randomUUID()}.part`;
  try {
    process.stdout.write(`Downloading ${file.file}\n`);
    const response = await fetch(`${manifest.source}/${file.file}`, { signal: AbortSignal.timeout(20 * 60 * 1000) });
    if (!response.ok || !response.body) throw new Error(`Download failed: HTTP ${response.status}`);
    await pipeline(Readable.fromWeb(response.body), createWriteStream(temporary, { flags: "wx" }));
    if (!(await matches(temporary, file))) throw new Error(`Integrity check failed for ${file.file}`);
    await rename(temporary, destination);
    process.stdout.write(`Verified ${file.file}\n`);
  } finally {
    await rm(temporary, { force: true });
  }
}
