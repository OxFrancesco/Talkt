export const MAX_AUDIO_BYTES = 12 * 1024 * 1024
const LANGUAGE_CODE = /^[a-z]{2,3}(?:-[A-Z]{2})?$/

export function parseLanguageCodes(value: string | null): string[] {
  if (!value) return []
  const codes = [...new Set(value.split(",").map((code) => code.trim()).filter(Boolean))]
  if (codes.length > 8 || codes.some((code) => !LANGUAGE_CODE.test(code))) {
    throw new SyntaxError("Invalid transcription languages")
  }
  return codes
}
