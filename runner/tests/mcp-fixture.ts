import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
const server = new McpServer({ name: "talkt-test", version: "1.0.0" });
server.registerTool("echo", { inputSchema: { value: z.string() } }, async ({ value }) => ({ content: [{ type: "text", text: value }] }));
if (process.env.FIXTURE_LARGE_CATALOG === "1") {
  for (let index = 0; index < 40; index++) server.registerTool(`large_${index}`, { description: "Detailed provider tool. ".repeat(150), inputSchema: { value: z.string().describe("Full input schema") } }, async ({ value }) => ({ content: [{ type: "text", text: value }] }));
}
await server.connect(new StdioServerTransport());
