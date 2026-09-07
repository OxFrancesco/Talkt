import { describe, expect, it } from "vitest"
import { secureEqual } from "../src/domain"

describe("secureEqual", () => {
  it("compares token contents", async () => {
    await expect(secureEqual("same", "same")).resolves.toBe(true)
    await expect(secureEqual("same", "different")).resolves.toBe(false)
  })
})
