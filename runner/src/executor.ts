import { spawn } from "node:child_process";
import { constants } from "node:fs";
import { open, realpath } from "node:fs/promises";
import { isAbsolute, relative, sep } from "node:path";
import { RUNNER_OUTPUT_LIMIT, type RunnerRequest, type RunnerResult } from "../../shared/runner-protocol";
import type { McpRegistry } from "./mcp";

export async function allowedPath(path: string, roots: string[]) {
  if (!isAbsolute(path)) throw new Error("An absolute path is required");
  const canonical = await realpath(path);
  const resolvedRoots = await Promise.all(roots.map(root => realpath(root).catch(() => null)));
  if (!resolvedRoots.some(root => {
    if (!root) return false;
    const suffix = relative(root, canonical);
    return suffix === "" || (!suffix.startsWith(`..${sep}`) && suffix !== ".." && !isAbsolute(suffix));
  })) throw new Error("Path is outside the configured runner roots");
  return canonical;
}

export function childEnvironment() {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && !key.startsWith("TALKT_")) env[key] = value;
  }
  return env;
}

export async function executeCommand(request: Extract<RunnerRequest, { kind: "exec" }>, roots: string[]): Promise<RunnerResult> {
  const cwd = await allowedPath(request.cwd, roots);
  const [command, ...args] = request.argv;
  if (!command) throw new Error("Missing executable");
  return new Promise(resolve => {
    const child = spawn(command, args, { cwd, env: childEnvironment(), detached: true, stdio: ["ignore", "pipe", "pipe"] });
    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    let size = 0;
    let truncated = false;
    let timedOut = false;
    const collect = (chunks: Buffer[], chunk: Buffer) => {
      const available = Math.max(0, RUNNER_OUTPUT_LIMIT - size);
      if (chunk.byteLength > available) truncated = true;
      const part = chunk.subarray(0, available);
      if (part.byteLength) chunks.push(part);
      size += part.byteLength;
    };
    child.stdout.on("data", (chunk: Buffer) => collect(stdout, chunk));
    child.stderr.on("data", (chunk: Buffer) => collect(stderr, chunk));
    const timer = setTimeout(() => {
      timedOut = true;
      if (child.pid) {
        try { process.kill(-child.pid, "SIGKILL"); } catch { child.kill("SIGKILL"); }
      }
    }, request.timeoutMs ?? 60_000);
    child.once("error", () => {
      clearTimeout(timer);
      resolve({ kind: "error", code: "spawn_failed", message: "The executable could not be started. Check its installation and permissions on the Mac." });
    });
    child.once("close", (exitCode, signal) => {
      clearTimeout(timer);
      resolve({ kind: "success", data: { stdout: Buffer.concat(stdout).toString("utf8"), stderr: Buffer.concat(stderr).toString("utf8"), exitCode, signal, timedOut, truncated } });
    });
  });
}

export async function executeRequest(request: RunnerRequest, roots: string[], mcp: McpRegistry): Promise<RunnerResult> {
  switch (request.kind) {
    case "exec": return executeCommand(request, roots);
    case "read_file": {
      const path = await allowedPath(request.path, roots);
      const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
      try {
        if (!(await file.stat()).isFile()) throw new Error("Only regular files can be read");
        const buffer = Buffer.alloc(RUNNER_OUTPUT_LIMIT + 1);
        const { bytesRead } = await file.read(buffer, 0, buffer.length, 0);
        return { kind: "success", data: { text: buffer.subarray(0, Math.min(bytesRead, RUNNER_OUTPUT_LIMIT)).toString("utf8"), truncated: bytesRead > RUNNER_OUTPUT_LIMIT } };
      } finally { await file.close(); }
    }
    case "mcp_list": return mcp.list(request.server);
    case "mcp_call": return mcp.call(request.server, request.tool, request.arguments);
  }
}
