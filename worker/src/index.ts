import { z } from "zod"
import { authenticate } from "./auth"
import { MAX_AUDIO_BYTES, parseLanguageCodes } from "./domain"
import { transcribeWav, TranscriptionError, TRANSCRIPTION_MODEL } from "./transcription"
import { TranscriptArchive, transcriptID, transcriptSchema } from "./transcripts"

export { TranscriptArchive }
export { TalktAgent, TelegramChannel } from "./retired-objects"

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const requestID = crypto.randomUUID()
    const url = new URL(request.url)
    try {
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true, service: "talkt-api", transcriptionModel: TRANSCRIPTION_MODEL, requestID })
      }
      const supported = url.pathname === "/v1/transcribe" || url.pathname === "/v1/transcripts" || url.pathname.startsWith("/v1/transcripts/")
      if (!supported) return json({ error: "Not found", requestID }, 404)
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
      if (request.method === "POST" && url.pathname === "/v1/transcribe") {
        if (request.headers.get("content-type")?.split(";")[0].trim() !== "audio/wav") {
          return json({ error: "Expected audio/wav", requestID }, 415)
        }
        const languages = parseLanguageCodes(url.searchParams.get("languages"))
        const audio = await readBody(request, MAX_AUDIO_BYTES)
        const transcript = await transcribeWav(audio, env.OPENROUTER_API_KEY, languages)
        return json({ transcript, requestID })
      }
      return json({ error: "Not found", requestID }, 404)
    } catch (error) {
      if (error instanceof z.ZodError || error instanceof SyntaxError) return json({ error: "Invalid request", requestID }, 400)
      if (error instanceof TranscriptionError) return json({ error: error.message, requestID }, error.status)
      console.error(JSON.stringify({ message: "request_failed", requestID, path: url.pathname, error: error instanceof Error ? error.name : "UnknownError" }))
      return json({ error: "Talkt could not finish. Try again.", requestID }, 500)
    }
  },
} satisfies ExportedHandler<Env>

async function readBody(request: Request, maximum: number): Promise<Uint8Array<ArrayBuffer>> {
  const reader = request.body?.getReader()
  if (!reader) throw new SyntaxError("Missing body")
  const chunks: Uint8Array[] = []
  let size = 0
  while (true) {
    const item = await reader.read()
    if (item.done) break
    size += item.value.byteLength
    if (size > maximum) {
      await reader.cancel()
      throw new TranscriptionError("Recording is too large", 413)
    }
    chunks.push(item.value)
  }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
  return bytes
}

async function readJson(request: Request, maximum: number): Promise<unknown> {
  return JSON.parse(new TextDecoder().decode(await readBody(request, maximum)))
}

function json(value: unknown, status = 200): Response {
  return Response.json(value, { status, headers: { "cache-control": "no-store" } })
}
