import { beforeAll, describe, expect, it } from "vitest"
import { env } from "cloudflare:workers"
import { generateKeyPairSync, sign } from "node:crypto"
import worker from "../src/index"
import { authenticate } from "../src/auth"

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 })
const config = {
  CLERK_JWT_KEY: publicKey.export({ type: "spki", format: "pem" }).toString(),
  CLERK_ISSUER: env.CLERK_ISSUER,
  CLERK_AUTHORIZED_PARTIES: env.CLERK_AUTHORIZED_PARTIES,
}
const token = (claims: Record<string, unknown> = {}) => {
  const now = Math.floor(Date.now() / 1000)
  const payload = {
    iss: config.CLERK_ISSUER, sub: "user_alice", sid: "sess_alice",
    iat: now, nbf: now - 1, exp: now + 60, ...claims,
  }
  const message = [JSON.stringify({ alg: "RS256", typ: "JWT", kid: "test-key" }), JSON.stringify(payload)]
    .map(value => Buffer.from(value).toString("base64url")).join(".")
  return `${message}.${sign("RSA-SHA256", Buffer.from(message), privateKey).toString("base64url")}`
}
const request = (jwt?: string, path = "/v1/transcripts", method = "GET") => new Request(`https://talkt.test${path}`, {
  method, headers: jwt ? { authorization: `Bearer ${jwt}` } : {},
})

beforeAll(() => { Object.assign(env, { CLERK_JWT_KEY: config.CLERK_JWT_KEY }) })

describe("Clerk session verification", () => {
  it("accepts native sessions without azp and keeps the same user across sessions", async () => {
    const identity = await authenticate(request(token()), config)
    expect(identity).toBe(JSON.stringify([config.CLERK_ISSUER, "user_alice"]))
    expect(await authenticate(request(token({ sid: "sess_second" })), config)).toBe(identity)
  })

  it.each([
    { exp: 1 }, { nbf: 9_999_999_999 }, { iss: "https://other.clerk.accounts.dev" },
    { sub: "" }, { sid: null }, { sts: "pending" }, { azp: "https://attacker.test" },
  ])("rejects invalid session claims %j", async claims => {
    expect(await authenticate(request(token(claims)), config)).toBeNull()
  })

  it("rejects a forged signature and the old shared token", async () => {
    const parts = token().split(".")
    parts[1] = Buffer.from(JSON.stringify({ sub: "user_bob" })).toString("base64url")
    expect(await authenticate(request(parts.join(".")), config)).toBeNull()
    expect(await authenticate(request("old-shared-token"), config)).toBeNull()
  })

  it("fails closed without verification configuration", async () => {
    await expect(authenticate(request(token()), { ...config, CLERK_JWT_KEY: "" })).rejects.toThrow("not configured")
  })
})

describe("transcription access", () => {
  it("requires Clerk for transcription and rejects removed endpoints", async () => {
    expect((await worker.fetch(request(undefined, "/v1/transcribe", "POST"), env)).status).toBe(401)
    for (const path of ["/v1/agent/prompt", "/v1/runner/poll", "/v1/runner/events", "/v1/telegram/webhook", "/v1/conversations", "/v1/connections"]) {
      expect((await worker.fetch(request(token(), path, "POST"), env)).status).toBe(404)
    }
  })
  it("allows ordinary accounts without an owner allowlist", async () => {
    for (const sub of ["user_alice", "user_bob"]) {
      expect((await worker.fetch(request(token({ sub })), env)).status).toBe(200)
    }
  })
})
