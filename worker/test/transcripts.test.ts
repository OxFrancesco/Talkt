import { beforeAll, expect, it, vi } from "vitest"
import { env } from "cloudflare:workers"
import { runInDurableObject } from "cloudflare:test"
import { generateKeyPairSync, sign } from "node:crypto"
import worker from "../src/index"
import type { Transcript } from "../src/transcripts"

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 })
beforeAll(() => { Object.assign(env, { CLERK_JWT_KEY: publicKey.export({ type: "spki", format: "pem" }).toString() }) })
function token(owner: string) {
  const now = Math.floor(Date.now() / 1000)
  const body = [JSON.stringify({ alg: "RS256", typ: "JWT" }), JSON.stringify({ iss: env.CLERK_ISSUER, sub: owner, sid: "sess_test", iat: now, nbf: now - 1, exp: now + 60 })].map(value => Buffer.from(value).toString("base64url")).join(".")
  return `${body}.${sign("RSA-SHA256", Buffer.from(body), privateKey).toString("base64url")}`
}
function call(owner: string | null, method = "GET", path = "", entry?: unknown) {
  return worker.fetch(new Request(`https://talkt.test/v1/transcripts${path}`, { method,
    headers: owner ? { authorization: `Bearer ${token(owner)}`, "content-type": "application/json" } : {},
    body: entry === undefined ? undefined : JSON.stringify(entry),
  }), env)
}
const entry = (text = "Caffè tomorrow at the museum"): Transcript => ({ id: crypto.randomUUID(), timeMillis: Date.now(), text, kind: "transcript", source: "test", reply: "Remember the tickets" })
const archive = (owner: string) => env.TRANSCRIPT_ARCHIVE.getByName(JSON.stringify([env.CLERK_ISSUER, owner]))

it("requires authentication on storage, reads, search and deletion", async () => {
  for (const method of ["GET", "PUT", "DELETE"]) expect((await call(null, method, `/${crypto.randomUUID()}`)).status).toBe(401)
  expect((await call(null)).status).toBe(401)
})

it("isolates authenticated accounts, saves in private R2 and searches text and replies", async () => {
  const owner = `user_${crypto.randomUUID()}`, other = `user_${crypto.randomUUID()}`, note = entry()
  expect((await call(owner, "PUT", `/${note.id}`, note)).status).toBe(200)
  const object = await env.TRANSCRIPTS.get(`${archive(owner).id}/${note.id}.json`)
  expect(await object?.json()).toEqual(note)
  expect(await (await call(owner, "GET", `/${note.id}`)).json()).toEqual(note)
  for (const query of ["caffe", "muse", "tickets", "tomorrow museum"]) {
    expect(await (await call(owner, "GET", `?q=${encodeURIComponent(query)}&owner=${other}`)).json()).toMatchObject({ entries: [note] })
  }
  expect(await (await call(other, "GET", "?q=museum")).json()).toMatchObject({ entries: [] })
  expect((await call(other, "GET", `/${note.id}`)).status).toBe(404)
  await call(other, "DELETE", `/${note.id}`)
  expect((await call(owner, "GET", `/${note.id}`)).status).toBe(200)
})

it("retries without duplicates and refuses changed payloads or deleted IDs", async () => {
  const owner = `user_${crypto.randomUUID()}`, note = entry()
  const responses = await Promise.all(Array.from({ length: 4 }, () => call(owner, "PUT", `/${note.id}`, note)))
  expect(responses.map(r => r.status)).toEqual([200, 200, 200, 200])
  expect(await (await call(owner)).json()).toMatchObject({ entries: [note] })
  expect((await call(owner, "PUT", `/${note.id}`, { ...note, text: "changed" })).status).toBe(409)
  expect((await call(owner, "DELETE", `/${note.id}`)).status).toBe(200)
  expect(await env.TRANSCRIPTS.get(`${archive(owner).id}/${note.id}.json`)).toBeNull()
  expect(await (await call(owner)).json()).toMatchObject({ entries: [] })
  expect((await call(owner, "PUT", `/${note.id}`, note)).status).toBe(410)
})

it("recovers pending writes and deletes through durable alarms", async () => {
  const stub = archive(`user_${crypto.randomUUID()}`), note = entry()
  await runInDurableObject(stub, async (instance, state) => {
    state.storage.sql.exec("INSERT INTO transcripts(id,time,payload,pending) VALUES(?,?,?,1)", note.id, note.timeMillis, JSON.stringify(note))
    await instance.alarm()
    expect(state.storage.sql.exec("SELECT pending FROM transcripts WHERE id=?", note.id).one().pending).toBe(0)
    state.storage.sql.exec("UPDATE transcripts SET deleted=1,pending=1,payload='' WHERE id=?", note.id)
    await instance.alarm()
    expect(await state.storage.getAlarm()).toBeNull()
  })
  expect(await env.TRANSCRIPTS.get(`${stub.id}/${note.id}.json`)).toBeNull()
})

it("handles punctuation safely and rejects malformed IDs, fields and oversized queries", async () => {
  const owner = `user_${crypto.randomUUID()}`, note = entry()
  expect((await call(owner, "PUT", `/${note.id}`, { ...note, owner: "user_other" })).status).toBe(400)
  expect((await call(owner, "PUT", "/not-a-uuid", note)).status).toBe(400)
  expect((await call(owner, "PUT", `/${crypto.randomUUID()}`, note)).status).toBe(400)
  expect((await call(owner, "GET", `?q=${"a".repeat(501)}`)).status).toBe(400)
  for (const q of ["***", '" OR museum', "NEAR(museum)", "' ; DROP TABLE transcripts --"]) {
    expect((await call(owner, "GET", `?q=${encodeURIComponent(q)}`)).status).toBe(200)
  }
})

it("keeps failed R2 writes pending and retries them without losing the search entry", async () => {
  const stub = archive(`user_${crypto.randomUUID()}`), note = entry()
  await runInDurableObject(stub, async (instance, state) => {
    const bucket = (instance as unknown as { env: Env }).env.TRANSCRIPTS
    const failed = vi.spyOn(bucket, "put").mockRejectedValueOnce(new Error("temporary outage"))
    try { expect(await instance.put(note)).toEqual({ status: 202, saved: false }) }
    finally { failed.mockRestore() }
    expect(await state.storage.getAlarm()).not.toBeNull()
    expect((await instance.search("museum")).entries).toEqual([note])
    await instance.alarm()
    expect(await instance.get(note.id)).toEqual(note)
    const failedDelete = vi.spyOn(bucket, "delete").mockRejectedValueOnce(new Error("temporary outage"))
    try { expect(await instance.remove(note.id)).toEqual({ pending: true }) }
    finally { failedDelete.mockRestore() }
    expect((await instance.search("museum")).entries).toEqual([])
    await instance.alarm()
    expect(await instance.get(note.id)).toBeNull()
  })
  expect(await env.TRANSCRIPTS.get(`${stub.id}/${note.id}.json`)).toBeNull()
})

it("paginates account archives and searches without duplicating stable results", async () => {
  const stub = archive(`user_${crypto.randomUUID()}`)
  for (let i = 0; i < 52; i++) await stub.put({ ...entry(), timeMillis: i })
  for (const q of ["", "museum"]) {
    const first = await stub.search(q)
    expect(first.entries).toHaveLength(50)
    expect(first.nextOffset).toBe(50)
    const second = await stub.search(q, first.nextOffset!)
    expect(second.entries).toHaveLength(2)
    expect(second.nextOffset).toBeNull()
    expect(new Set([...first.entries, ...second.entries].map(row => row.id)).size).toBe(52)
  }
})
