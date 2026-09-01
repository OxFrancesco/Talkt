import { describe, expect, it } from "vitest"
import { extractInteractionText } from "../src/gemini"

describe("extractInteractionText", () => {
  it("reads raw REST model output steps", () => {
    expect(extractInteractionText({
      steps: [
        { type: "model_input", content: [{ type: "text", text: "ignored" }] },
        { type: "model_output", content: [{ type: "text", text: " Ciao mondo. " }] },
      ],
    })).toBe("Ciao mondo.")
  })

  it("keeps the SDK convenience field compatible", () => {
    expect(extractInteractionText({ output_text: " Hello. " })).toBe("Hello.")
  })
})
