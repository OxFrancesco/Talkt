import { describe, expect, it } from "vitest"
import { isFallbackEligible, parseModelRef, secureEqual } from "../src/domain"

describe("parseModelRef", () => {
  it("keeps slash-delimited model IDs intact", () => {
    expect(parseModelRef("openrouter/openai/gpt-5.4")).toEqual({
      providerID: "openrouter",
      id: "openai/gpt-5.4",
    })
  })

  it("rejects malformed values", () => {
    expect(() => parseModelRef("gpt-5.4")).toThrow("Invalid model reference")
  })
})

describe("fallback policy", () => {
  it("allows provider availability failures", () => {
    expect(isFallbackEligible(new Error("Provider rate limit exceeded"))).toBe(true)
  })

  it("does not route authentication or policy failures elsewhere", () => {
    expect(isFallbackEligible(new Error("Authentication failed"))).toBe(false)
    expect(isFallbackEligible(new Error("Content policy refused"))).toBe(false)
  })
})

describe("secureEqual", () => {
  it("compares token contents", async () => {
    await expect(secureEqual("same", "same")).resolves.toBe(true)
    await expect(secureEqual("same", "different")).resolves.toBe(false)
  })
})
