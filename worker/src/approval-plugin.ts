import type { Plugin } from "@opencode-ai/plugin"
import type { ToolApprovals } from "./tool-approvals"

export function approvalPlugin(approvals: ToolApprovals) {
  return {
    id: "talkt-personal-approvals",
    async setup(ctx) {
      await ctx.tool.hook("execute.before", async event => {
        if (["personal_exec", "personal_mcp_call", "native_google", "native_notion_write"].includes(event.tool)) {
          await approvals.require(event.sessionID, event.id, event.tool, event.input)
        }
      })
    },
  } satisfies Plugin.Plugin
}
