import { z } from "zod"
import { TalktAgent } from "./agent"
import { MAX_AUDIO_BYTES, MAX_PROMPT_BYTES, parseLanguageCodes, secureEqual } from "./domain"
import { transcribeWav } from "./gemini"

export { TalktAgent }

const promptSchema = z.object({ text: z.string().trim().min(1).max(32_000) })
const keySchema = z.object({ key: z.string().trim().min(1).max(512) })

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const requestID = crypto.randomUUID()
    const url = new URL(request.url)
    const startedAt = Date.now()

    try {
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true, service: "talkt-api", environment: env.ENVIRONMENT, requestID })
      }

      if (!(await authorized(request, env))) return json({ error: "Unauthorized", requestID }, 401)
      const agent = env.TALKT_AGENT.getByName("owner")

      if (request.method === "POST" && url.pathname === "/v1/agent/openrouter/key") {
        if (!bounded(request, MAX_PROMPT_BYTES)) return json({ error: "Body is too large", requestID }, 413)
        const parsed = keySchema.safeParse(await request.json())
        if (!parsed.success) return json({ error: "Invalid key", requestID }, 400)
        return json({ ...(await agent.connectOpenRouter(parsed.data.key)), requestID })
      }

      if (request.method === "GET" && url.pathname === "/v1/agent/status") {
        const [host, connection] = await Promise.all([agent.health(), agent.connectionStatus()])
        return json({ host, connection, requestID })
      }

      if (request.method === "POST" && url.pathname === "/v1/agent/prompt") {
        if (!bounded(request, MAX_PROMPT_BYTES)) return json({ error: "Prompt body is too large", requestID }, 413)
        const parsed = promptSchema.safeParse(await request.json())
        if (!parsed.success) return json({ error: "Invalid prompt", requestID }, 400)
        return json({ ...(await agent.prompt(parsed.data.text)), requestID })
      }

      if (request.method === "POST" && url.pathname === "/v1/transcribe") {
        if (request.headers.get("content-type")?.split(";")[0] !== "audio/wav") {
          return json({ error: "Expected audio/wav", requestID }, 415)
        }
        if (!bounded(request, MAX_AUDIO_BYTES)) return json({ error: "Audio body is too large", requestID }, 413)
        const languageCodes = parseLanguageCodes(url.searchParams.get("languages"))
        const transcript = await transcribeWav(await request.arrayBuffer(), env.GEMINI_API_KEY, languageCodes)
        return json({ transcript, requestID })
      }

      return json({ error: "Not found", requestID }, 404)
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unknown error"
      console.error(JSON.stringify({
        level: "error",
        message: "request_failed",
        requestID,
        path: url.pathname,
        durationMs: Date.now() - startedAt,
        error: message,
      }))
      return json({ error: message, requestID }, 500)
    }
  },
} satisfies ExportedHandler<Env>

async function authorized(request: Request, env: Env): Promise<boolean> {
  const header = request.headers.get("authorization")
  if (!header?.startsWith("Bearer ")) return false
  return secureEqual(header.slice(7), env.TALKT_APP_TOKEN)
}

function bounded(request: Request, limit: number): boolean {
  const raw = request.headers.get("content-length")
  if (!raw) return false
  const length = Number(raw)
  return Number.isSafeInteger(length) && length > 0 && length <= limit
}

function json(value: unknown, status = 200): Response {
  return Response.json(value, {
    status,
    headers: {
      "cache-control": "no-store",
      "content-type": "application/json; charset=utf-8",
    },
  })
}
