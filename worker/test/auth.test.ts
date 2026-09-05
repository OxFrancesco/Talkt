import { beforeAll, describe, expect, it, vi } from "vitest"
import { env } from "cloudflare:workers"
import { runInDurableObject } from "cloudflare:test"
import { generateKeyPairSync, sign } from "node:crypto"
import worker from "../src/index"
import { authenticate } from "../src/auth"

// Keep provider setup offline while exercising real Durable Object storage and RPC.
vi.mock("@opencode-ai/sdk/workerd", () => ({
  create: async ({ storage }: { storage: DurableObjectStorage }) => ({
    close: async () => {},
    health: { get: async () => ({ healthy: true, version: "test" }) },
    integration: {
      get: async () => ({ data: { connections: await storage.get("test-credentials") ?? [] } }),
      connect: { key: async ({ key, label }: { key: string; label: string }) => {
        await storage.put("test-credentials", [{ type: "credential", label, key }])
      } },
    },
  }),
}))

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
const request = (jwt?: string, path = "/v1/agent/status", method = "GET") => new Request(`https://talkt.test${path}`, {
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

describe("authenticated Worker routing", () => {
  it.each([
    ["/v1/agent/status", "GET"], ["/v1/agent/prompt", "POST"],
    ["/v1/agent/openrouter/key", "POST"], ["/v1/transcribe", "POST"],
  ])("protects %s before accessing agent or cloud providers", async (path, method) => {
    expect((await worker.fetch(request(undefined, path, method), env)).status).toBe(401)
    expect((await worker.fetch(request("old-shared-token", path, method), env)).status).toBe(401)
  })

  it("keeps health public", async () => {
    expect((await worker.fetch(request(undefined, "/health"), env)).status).toBe(200)
  })

  it("routes signed users to separate persistent agents and ignores supplied user IDs", async () => {
    const aliceName = `clerk:${JSON.stringify([config.CLERK_ISSUER, "user_alice"])}`
    const bobName = `clerk:${JSON.stringify([config.CLERK_ISSUER, "user_bob"])}`
    const alice = env.TALKT_AGENT.getByName(aliceName)
    const bob = env.TALKT_AGENT.getByName(bobName)
    expect(alice.id.toString()).not.toBe(bob.id.toString())
    await runInDurableObject(alice, async (_instance, state) => { await state.storage.put("isolation-proof", "alice-private") })
    await runInDurableObject(bob, async (_instance, state) => {
      expect(await state.storage.get("isolation-proof")).toBeUndefined()
    })
    const connect = new Request("https://talkt.test/v1/agent/openrouter/key?userId=user_bob", {
      method: "POST",
      headers: { authorization: `Bearer ${token()}`, "content-type": "application/json", "content-length": "26", "x-user-id": "user_bob" },
      body: JSON.stringify({ key: "alice-test-key" }),
    })
    expect((await worker.fetch(connect, env)).status).toBe(200)
    const response = await worker.fetch(request(token(), "/v1/agent/status?userId=user_bob"), env)
    expect(response.status).toBe(200)
    expect(await response.json()).toMatchObject({ connection: { connected: true }, host: { healthy: true } })
    const bobResponse = await worker.fetch(request(token({ sub: "user_bob", sid: "sess_bob" })), env)
    expect(bobResponse.status).toBe(200)
    expect(await bobResponse.json()).toMatchObject({ connection: { connected: false } })
    await runInDurableObject(alice, async (_instance, state) => {
      expect(await state.storage.get("isolation-proof")).toBe("alice-private")
    })
  }, 30_000)
})
