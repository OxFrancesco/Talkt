import type { Plugin } from "pigent"
import { z } from "zod"
import { CHATGPT_RESPONSES_URL, decrypt, sealRequest } from "../../shared/chatgpt-relay"

const mcpResult = z.object({ isError: z.boolean().optional(), content: z.array(z.object({ type: z.string(), text: z.string().optional() })) })
const envelopeSchema = z.object({ iv: z.string().max(24), payload: z.string().max(56_000), responseID: z.string().uuid(), chunks: z.number().int().min(1).max(20) })
const responseSchema = z.object({ status: z.number().int().min(200).max(599), contentType: z.string(), body: z.string() })

export function chatGPTRelayPlugin(options: {
  publicKey: string
  execute: (id: string, tool: "request" | "read", arguments_: Record<string, unknown>) => Promise<unknown>
  online: () => Promise<boolean>
}) {
  return {
    id: "talkt-chatgpt-relay",
    async setup(ctx) {
      await ctx.session.hook("http.response", async event => {
        if (event.model.providerID !== "openai" || event.request.url !== CHATGPT_RESPONSES_URL || event.response.status !== 403 || !event.response.headers.get("content-type")?.includes("text/html")) return
        if (!await options.online()) throw new Error("ChatGPT requests need the Mac runner online")
        await event.response.body?.cancel()
        const headers: Record<string, string> = {}
        event.request.headers.forEach((value, key) => { headers[key] = value })
        const request = { url: event.request.url, method: event.request.method, headers, body: await event.request.clone().text() }
        const sealed = await sealRequest(JSON.stringify(request), options.publicKey)
        const call = async (tool: "request" | "read", arguments_: Record<string, unknown>): Promise<unknown> => {
          const result = z.discriminatedUnion("kind", [z.object({ kind: z.literal("success"), data: z.unknown() }), z.object({ kind: z.literal("error"), message: z.string() })]).parse(await options.execute(`chatgpt_${crypto.randomUUID()}`, tool, arguments_))
          if (result.kind === "error") throw new Error(result.message)
          const mcp = mcpResult.parse(result.data)
          const packet = mcp.content.find(part => part.type === "text")?.text
          if (mcp.isError || !packet) throw new Error("ChatGPT relay could not complete the model request")
          return JSON.parse(packet)
        }
        const envelope = envelopeSchema.parse(await call("request", sealed.envelope))
        let payload = envelope.payload
        for (let index = 1; index < envelope.chunks; index++) {
          payload += z.object({ payload: z.string().max(56_000) }).parse(await call("read", { responseID: envelope.responseID, index })).payload
        }
        const response = responseSchema.parse(JSON.parse(await decrypt({ iv: envelope.iv, payload }, sealed.key, "response")))
        event.response = new Response(response.body, { status: response.status, headers: { "content-type": response.contentType } })
        console.info(JSON.stringify({ message: "chatgpt_relay_response", status: response.status, model: event.model }))
      })
    },
  } satisfies Plugin.Plugin
}
