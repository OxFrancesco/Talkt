import { describe, expect, it } from "bun:test"
import { CHATGPT_RESPONSES_URL, decrypt, openRequest, sealRequest } from "../../shared/chatgpt-relay"
import { relayRequest } from "../src/chatgpt-relay-mcp"

const keys = await crypto.subtle.generateKey({ name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["encrypt", "decrypt"])
const publicKey = Buffer.from(await crypto.subtle.exportKey("spki", keys.publicKey)).toString("base64")
const request = { url: CHATGPT_RESPONSES_URL, method: "POST", headers: { authorization: "Bearer fixture-private-token", "content-type": "application/json", cookie: "must-not-forward" }, body: JSON.stringify({ model: "gpt-5.5", input: "private prompt" }) }

describe("encrypted ChatGPT transport", () => {
  it("keeps credentials out of journal packets and preserves a streamed response", async () => {
    const sealed = await sealRequest(JSON.stringify(request), publicKey)
    expect(JSON.stringify(sealed.envelope)).not.toContain("fixture-private-token")
    expect(JSON.stringify(sealed.envelope)).not.toContain("private prompt")
    let called = false
    const response = await relayRequest(sealed.envelope, keys.privateKey, async (url, init) => {
      called = true
      expect(url).toBe(CHATGPT_RESPONSES_URL)
      expect(init?.redirect).toBe("error")
      const headers = new Headers(init?.headers)
      expect(headers.get("authorization")).toBe("Bearer fixture-private-token")
      expect(headers.has("cookie")).toBe(false)
      return new Response('data: {"text":"hello"}\n\ndata: [DONE]\n\n', { headers: { "content-type": "text/event-stream" } })
    })
    expect(called).toBe(true)
    expect(JSON.stringify(response)).not.toContain("hello")
    expect(JSON.parse(await decrypt(response, sealed.key, "response"))).toEqual({ status: 200, contentType: "text/event-stream", body: 'data: {"text":"hello"}\n\ndata: [DONE]\n\n' })
    await expect(decrypt(response, sealed.key, "request")).rejects.toThrow()
  })

  it("rejects an altered encrypted packet", async () => {
    const sealed = await sealRequest(JSON.stringify(request), publicKey)
    const payload = sealed.envelope.payload
    await expect(openRequest({ ...sealed.envelope, payload: (payload[0] === "A" ? "B" : "A") + payload.slice(1) }, keys.privateKey)).rejects.toThrow()
  })

  it("rejects other destinations before making a network request", async () => {
    const sealed = await sealRequest(JSON.stringify({ ...request, url: "https://example.com/collect" }), publicKey)
    let called = false
    await expect(relayRequest(sealed.envelope, keys.privateKey, async () => { called = true; return new Response() })).rejects.toThrow()
    expect(called).toBe(false)
  })

  it("bounds the encrypted result instead of returning a truncated event stream", async () => {
    const sealed = await sealRequest(JSON.stringify(request), publicKey)
    const random = Array.from({ length: 2_000_000 }, () => String.fromCharCode(32 + Math.floor(Math.random() * 90))).join("")
    await expect(relayRequest(sealed.envelope, keys.privateKey, async () => new Response(random))).rejects.toThrow("size limit")
  })
})
