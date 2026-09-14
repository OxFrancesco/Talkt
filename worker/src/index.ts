import { z } from "zod"
import { authenticate } from "./auth"
import { TalktAgent } from "./agent"
import { MAX_AUDIO_BYTES, MAX_PROMPT_BYTES, parseLanguageCodes, secureEqual } from "./domain"
import { transcribeWav } from "./gemini"
import { TelegramChannel } from "./telegram-channel"
import { telegramUpdateSchema } from "./telegram"
import { record, nonempty, validateRunnerResult } from "pigent/runner"

import { TranscriptArchive, transcriptID, transcriptSchema } from "./transcripts"

export { TalktAgent, TelegramChannel, TranscriptArchive }

const promptSchema = z.object({ text: z.string().trim().min(1).max(32_000), sessionID: z.string().min(1).max(200).optional(), id: z.string().min(1).max(200).optional() })
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

      if (request.method === "POST" && url.pathname === "/v1/telegram/webhook") {
        if (!env.TELEGRAM_WEBHOOK_SECRET || !env.TELEGRAM_BOT_TOKEN || !env.TELEGRAM_OWNER_ID) return json({ error: "Telegram is not configured" }, 503)
        if (!(await secureEqual(request.headers.get("x-telegram-bot-api-secret-token") ?? "", env.TELEGRAM_WEBHOOK_SECRET))) return json({ error: "Unauthorized" }, 401)
        const parsed = telegramUpdateSchema.safeParse(await readJson(request, 64_000))
        if (!parsed.success) return json({ error: "Invalid Telegram update" }, 400)
        return json(await env.TALKT_TELEGRAM.getByName("owner").receive(parsed.data))
      }

      if (url.pathname.startsWith("/v1/runner/")) {
        if (!env.TALKT_RUNNER_TOKEN || !(await bearer(request, env.TALKT_RUNNER_TOKEN))) return json({ error: "Unauthorized" }, 401)
        const agent = env.TALKT_AGENT.getByName("owner")
        if (request.method === "POST" && url.pathname === "/v1/runner/poll") {
          const input = z.object({ runnerID: z.string().min(1).max(200) }).parse(await readJson(request, 4096))
          return json(await agent.runnerPoll(input.runnerID))
        }
        if (request.method === "POST" && url.pathname === "/v1/runner/result") {
          const body = record(await readJson(request, 80_000))
          const input = { runnerID: nonempty(body.runnerID, "runnerID", 200), id: nonempty(body.id, "id", 200), leaseToken: nonempty(body.leaseToken, "leaseToken", 500), result: validateRunnerResult(body.result) }
          try { await agent.runnerResult(input) } catch { return json({ error: "Runner lease does not match" }, 409) }
          return json({ accepted: true })
        }
        return json({ error: "Not found" }, 404)
      }

      const identity = await authenticate(request, env)
      if (!identity) return json({ error: "Sign in to Talkt to continue", requestID }, 401)
      if (url.pathname === "/v1/transcripts" || url.pathname.startsWith("/v1/transcripts/")) {
        const archive = env.TRANSCRIPT_ARCHIVE.getByName(identity)
        if (url.pathname === "/v1/transcripts" && request.method === "GET") {
          const query = z.string().max(500).parse(url.searchParams.get("q") ?? "")
          const offset = z.coerce.number().int().min(0).max(100_000).parse(url.searchParams.get("offset") ?? 0)
          return json(await archive.search(query, offset))
        }
        const id = transcriptID.parse(url.pathname.slice("/v1/transcripts/".length))
        if (request.method === "PUT") {
          const entry = transcriptSchema.parse(await readJson(request, 1_600_000))
          if (entry.id !== id) return json({ error: "Transcript ID does not match" }, 400)
          const result = await archive.put(entry)
          return json(result.status === 410 ? { error: "Transcript was deleted" } : result.status === 409
            ? { error: "Transcript ID already exists with different content" } : { saved: result.saved }, result.status)
        }
        if (request.method === "GET") {
          const entry = await archive.get(id)
          return entry ? json(entry) : json({ error: "Transcript unavailable" }, 404)
        }
        if (request.method === "DELETE") {
          const result = await archive.remove(id)
          return json(result, result.pending ? 202 : 200)
        }
        return json({ error: "Not found" }, 404)
      }
      const [, subject] = JSON.parse(identity) as [string, string]
      const personal = Boolean(env.PERSONAL_OWNER_SUB) && subject === env.PERSONAL_OWNER_SUB
      const agent = env.TALKT_AGENT.getByName(personal ? "owner" : `clerk:${identity}`)

      if (request.method === "GET" && url.pathname === "/v1/agent/capabilities") return json(await agent.capabilities())
      if (request.method === "GET" && url.pathname === "/v1/agent/permissions") return json(await agent.permissionMode())
      if (request.method === "POST" && url.pathname === "/v1/agent/permissions") {
        if (!personal) return json({ error: "Personal owner required" }, 403)
        const input = z.object({ yolo: z.boolean() }).strict().parse(await readJson(request, 1024))
        return json(await agent.setYolo(input.yolo))
      }
      if (request.method === "GET" && url.pathname === "/v1/connections") return json(await agent.providerStatus())
      if (request.method === "POST" && url.pathname === "/v1/connections/chatgpt") return json(await agent.beginChatGPT())
      if (request.method === "GET" && url.pathname === "/v1/connections/chatgpt") return json(await agent.chatGPTStatus())
      if (request.method === "POST" && url.pathname === "/v1/connections/select") {
        const input = z.object({ provider: z.enum(["chatgpt", "openrouter"]) }).strict().parse(await readJson(request, 1024))
        return json(await agent.useProvider(input.provider))
      }
      if (request.method === "GET" && url.pathname === "/v1/conversations") return json(await agent.conversations())
      if (request.method === "POST" && url.pathname === "/v1/conversations") {
        const input = z.object({ title: z.string().max(200).optional() }).parse(await readJson(request, 4096))
        return json(await agent.createConversation(input.title))
      }
      if (request.method === "POST" && url.pathname === "/v1/conversations/select") {
        const input = z.object({ sessionID: z.string().min(1).max(200) }).parse(await readJson(request, 4096))
        await agent.selectConversation(input.sessionID)
        return json({ sessionID: input.sessionID })
      }
      if (request.method === "POST" && url.pathname === "/v1/conversations/stop") {
        const input = z.object({ sessionID: z.string().min(1).max(200).optional() }).parse(await readJson(request, 4096))
        await agent.stopConversation(input.sessionID)
        return json({ stopped: true })
      }
      if (request.method === "GET" && url.pathname === "/v1/conversations/context") {
        const sessionID = z.string().min(1).max(200).parse(url.searchParams.get("sessionID"))
        return new Response(await agent.conversationContext(sessionID), { headers: { "content-type": "application/json", "cache-control": "no-store" } })
      }
      if (request.method === "POST" && url.pathname === "/v1/runs") {
        const input = promptSchema.parse(await readJson(request, MAX_PROMPT_BYTES))
        return json(await agent.startRun(input.text, "app", input.sessionID, input.id), 202)
      }
      if (request.method === "GET" && url.pathname.startsWith("/v1/runs/")) return json(await agent.runStatus(decodeURIComponent(url.pathname.slice(9))))
      if (request.method === "GET" && url.pathname === "/v1/permissions") {
        const sessionID = z.string().min(1).max(200).parse(url.searchParams.get("sessionID"))
        return json(await agent.permissions(sessionID))
      }
      if (request.method === "POST" && url.pathname === "/v1/permissions/reply") {
        const input = z.object({ sessionID: z.string().min(1), requestID: z.string().min(1), reply: z.enum(["once", "always", "reject"]) }).parse(await readJson(request, 4096))
        await agent.replyPermission(input.sessionID, input.requestID, input.reply)
        return json({ accepted: true })
      }

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
        const result = await agent.prompt(parsed.data.text, parsed.data.sessionID)
        return json({ ...result, requestID }, result.pending ? 202 : 200)
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
      if (error instanceof z.ZodError || error instanceof SyntaxError) return json({ error: "Invalid request", requestID }, 400)
      const message = error instanceof Error ? error.message : "Unknown error"
      console.error(JSON.stringify({
        level: "error",
        message: "request_failed",
        requestID,
        path: url.pathname,
        durationMs: Date.now() - startedAt,
        error: message,
      }))
      return json({ error: message.includes("busy") ? message : "Request failed", requestID }, message.includes("busy") ? 409 : 500)
    }
  },
} satisfies ExportedHandler<Env>

async function bearer(request: Request, token: string): Promise<boolean> {
  const header = request.headers.get("authorization")
  if (!token || !header?.startsWith("Bearer ")) return false
  return secureEqual(header.slice(7), token)
}

async function readJson(request: Request, maximum: number): Promise<unknown> {
  const reader = request.body?.getReader()
  if (!reader) throw new SyntaxError("Missing body")
  let size = 0
  const chunks: Uint8Array[] = []
  while (true) {
    const item = await reader.read()
    if (item.done) break
    size += item.value.byteLength
    if (size > maximum) { await reader.cancel(); throw new SyntaxError("Body too large") }
    chunks.push(item.value)
  }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
  return JSON.parse(new TextDecoder().decode(bytes))
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
