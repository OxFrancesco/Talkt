import { describe, expect, it } from "vitest"
import { parseLanguageCodes } from "../src/domain"

describe("parseLanguageCodes", () => {
  it("accepts and deduplicates language hints", () => {
    expect(parseLanguageCodes("en,it,en")).toEqual(["en", "it"])
  })

  it("uses automatic detection when no hint is supplied", () => {
    expect(parseLanguageCodes(null)).toEqual([])
  })

  it("rejects malformed language hints", () => {
    expect(() => parseLanguageCodes("english,<script>")).toThrow("Invalid transcription languages")
  })
})
