import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js";
import { homedir } from "node:os";
import { join } from "node:path";
import { record, RUNNER_RESULT_LIMIT, type RunnerResult } from "../../shared/runner-protocol";
import { childEnvironment } from "./executor";

type ServerBase = { name: string; source: string; disabled: boolean };
type Server = ServerBase & (
  | { kind: "stdio"; command: string; args: string[]; env: Record<string, string>; cwd?: string }
  | { kind: "remote"; url: string; headers: Record<string, string>; transport: "http" | "sse" }
  | { kind: "unavailable"; reason: string }
);

function stringMap(value: unknown): Record<string, string> {
  if (value === undefined) return {};
  const result: Record<string, string> = {};
  for (const [key, entry] of Object.entries(record(value))) {
    if (typeof entry !== "string") throw new Error("Expected string configuration value");
    result[key] = entry;
  }
  return result;
}
function interpolate(value: string): string {
  return value.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\{env:([A-Za-z_][A-Za-z0-9_]*)\}/g, (_, first: string | undefined, second: string | undefined) => {
    const name = first ?? second ?? "";
    const replacement = process.env[name];
    if (replacement === undefined) throw new Error("A configured environment variable is missing");
    return replacement;
  });
}
function expandedMap(value: unknown): Record<string, string> {
  return Object.fromEntries(Object.entries(stringMap(value)).map(([key, entry]) => [key, interpolate(entry)]));
}
function serverFrom(name: string, source: string, value: unknown): Server {
  let disabled = false;
  try {
    const entry = record(value);
    disabled = entry.disabled === true || entry.enabled === false;
    const base = { name, source, disabled };
    if (typeof entry.url === "string") {
      const headers = expandedMap(entry.http_headers ?? entry.headers);
      for (const [key, envName] of Object.entries(stringMap(entry.env_http_headers))) {
        const envValue = process.env[envName];
        if (!envValue) throw new Error("A configured header environment variable is missing");
        headers[key] = envValue;
      }
      if (typeof entry.bearer_token_env_var === "string") {
        const token = process.env[entry.bearer_token_env_var];
        if (!token) throw new Error("The configured bearer token environment variable is missing");
        headers.Authorization = `Bearer ${token}`;
      }
      const url = new URL(interpolate(entry.url));
      if (url.protocol !== "https:" && !(url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname))) throw new Error("Remote MCP must use HTTPS or loopback HTTP");
      return { ...base, kind: "remote", url: url.toString(), headers, transport: entry.type === "sse" ? "sse" : "http" };
    }
    const argv: unknown = Array.isArray(entry.command) ? entry.command : [entry.command, ...(Array.isArray(entry.args) ? entry.args : [])];
    if (!Array.isArray(argv) || argv.length === 0 || argv.some(arg => typeof arg !== "string")) throw new Error("Invalid command");
    const strings = argv as string[];
    const command = strings[0];
    if (!command) throw new Error("No command");
    return { ...base, kind: "stdio", command: interpolate(command), args: strings.slice(1).map(interpolate), env: expandedMap(entry.environment ?? entry.env), ...(typeof entry.cwd === "string" ? { cwd: interpolate(entry.cwd) } : {}) };
  } catch {
    return { name, source, disabled, kind: "unavailable", reason: "Unsupported configuration or missing local environment credentials" };
  }
}

export class McpRegistry {
  private readonly secrets = new Set<string>();
  private constructor(private readonly servers: Map<string, Server>, readonly warnings: string[]) {
    for (const [key, value] of Object.entries(process.env)) {
      if (/token|secret|password|api_?key/i.test(key) && value && value.length >= 8) this.secrets.add(value);
    }
    for (const server of servers.values()) {
      const values = server.kind === "remote" ? Object.values(server.headers) : server.kind === "stdio" ? Object.values(server.env) : [];
      for (const value of values) if (value.length >= 8) {
        this.secrets.add(value);
        if (value.startsWith("Bearer ")) this.secrets.add(value.slice(7));
      }
    }
  }

