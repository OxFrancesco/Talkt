export const MAX_AUDIO_BYTES = 12 * 1024 * 1024
export const MAX_PROMPT_BYTES = 64 * 1024
const LANGUAGE_CODE = /^[a-z]{2,3}(?:-[A-Z]{2})?$/

export type ModelRef = {
  readonly providerID: string
  readonly id: string
  readonly variant?: string
}

export function parseModelRef(value: string): ModelRef {
  const at = value.indexOf("@")
  const ref = at === -1 ? value : value.slice(0, at)
  const variant = at === -1 ? undefined : value.slice(at + 1)
  const separator = ref.indexOf("/")
  if (separator <= 0 || separator === ref.length - 1 || variant === "") {
    throw new Error(`Invalid model reference: ${value}`)
  }
  return {
    providerID: ref.slice(0, separator),
    id: ref.slice(separator + 1),
    ...(variant === undefined ? {} : { variant }),
  }
}

export function parseLanguageCodes(value: string | null): string[] {
  if (!value) return []
  const codes = [...new Set(value.split(",").map((code) => code.trim()).filter(Boolean))]
  if (codes.length > 8 || codes.some((code) => !LANGUAGE_CODE.test(code))) {
    throw new Error("Invalid transcription languages")
  }
  return codes
}

export function isFallbackEligible(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error)
  const normalized = message.toLowerCase()
  const excluded = [
    "authentication",
    "unauthorized",
    "forbidden",
    "invalid request",
    "context length",
    "content policy",
    "moderation",
    "unsupported tool",
  ]
  if (excluded.some((value) => normalized.includes(value))) return false

  const eligible = [
    "rate limit",
    "quota",
    "timeout",
    "timed out",
    "temporarily unavailable",
    "service unavailable",
    "provider",
    "overloaded",
    "internal server",
    "network",
  ]
  return eligible.some((value) => normalized.includes(value))
}

export async function secureEqual(provided: string, expected: string): Promise<boolean> {
  const encoder = new TextEncoder()
  const [providedHash, expectedHash] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(provided)),
    crypto.subtle.digest("SHA-256", encoder.encode(expected)),
  ])
  const left = new Uint8Array(providedHash)
  const right = new Uint8Array(expectedHash)
  let difference = left.length ^ right.length
  const length = Math.max(left.length, right.length)
  for (let index = 0; index < length; index += 1) {
    difference |= (left[index % left.length] ?? 0) ^ (right[index % right.length] ?? 0)
  }
  return difference === 0
}
