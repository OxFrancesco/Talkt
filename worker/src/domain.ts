export const MAX_AUDIO_BYTES = 12 * 1024 * 1024
export const MAX_PROMPT_BYTES = 64 * 1024
const LANGUAGE_CODE = /^[a-z]{2,3}(?:-[A-Z]{2})?$/

export function parseLanguageCodes(value: string | null): string[] {
  if (!value) return []
  const codes = [...new Set(value.split(",").map((code) => code.trim()).filter(Boolean))]
  if (codes.length > 8 || codes.some((code) => !LANGUAGE_CODE.test(code))) {
    throw new Error("Invalid transcription languages")
  }
  return codes
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
