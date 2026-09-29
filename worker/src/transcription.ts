import { Buffer } from "node:buffer"
import { z } from "zod"
import { MAX_AUDIO_BYTES } from "./domain"

const transcriptResponse = z.object({ text: z.string() })
export const TRANSCRIPTION_MODEL = "x-ai/grok-stt-1.0"

export class TranscriptionError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

export async function transcribeWav(audio: Uint8Array, apiKey: string, languages: readonly string[] = []): Promise<string> {
  if (audio.byteLength <= 44 || audio.byteLength > MAX_AUDIO_BYTES ||
      new TextDecoder().decode(audio.subarray(0, 4)) !== "RIFF" ||
      new TextDecoder().decode(audio.subarray(8, 12)) !== "WAVE") {
    throw new TranscriptionError("Send a valid WAV recording", 400)
  }
  if (!apiKey) throw new TranscriptionError("Cloud transcription is not configured", 503)
  let response: Response
  try {
    response = await fetch("https://openrouter.ai/api/v1/audio/transcriptions", {
      method: "POST",
      headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json", "X-OpenRouter-Title": "Talkt" },
      body: JSON.stringify({
        model: TRANSCRIPTION_MODEL,
        input_audio: { data: Buffer.from(audio).toString("base64"), format: "wav" },
        ...(languages.length === 1 ? { language: languages[0] } : {}),
      }),
      signal: AbortSignal.timeout(65_000),
    })
  } catch {
    throw new TranscriptionError("Cloud transcription could not connect. Your recording can be retried.", 503)
  }
  if (!response.ok) {
    console.warn(JSON.stringify({ message: "transcription_provider_failed", status: response.status, generationID: response.headers.get("x-generation-id") }))
    throw new TranscriptionError(response.status === 429 ? "Transcription is busy. Try again shortly." : "Cloud transcription is unavailable. Try again shortly.", response.status === 429 ? 429 : 502)
  }
  const data = transcriptResponse.safeParse(await response.json().catch(() => null))
  if (!data.success) throw new TranscriptionError("Cloud transcription returned an invalid response. Try again.", 502)
  return data.data.text.trim()
}
