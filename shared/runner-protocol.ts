export const RUNNER_OUTPUT_LIMIT = 24_576;
export const RUNNER_RESULT_LIMIT = 65_536;
export const RUNNER_MAX_TIMEOUT_MS = 120_000;

export type RunnerRequest =
  | { kind: "exec"; argv: string[]; cwd: string; timeoutMs?: number }
  | { kind: "read_file"; path: string }
  | { kind: "mcp_list"; server?: string }
  | { kind: "mcp_call"; server: string; tool: string; arguments: Record<string, unknown> };
export type RunnerResult =
  | { kind: "success"; data: unknown }
  | { kind: "error"; code: string; message: string };
export type RunnerJob = { id: string; leaseToken: string; request: RunnerRequest };
export type RunnerPollResponse = { job: RunnerJob | null };
export type RunnerSubmission = { runnerID: string; id: string; leaseToken: string; result: RunnerResult };

export function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("Expected object");
  return value as Record<string, unknown>;
}
export function nonempty(value: unknown, name: string, max = 4096): string {
  if (typeof value !== "string" || !value.trim() || value.length > max || value.includes("\0")) throw new Error(`Invalid ${name}`);
  return value;
}
export function validateRunnerRequest(value: unknown): RunnerRequest {
  const data = record(value);
  switch (data.kind) {
    case "exec": {
      if (!Array.isArray(data.argv) || data.argv.length === 0 || data.argv.length > 128 || data.argv.some(arg => typeof arg !== "string" || arg.includes("\0") || arg.length > 16384)) throw new Error("Invalid argv");
      const argv = data.argv as string[];
      nonempty(argv[0], "executable");
      const cwd = nonempty(data.cwd, "cwd");
      if (data.timeoutMs === undefined) return { kind: "exec", argv, cwd };
      if (typeof data.timeoutMs !== "number" || !Number.isInteger(data.timeoutMs) || data.timeoutMs < 1 || data.timeoutMs > RUNNER_MAX_TIMEOUT_MS) throw new Error("Invalid timeoutMs");
      return { kind: "exec", argv, cwd, timeoutMs: data.timeoutMs };
    }
    case "read_file": return { kind: "read_file", path: nonempty(data.path, "path") };
    case "mcp_list": return data.server === undefined ? { kind: "mcp_list" } : { kind: "mcp_list", server: nonempty(data.server, "server", 200) };
    case "mcp_call": return { kind: "mcp_call", server: nonempty(data.server, "server", 200), tool: nonempty(data.tool, "tool", 200), arguments: record(data.arguments) };
    default: throw new Error("Unknown runner request kind");
  }
}
export function validateRunnerResult(value: unknown): RunnerResult {
  const data = record(value);
  if (new TextEncoder().encode(JSON.stringify(value)).byteLength > RUNNER_RESULT_LIMIT) throw new Error("Result too large");
  if (data.kind === "success" && "data" in data) return { kind: "success", data: data.data };
  if (data.kind === "error") return { kind: "error", code: nonempty(data.code, "code", 100), message: nonempty(data.message, "message", 8192) };
  throw new Error("Invalid runner result");
}
export function validateRunnerJob(value: unknown): RunnerJob {
  const data = record(value);
  return { id: nonempty(data.id, "id", 200), leaseToken: nonempty(data.leaseToken, "leaseToken", 500), request: validateRunnerRequest(data.request) };
}
