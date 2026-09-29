import { afterEach, expect, it, vi } from "vitest"
import { transcribeWav, TRANSCRIPTION_MODEL } from "../src/transcription"

function wav() {
  const audio = new Uint8Array(76)
  audio.set(new TextEncoder().encode("RIFF"))
  audio.set(new TextEncoder().encode("WAVE"), 8)
  return audio
}
afterEach(() => vi.restoreAllMocks())
it("uses Grok STT with WAV audio and a single language hint", async () => {
  const upstream = vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({ text: " Hello world. " }))
  expect(await transcribeWav(wav(), "test-provider-key", ["en"])).toBe("Hello world.")
  const [url, options] = upstream.mock.calls[0]
  expect(url).toBe("https://openrouter.ai/api/v1/audio/transcriptions")
  expect(JSON.parse(String(options?.body))).toEqual({ model: TRANSCRIPTION_MODEL, input_audio: { data: Buffer.from(wav()).toString("base64"), format: "wav" }, language: "en" })
  expect(new Headers(options?.headers).get("authorization")).toBe("Bearer test-provider-key")
})
it("uses automatic language detection for legacy multiple language hints", async () => {
  const upstream = vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({ text: "Ciao hello" }))
  await transcribeWav(wav(), "test", ["it", "en"])
  expect(JSON.parse(String(upstream.mock.calls[0][1]?.body))).not.toHaveProperty("language")
})
it("does not leak provider errors or send malformed recordings", async () => {
  const upstream = vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({ error: { message: "secret-provider-details" } }, { status: 401 }))
  await expect(transcribeWav(wav(), "test")).rejects.toMatchObject({ status: 502, message: "Cloud transcription is unavailable. Try again shortly." })
  upstream.mockClear()
  await expect(transcribeWav(new Uint8Array(60), "test")).rejects.toMatchObject({ status: 400 })
  await expect(transcribeWav(wav(), "")).rejects.toMatchObject({ status: 503 })
  expect(upstream).not.toHaveBeenCalled()
})
it("distinguishes silence, malformed success, throttling and network failures", async () => {
  const upstream = vi.spyOn(globalThis, "fetch")
  upstream.mockResolvedValueOnce(Response.json({ text: " " }))
  expect(await transcribeWav(wav(), "test")).toBe("")
  upstream.mockResolvedValueOnce(Response.json({ error: "bad response" }))
  await expect(transcribeWav(wav(), "test")).rejects.toMatchObject({ status: 502 })
  upstream.mockResolvedValueOnce(new Response("busy", { status: 429 }))
  await expect(transcribeWav(wav(), "test")).rejects.toMatchObject({ status: 429 })
  upstream.mockRejectedValueOnce(new Error("timeout"))
  await expect(transcribeWav(wav(), "test")).rejects.toMatchObject({ status: 503 })
})
