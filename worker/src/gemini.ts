import { MAX_AUDIO_BYTES } from "./domain"

type GeminiFile = {
  readonly name: string
  readonly uri: string
  readonly mimeType?: string
  readonly state?: string
}

type GeminiFileResponse = {
  readonly file?: GeminiFile
}

type GeminiInteractionResponse = {
  readonly output_text?: string
  readonly steps?: ReadonlyArray<{
    readonly type?: string
    readonly content?: ReadonlyArray<{
      readonly type?: string
      readonly text?: string
    }>
  }>
  readonly error?: { readonly message?: string }
}

const API_ROOT = "https://generativelanguage.googleapis.com"

export async function transcribeWav(
  audio: ArrayBuffer,
  apiKey: string,
  languageCodes: ReadonlyArray<string> = [],
): Promise<string> {
  if (audio.byteLength === 0 || audio.byteLength > MAX_AUDIO_BYTES) {
    throw new Error("Audio must be between 1 byte and 12 MiB")
  }

  const file = await uploadFile(audio, apiKey)
  try {
    const active = await waitUntilActive(file, apiKey)
    const response = await fetch(`${API_ROOT}/v1beta/interactions`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-goog-api-key": apiKey,
      },
      body: JSON.stringify({
        model: "gemini-3.5-transcribe",
        input: [
          {
            type: "audio",
            uri: active.uri,
            mime_type: active.mimeType ?? "audio/wav",
          },
        ],
        generation_config: {
          transcription_config: {
            language_codes: languageCodes,
            custom_vocabulary: ["Talkt", "OpenCode", "Cloudflare", "Codex", "OpenRouter"],
            mode: "smart",
          },
        },
        store: false,
      }),
    })
    const data = await readJson<GeminiInteractionResponse>(response)
    if (!response.ok) throw new Error(data.error?.message ?? `Gemini transcription failed (${response.status})`)
    const text = extractInteractionText(data)
    if (!text) throw new Error("Gemini returned an empty transcript")
    return text
  } finally {
    await deleteFile(file, apiKey)
  }
}

export function extractInteractionText(data: GeminiInteractionResponse): string {
  const sdkText = data.output_text?.trim()
  if (sdkText) return sdkText

  const modelOutput = data.steps?.findLast((step) => step.type === "model_output")
  return modelOutput?.content
    ?.filter((content) => content.type === "text" && content.text)
    .map((content) => content.text?.trim())
    .filter(Boolean)
    .join("\n")
    .trim() ?? ""
}

async function uploadFile(audio: ArrayBuffer, apiKey: string): Promise<GeminiFile> {
  const start = await fetch(`${API_ROOT}/upload/v1beta/files`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-goog-api-key": apiKey,
      "x-goog-upload-protocol": "resumable",
      "x-goog-upload-command": "start",
      "x-goog-upload-header-content-length": String(audio.byteLength),
      "x-goog-upload-header-content-type": "audio/wav",
    },
    body: JSON.stringify({ file: { display_name: `talkt-${crypto.randomUUID()}.wav` } }),
  })
  if (!start.ok) throw new Error(`Gemini file upload could not start (${start.status})`)
  const uploadUrl = start.headers.get("x-goog-upload-url")
  if (!uploadUrl) throw new Error("Gemini did not return an upload URL")

  const finalize = await fetch(uploadUrl, {
    method: "POST",
    headers: {
      "content-length": String(audio.byteLength),
      "x-goog-upload-offset": "0",
      "x-goog-upload-command": "upload, finalize",
    },
    body: audio,
  })
  const data = await readJson<GeminiFileResponse>(finalize)
  if (!finalize.ok || !data.file) throw new Error(`Gemini file upload failed (${finalize.status})`)
  return data.file
}

async function waitUntilActive(initial: GeminiFile, apiKey: string): Promise<GeminiFile> {
  let file = initial
  for (let attempt = 0; attempt < 12; attempt += 1) {
    if (!file.state || file.state === "ACTIVE") return file
    if (file.state === "FAILED") throw new Error("Gemini could not process the audio file")
    await scheduler.wait(250 * (attempt + 1))
    const response = await fetch(`${API_ROOT}/v1beta/${file.name}`, {
      headers: { "x-goog-api-key": apiKey },
    })
    const data = await readJson<GeminiFileResponse>(response)
    if (!response.ok || !data.file) throw new Error(`Gemini file status failed (${response.status})`)
    file = data.file
  }
  throw new Error("Gemini audio processing timed out")
}

async function deleteFile(file: GeminiFile, apiKey: string): Promise<void> {
  try {
    await fetch(`${API_ROOT}/v1beta/${file.name}`, {
      method: "DELETE",
      headers: { "x-goog-api-key": apiKey },
    })
  } catch (error) {
    console.warn(JSON.stringify({ level: "warn", message: "gemini_file_cleanup_failed", error: String(error) }))
  }
}

async function readJson<T>(response: Response): Promise<T> {
  return response.json<T>()
}
