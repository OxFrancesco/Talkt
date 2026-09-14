import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { z } from "zod"
import { mkdir, readFile, readdir, stat, unlink, writeFile } from "node:fs/promises"
import { dirname, join } from "node:path"
import { CHATGPT_RESPONSES_URL, encrypt, openRequest, readBounded } from "../../shared/chatgpt-relay"

const requestSchema = z.object({
  url: z.literal(CHATGPT_RESPONSES_URL),
  method: z.literal("POST"),
  headers: z.record(z.string(), z.string()),
  body: z.string().max(4_000_000),
}).strict()

export async function relayRequest(value: unknown, privateKey: CryptoKey, fetcher: (url: string, init: RequestInit) => Promise<Response> = fetch) {
  const envelope = z.object({ iv: z.string().max(24), payload: z.string().max(220_000), wrappedKey: z.string().max(400) }).strict().parse(value)
  const opened = await openRequest(envelope, privateKey)
  const request = requestSchema.parse(JSON.parse(opened.value))
  const source = new Headers(request.headers)
  if (!source.get("authorization")?.startsWith("Bearer ")) throw new Error("ChatGPT authorization is missing")
  const headers = new Headers()
  for (const name of ["authorization", "content-type", "chatgpt-account-id", "originator", "session-id", "user-agent", "openai-beta"]) {
    const value = source.get(name)
    if (value) headers.set(name, value)
  }
  const response = await fetcher(CHATGPT_RESPONSES_URL, { method: "POST", headers, body: request.body, redirect: "error", signal: AbortSignal.timeout(80_000) })
  const body = new TextDecoder().decode(await readBounded(response.body, 4_000_000))
  return encrypt(JSON.stringify({ status: response.status, contentType: response.headers.get("content-type") ?? "text/event-stream", body }), opened.key, "response", 1_000_000)
}

if (import.meta.main) {
  const path = process.env.TALKT_CHATGPT_RELAY_KEY_FILE
  if (!path || (await stat(path)).mode & 0o077) throw new Error("Set a private TALKT_CHATGPT_RELAY_KEY_FILE")
  const privateKey = await crypto.subtle.importKey("pkcs8", Uint8Array.from(await readFile(path)), { name: "RSA-OAEP", hash: "SHA-256" }, false, ["decrypt"])
  const cache = join(dirname(path), "chatgpt-relay-cache")
  await mkdir(cache, { recursive: true, mode: 0o700 })
  const chunkSize = 56_000
  const ttl = 600_000
  for (const name of await readdir(cache)) {
    if (/^[a-f0-9-]{36}$/.test(name) && Date.now() - (await stat(join(cache, name))).mtimeMs > ttl) await unlink(join(cache, name))
  }
  const server = new McpServer({ name: "talkt-chatgpt-relay", version: "1.0.0" })
  server.registerTool("request", {
    description: "Encrypted transport for Talkt ChatGPT model requests. This tool accepts only packets encrypted by the Talkt runtime.",
    inputSchema: { iv: z.string().max(24), payload: z.string().max(220_000), wrappedKey: z.string().max(400) },
  }, async envelope => {
    try {
      const response = await relayRequest(envelope, privateKey)
      const responseID = crypto.randomUUID()
      await writeFile(join(cache, responseID), response.payload, { mode: 0o600, flag: "wx" })
      return { content: [{ type: "text", text: JSON.stringify({ iv: response.iv, payload: response.payload.slice(0, chunkSize), responseID, chunks: Math.ceil(response.payload.length / chunkSize) }) }] }
    }
    catch { return { isError: true, content: [{ type: "text", text: "ChatGPT relay failed. Check the Mac connection or request size." }] } }
  })
  server.registerTool("read", {
    description: "Read one encrypted response segment from a completed ChatGPT relay request.",
    inputSchema: { responseID: z.string().uuid(), index: z.number().int().min(1).max(20) },
  }, async ({ responseID, index }) => {
    const path = join(cache, responseID)
    if (Date.now() - (await stat(path)).mtimeMs > ttl) throw new Error("ChatGPT relay response expired")
    const payload = await readFile(path, "utf8")
    if (index * chunkSize >= payload.length) throw new Error("Invalid response segment")
    return { content: [{ type: "text", text: JSON.stringify({ payload: payload.slice(index * chunkSize, (index + 1) * chunkSize) }) }] }
  })
  await server.connect(new StdioServerTransport())
}
