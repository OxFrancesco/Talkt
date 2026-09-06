import { chmod, mkdir, open, readFile, stat, unlink } from "node:fs/promises";
import { homedir, hostname } from "node:os";
import { dirname, join, resolve } from "node:path";
import { record, validateRunnerJob, type RunnerSubmission } from "../../shared/runner-protocol";
import { executeRequest } from "./executor";
import { Journal } from "./journal";
import { McpRegistry } from "./mcp";

function log(event: string, fields: Record<string, unknown> = {}) {
  process.stderr.write(`${JSON.stringify({ time: new Date().toISOString(), event, ...fields })}\n`);
}
function rootsFromEnv() {
  const configured = process.env.TALKT_RUNNER_ROOTS;
  if (configured) {
    const roots: unknown = JSON.parse(configured);
    if (!Array.isArray(roots) || roots.length === 0 || roots.some(root => typeof root !== "string" || !root.startsWith("/"))) throw new Error("TALKT_RUNNER_ROOTS must be a JSON array of absolute paths");
    return roots as string[];
  }
  return [resolve(import.meta.dir, "../.."), join(homedir(), ".agents/skills"), join(homedir(), ".codex/skills"), join(homedir(), ".claude/skills")];
}
async function tokenFromEnv() {
  const file = process.env.TALKT_RUNNER_TOKEN_FILE;
  if (file) {
    const mode = (await stat(file)).mode;
    if ((mode & 0o077) !== 0) throw new Error("Runner token file must be private, use chmod 600");
    return (await readFile(file, "utf8")).trim();
  }
  return process.env.TALKT_RUNNER_TOKEN?.trim();
}
async function acquireLock(path: string) {
  await mkdir(dirname(path), { recursive: true, mode: 0o700 });
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const file = await open(path, "wx", 0o600);
      await file.writeFile(String(process.pid));
      await file.sync();
      await file.close();
      return async () => { await unlink(path); };
    } catch (error) {
      if (!(error instanceof Error) || !("code" in error) || error.code !== "EEXIST") throw error;
      const pid = Number(await readFile(path, "utf8"));
      let alive = true;
      if (!Number.isInteger(pid) || pid <= 0) throw new Error("Runner lock is invalid. Inspect it before removing it.");
      try { process.kill(pid, 0); } catch (error) {
        if (error instanceof Error && "code" in error && error.code === "ESRCH") alive = false;
      }
      if (alive) throw new Error("Another runner uses this journal");
      await unlink(path);
    }
  }
  throw new Error("Could not acquire runner lock");
}
class HttpFailure extends Error {
  constructor(readonly status: number) { super(`Runner API returned HTTP ${status}`); }
}
async function main() {
  const roots = rootsFromEnv();
  const mcp = await McpRegistry.load();
  if (process.argv.includes("--selftest")) {
    const result = await executeRequest({ kind: "exec", argv: [process.execPath, "-e", "process.stdout.write('talkt-runner-ok')"], cwd: roots[0] }, roots, mcp);
    if (result.kind !== "success" || record(result.data).stdout !== "talkt-runner-ok") throw new Error("Runner selftest failed");
    process.stdout.write(`${JSON.stringify({ selftest: "passed", roots, mcp: (await mcp.list()) })}\n`);
    return;
  }
  if (process.argv.includes("--inventory")) {
    process.stdout.write(`${JSON.stringify(await mcp.list(), null, 2)}\n`);
    return;
  }
  const urlValue = process.env.TALKT_API_URL;
  if (!urlValue) throw new Error("Set TALKT_API_URL");
  const api = new URL(urlValue);
  if (api.username || api.password || api.search || api.hash || (api.protocol !== "https:" && !(api.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(api.hostname)))) throw new Error("TALKT_API_URL requires HTTPS, or loopback HTTP, without credentials or query parameters");
  const token = await tokenFromEnv();
  if (!token) throw new Error("Set TALKT_RUNNER_TOKEN_FILE or TALKT_RUNNER_TOKEN");
  mcp.addSecret(token);
  const scope = new Bun.CryptoHasher("sha256").update(api.origin).digest("hex").slice(0, 16);
  const state = process.env.TALKT_RUNNER_STATE_DIR ?? join(homedir(), ".local/state/talkt-runner", scope);
  await mkdir(state, { recursive: true, mode: 0o700 });
  await chmod(state, 0o700);
  const unlock = await acquireLock(join(state, "runner.lock"));
  const journal = await Journal.open(join(state, "jobs.sqlite"));
  const runnerID = process.env.TALKT_RUNNER_ID ?? `${hostname()}-${scope}`;
  let stopped = false;
  const stop = () => { stopped = true; log("stopping_after_current_job"); };
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
  const post = async (path: string, body: unknown) => {
    const response = await fetch(new URL(path, api), { method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(30_000), redirect: "error" });
    if (!response.ok) { await response.body?.cancel(); throw new HttpFailure(response.status); }
    const contentLength = Number(response.headers.get("content-length") ?? 0);
    if (contentLength > 262_144) { await response.body?.cancel(); throw new Error("Runner API response exceeds limit"); }
    if (!response.body) return {};
    const reader = response.body.getReader();
    const parts: Uint8Array[] = [];
    let size = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 262_144) { await reader.cancel(); throw new Error("Runner API response exceeds limit"); }
      parts.push(value);
    }
    const text = Buffer.concat(parts).toString("utf8");
    return text ? JSON.parse(text) as unknown : {};
  };
  try {
    log("started", { runnerID, roots, mcpWarnings: mcp.warnings.length });
    while (!stopped) {
      try {
        const poll = record(await post("/v1/runner/poll", { runnerID }));
        if (poll.job === null) { await Bun.sleep(2_000); continue; }
        const job = validateRunnerJob(poll.job);
        log("job_started", { id: job.id, kind: job.request.kind });
        const result = await journal.run(job, async () => mcp.sanitize(await executeRequest(job.request, roots, mcp)));
        const submission: RunnerSubmission = { runnerID, id: job.id, leaseToken: job.leaseToken, result };
        do {
          try {
            await post("/v1/runner/result", submission);
            log("job_reported", { id: job.id, result: result.kind });
            break;
          } catch (error) {
            if (error instanceof HttpFailure && [409, 410].includes(error.status)) { log("lease_expired", { id: job.id }); break; }
            if (error instanceof HttpFailure && [401, 403, 404].includes(error.status)) throw error;
            log("result_upload_retry", { id: job.id });
            if (!stopped) await Bun.sleep(5_000);
          }
        } while (!stopped);
      } catch (error) {
        if (error instanceof HttpFailure && [401, 403, 404].includes(error.status)) throw error;
        log("poll_retry");
        if (!stopped) await Bun.sleep(5_000);
      }
    }
  } finally {
    process.off("SIGINT", stop);
    process.off("SIGTERM", stop);
    journal.close();
    await unlock();
  }
}

main().catch(error => {
  log("fatal", { message: error instanceof HttpFailure ? error.message : "Runner could not start or continue. Check the documented URL, token file, roots and journal lock settings." });
  process.exitCode = 1;
});
