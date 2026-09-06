import { afterEach, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { mkdtemp, mkdir, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { RUNNER_OUTPUT_LIMIT, RUNNER_RESULT_LIMIT, validateRunnerRequest, validateRunnerResult, type RunnerJob } from "../../shared/runner-protocol";
import { allowedPath, executeCommand, executeRequest } from "../src/executor";
import { Journal } from "../src/journal";
import { McpRegistry } from "../src/mcp";

const temporary: string[] = [];
async function temp() { const path = await mkdtemp(join(tmpdir(), "talkt-runner-test-")); temporary.push(path); return path; }
afterEach(async () => { await Promise.all(temporary.splice(0).map(path => rm(path, { recursive: true, force: true }))); });

test("protocol rejects missing fields, null bytes, invalid timeout and oversized UTF-8 output", () => {
  for (const input of [null, {}, { kind: "exec", argv: [], cwd: "/tmp" }, { kind: "exec", argv: ["x\0"], cwd: "/tmp" }, { kind: "exec", argv: ["x"], cwd: "/tmp", timeoutMs: 120001 }, { kind: "mcp_call", server: "x", tool: "y", arguments: [] }]) expect(() => validateRunnerRequest(input)).toThrow();
  expect(validateRunnerRequest({ kind: "exec", argv: ["echo", ""], cwd: "/tmp", timeoutMs: 100 })).toEqual({ kind: "exec", argv: ["echo", ""], cwd: "/tmp", timeoutMs: 100 });
  expect(() => validateRunnerResult({ kind: "success", data: "€".repeat(RUNNER_RESULT_LIMIT / 2) })).toThrow();
});

test("exec passes argv literally and removes runner credentials from the child", async () => {
  const cwd = await temp();
  process.env.TALKT_RUNNER_TOKEN = "runner-test-secret";
  try {
    const result = await executeCommand({ kind: "exec", cwd, argv: [process.execPath, "-e", "process.stdout.write(JSON.stringify({args:process.argv.slice(1),token:process.env.TALKT_RUNNER_TOKEN??null}))", "$(touch should-not-exist)", "a b"] }, [cwd]);
    expect(result.kind).toBe("success");
    if (result.kind !== "success") throw new Error("exec failed");
    const data = result.data as { stdout: string; exitCode: number };
    expect(JSON.parse(data.stdout)).toEqual({ args: ["$(touch should-not-exist)", "a b"], token: null });
    expect(data.exitCode).toBe(0);
    expect(await Bun.file(join(cwd, "should-not-exist")).exists()).toBe(false);
  } finally { delete process.env.TALKT_RUNNER_TOKEN; }
});

test("exec bounds combined output and kills timed-out subprocesses", async () => {
  const cwd = await temp();
  const noisy = await executeCommand({ kind: "exec", cwd, argv: [process.execPath, "-e", "process.stdout.write('x'.repeat(200000)); process.stderr.write('y'.repeat(200000))"] }, [cwd]);
  if (noisy.kind !== "success") throw new Error("exec failed");
  const output = noisy.data as { stdout: string; stderr: string; truncated: boolean };
  expect(Buffer.byteLength(output.stdout + output.stderr)).toBe(RUNNER_OUTPUT_LIMIT);
  expect(output.truncated).toBe(true);
  const start = Date.now();
  const slow = await executeCommand({ kind: "exec", cwd, argv: [process.execPath, "-e", "setTimeout(()=>{},10000)"], timeoutMs: 50 }, [cwd]);
  expect(Date.now() - start).toBeLessThan(2000);
  expect(slow.kind === "success" && (slow.data as { timedOut: boolean }).timedOut).toBe(true);
});

test("path checks reject traversal, sibling prefix and symlink escape; file reads are bounded", async () => {
  const base = await temp();
  const root = join(base, "root");
  const sibling = join(base, "root-secret");
  await mkdir(root); await mkdir(sibling);
  await writeFile(join(sibling, "secret"), "secret");
  await symlink(sibling, join(root, "escape"));
  await expect(allowedPath(join(root, "../root-secret/secret"), [root])).rejects.toThrow();
  await expect(allowedPath(join(root, "escape/secret"), [root])).rejects.toThrow();
  await expect(allowedPath("relative", [root])).rejects.toThrow();
  const file = join(root, "large.txt");
  await writeFile(file, "a".repeat(RUNNER_OUTPUT_LIMIT + 100));
  const result = await executeRequest({ kind: "read_file", path: file }, [root], await McpRegistry.load(base));
  expect(result.kind === "success" && (result.data as { truncated: boolean }).truncated).toBe(true);
});

test("journal returns completed results after restart and never repeats interrupted work", async () => {
  const path = join(await temp(), "jobs.sqlite");
  const job: RunnerJob = { id: "a", leaseToken: "first", request: { kind: "exec", cwd: "/tmp", argv: ["echo", "hello"] } };
  let executions = 0;
  const execute = async () => { executions++; return { kind: "success", data: "done" } as const; };
  let journal = await Journal.open(path);
  expect(await journal.run(job, execute)).toEqual({ kind: "success", data: "done" });
  journal.close();
  journal = await Journal.open(path);
  expect(await journal.run({ ...job, leaseToken: "renewed" }, execute)).toEqual({ kind: "success", data: "done" });
  expect(executions).toBe(1);
  expect(await journal.run({ ...job, request: { kind: "read_file", path: "/tmp" } }, execute)).toMatchObject({ kind: "error", code: "job_conflict" });
  journal.close();
  const db = new Database(path);
  db.query("INSERT INTO jobs (id,request,started_at) VALUES (?,?,?)").run("interrupted", JSON.stringify(job.request), new Date().toISOString());
  db.close();
  journal = await Journal.open(path);
  expect(await journal.run({ ...job, id: "interrupted" }, execute)).toMatchObject({ kind: "error", code: "outcome_unknown" });
  expect(executions).toBe(1);
  journal.close();
});

test("MCP loads all config formats, honors precedence and disabled servers, and calls a real stdio server", async () => {
  const home = await temp();
  await mkdir(join(home, ".codex"));
  await mkdir(join(home, ".config/opencode"), { recursive: true });
  await writeFile(join(home, ".codex/config.toml"), '[mcp_servers.duplicate]\ncommand = "missing"\nenabled = true\n[mcp_servers.codexOnly]\ncommand = "missing"\nenabled = false\n');
  await writeFile(join(home, ".claude.json"), JSON.stringify({ mcpServers: { duplicate: { command: "missing-claude" }, claudeOnly: { url: "https://example.com/mcp" } } }));
  await writeFile(join(home, ".config/opencode/opencode.json"), JSON.stringify({ mcp: { duplicate: { command: [process.execPath, resolve(import.meta.dir, "mcp-fixture.ts")], environment: { FIXTURE_SECRET: "fixture-secret-value" } }, disabled: { command: ["never-execute"], enabled: false } } }));
  const mcp = await McpRegistry.load(home);
  expect(mcp.warnings).toEqual([]);
  const inventory = JSON.stringify(await mcp.list());
  expect(inventory).toContain("codexOnly");
  expect(inventory).toContain("claudeOnly");
  expect(inventory).not.toContain("fixture-secret-value");
  expect(await mcp.list("disabled")).toMatchObject({ kind: "error", code: "mcp_disabled" });
  const listing = await mcp.list("duplicate");
  expect(JSON.stringify(listing)).toContain('"name":"echo"');
  expect(JSON.stringify(await mcp.call("duplicate", "echo", { value: "hello MCP" }))).toContain("hello MCP");
  expect(JSON.stringify(await mcp.call("duplicate", "echo", { value: "fixture-secret-value" }))).toContain("[REDACTED]");
  expect(mcp.sanitize({ kind: "success", data: "x".repeat(RUNNER_RESULT_LIMIT + 1) })).toMatchObject({ kind: "error", code: "output_limit" });
});
