import type { Plugin } from "@opencode-ai/plugin"
import { z } from "zod"
import type { RunnerRequest, RunnerResult } from "../../shared/runner-protocol"

export function runnerPlugin(execute: (id: string, request: RunnerRequest) => Promise<RunnerResult>) {
  return {
    id: "talkt-mac-runner",
    async setup(ctx) {
      await ctx.tool.transform((editor) => {
        editor.add({
          name: "personal_exec",
          description: "Run installed CLI tools on Francesco's Mac. Use argv without shell interpolation and an explicit project directory. Read project AGENTS.md before modifying that project. macOS, Helium, Google gog and GitHub gh credentials stay on the Mac.",
          input: z.object({ argv: z.array(z.string()).min(1).max(128), cwd: z.string().min(1), timeoutMs: z.number().int().min(1).max(120_000).optional() }),
          options: { permission: "personal_exec" },
          execute: async (input, context) => ({ content: JSON.stringify(await execute(`${context.sessionID}:${context.id}`, { kind: "exec", ...input })) }),
        })
        editor.add({
          name: "personal_read_file",
          description: "Read a file from the allowed Mac project or skills directories, including project AGENTS.md and skill support files.",
          input: z.object({ path: z.string().min(1) }),
          execute: async (input, context) => ({ content: JSON.stringify(await execute(`${context.sessionID}:${context.id}`, { kind: "read_file", ...input })) }),
        })
        editor.add({
          name: "personal_mcp_list",
          description: "Discover the actual MCP servers and tools configured on Francesco's Mac. Disabled servers stay disabled. Pass a server name to list its callable tools.",
          input: z.object({ server: z.string().optional() }),
          execute: async (input, context) => ({ content: JSON.stringify(await execute(`${context.sessionID}:${context.id}`, { kind: "mcp_list", ...input })) }),
        })
        editor.add({
          name: "personal_mcp_call",
          description: "Call a discovered native MCP tool through Francesco's Mac. Check its schema with personal_mcp_list first.",
          input: z.object({ server: z.string().min(1), tool: z.string().min(1), arguments: z.record(z.string(), z.unknown()) }),
          options: { permission: "personal_mcp_call" },
          execute: async (input, context) => ({ content: JSON.stringify(await execute(`${context.sessionID}:${context.id}`, { kind: "mcp_call", ...input })) }),
        })
      })
      await ctx.permission.hook("evaluate", (request) => {
        if (["personal_exec", "personal_mcp_call"].includes(request.action) && request.effect !== "deny") request.effect = "ask"
      })
    },
  } satisfies Plugin.Plugin
}
