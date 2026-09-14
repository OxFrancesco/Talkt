import { Buffer } from "node:buffer"

export const CHATGPT_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"
export type Envelope = { iv: string; payload: string }
export type RequestEnvelope = Envelope & { wrappedKey: string }

const bytes = (value: string) => Uint8Array.from(Buffer.from(value, "base64"))
const base64 = (value: ArrayBuffer | Uint8Array) => Buffer.from(value instanceof Uint8Array ? value : new Uint8Array(value)).toString("base64")

export async function readBounded(stream: ReadableStream<Uint8Array> | null, limit: number) {
  if (!stream) return new Uint8Array()
  const reader = stream.getReader()
  const chunks: Uint8Array[] = []
  let size = 0
  while (true) {
    const part = await reader.read()
    if (part.done) break
    size += part.value.byteLength
    if (size > limit) { await reader.cancel(); throw new Error("ChatGPT relay data exceeds its size limit") }
    chunks.push(part.value)
  }
  return Uint8Array.from(Buffer.concat(chunks))
}

export async function encrypt(value: string, key: CryptoKey, direction: "request" | "response", limit: number): Promise<Envelope> {
  const input = new TextEncoder().encode(value)
  if (input.byteLength > 4_000_000) throw new Error("ChatGPT relay data exceeds its size limit")
  const compressed = await readBounded(new Blob([input]).stream().pipeThrough(new CompressionStream("gzip")), limit)
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: new TextEncoder().encode(`talkt-chatgpt-${direction}-v1`) }, key, compressed)
  const envelope = { iv: base64(iv), payload: base64(encrypted) }
  if (JSON.stringify(envelope).length > limit) throw new Error("ChatGPT relay data exceeds its size limit")
  return envelope
}

export async function decrypt(envelope: Envelope, key: CryptoKey, direction: "request" | "response") {
  const compressed = await crypto.subtle.decrypt({ name: "AES-GCM", iv: bytes(envelope.iv), additionalData: new TextEncoder().encode(`talkt-chatgpt-${direction}-v1`) }, key, bytes(envelope.payload))
  const plain = await readBounded(new Blob([compressed]).stream().pipeThrough(new DecompressionStream("gzip")), 4_000_000)
  return new TextDecoder().decode(plain)
}

export async function sealRequest(value: string, publicKey: string) {
  const rsa = await crypto.subtle.importKey("spki", bytes(publicKey), { name: "RSA-OAEP", hash: "SHA-256" }, false, ["encrypt"])
  const key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt", "decrypt"])
  const wrappedKey = base64(await crypto.subtle.encrypt({ name: "RSA-OAEP" }, rsa, await crypto.subtle.exportKey("raw", key)))
  return { key, envelope: { ...await encrypt(value, key, "request", 220_000), wrappedKey } }
}

export async function openRequest(envelope: RequestEnvelope, privateKey: CryptoKey) {
  const raw = await crypto.subtle.decrypt({ name: "RSA-OAEP" }, privateKey, bytes(envelope.wrappedKey))
  const key = await crypto.subtle.importKey("raw", raw, { name: "AES-GCM" }, false, ["encrypt", "decrypt"])
  return { key, value: await decrypt(envelope, key, "request") }
}