  static async load(home = homedir()) {
    const servers = new Map<string, Server>();
    const warnings: string[] = [];
    const sources = [
      { path: join(home, ".codex/config.toml"), key: "mcp_servers", format: "toml" },
      { path: join(home, ".claude.json"), key: "mcpServers", format: "json" },
      { path: join(home, ".config/opencode/opencode.json"), key: "mcp", format: "json" },
    ];
    for (const source of sources) {
      const file = Bun.file(source.path);
      if (!(await file.exists())) continue;
      try {
        const text = await file.text();
        const config = record(source.format === "toml" ? Bun.TOML.parse(text) : JSON.parse(text));
        if (config[source.key] === undefined) continue;
        for (const [name, value] of Object.entries(record(config[source.key]))) servers.set(name, serverFrom(name, source.path, value));
      } catch { warnings.push(`Could not parse MCP configuration at ${source.path}`); }
    }
    return new McpRegistry(servers, warnings);
  }

  addSecret(value: string) { if (value.length >= 8) this.secrets.add(value); }

  sanitize(result: RunnerResult): RunnerResult {
    let serialized = JSON.stringify(result);
    for (const secret of this.secrets) serialized = serialized.replaceAll(JSON.stringify(secret).slice(1, -1), "[REDACTED]");
    if (new TextEncoder().encode(serialized).byteLength > RUNNER_RESULT_LIMIT) return { kind: "error", code: "output_limit", message: "Tool output exceeded 64 KiB. Request fewer results or a narrower query." };
    return JSON.parse(serialized) as RunnerResult;
  }

  async list(name?: string): Promise<RunnerResult> {
    if (!name) return { kind: "success", data: { servers: [...this.servers.values()].map(server => ({ name: server.name, kind: server.kind, source: server.source, disabled: server.disabled, ...(server.kind === "unavailable" ? { reason: server.reason } : {}) })), warnings: this.warnings } };
    return this.withClient(name, async client => {
      const tools: unknown[] = [];
      let cursor: string | undefined;
      for (let page = 0; page < 20; page++) {
        const result = await client.listTools(cursor ? { cursor } : {}, { timeout: 30_000 });
        tools.push(...result.tools);
        cursor = result.nextCursor;
        if (!cursor) return { tools };
      }
      return { tools, truncated: true, reason: "Tool listing exceeded 20 pages" };
    });
  }

  async call(name: string, tool: string, args: Record<string, unknown>): Promise<RunnerResult> {
    return this.withClient(name, client => client.callTool({ name: tool, arguments: args }, undefined, { timeout: 90_000 }));
  }

  private async withClient(name: string, operation: (client: Client) => Promise<unknown>): Promise<RunnerResult> {
    const server = this.servers.get(name);
    if (!server) return { kind: "error", code: "mcp_not_found", message: "MCP server is not in the local runner configuration" };
    if (server.disabled) return { kind: "error", code: "mcp_disabled", message: "MCP server is disabled in its local configuration" };
    if (server.kind === "unavailable") return { kind: "error", code: "mcp_unavailable", message: server.reason };
    const client = new Client({ name: "talkt-personal-runner", version: "1.0.0" });
    const transport = server.kind === "stdio"
      ? new StdioClientTransport({ command: server.command, args: server.args, env: { ...childEnvironment(), ...server.env }, stderr: "ignore", ...(server.cwd ? { cwd: server.cwd } : {}) })
      : server.transport === "sse"
        ? new SSEClientTransport(new URL(server.url), { requestInit: { headers: server.headers } })
        : new StreamableHTTPClientTransport(new URL(server.url), { requestInit: { headers: server.headers } });
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const data = await Promise.race([
        (async () => { await client.connect(transport); return operation(client); })(),
        new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error("MCP deadline exceeded")), 110_000); }),
      ]);
      return this.sanitize({ kind: "success", data });
    } catch {
      return { kind: "error", code: "mcp_failed", message: "MCP connection or tool call failed. Check local installation, credentials and provider access. Runtime-owned Codex tools require a standalone replacement." };
    } finally {
      if (timer) clearTimeout(timer);
      await client.close().catch(() => undefined);
      await transport.close().catch(() => undefined);
    }
  }
}
