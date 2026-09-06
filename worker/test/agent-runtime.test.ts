import { env } from "cloudflare:test"
import { describe, expect, it } from "vitest"
import { messageChunks, ownerMessage, telegramUpdateSchema } from "../src/telegram"

describe("personal agent runtime", () => {
  it("boots the real Workerd host with imported skills and preserves explicit conversations", async () => {
    const agent = env.TALKT_AGENT.getByName("owner")
    const health = await agent.health()
    expect(health.healthy).toBe(true)
    const capabilities = await agent.capabilities()
    expect(capabilities.personal).toBe(true)
    expect(capabilities.profile?.skills).toBeGreaterThan(0)
    expect(capabilities.profile?.instructions).toBe(true)
    const created = await agent.createConversation("Runtime verification")
    await agent.selectConversation(created.id)
    expect((await agent.capabilities()).conversationID).toBe(created.id)
    const sessions = await agent.conversations()
    expect(sessions.data.some((session) => session.id === created.id)).toBe(true)
    const other = env.TALKT_AGENT.getByName(`clerk:runtime-${crypto.randomUUID()}`)
    const isolated = await other.capabilities()
    expect(isolated.personal).toBe(false)
    expect("profile" in isolated).toBe(false)
  }, 60_000)
})

describe("Telegram boundaries", () => {
  it("accepts only the configured owner's private messages", () => {
    const input = { update_id: 1, message: { message_id: 2, from: { id: 42 }, chat: { id: 42, type: "private" }, text: "hello" } }
    const update = telegramUpdateSchema.parse(input)
    expect(ownerMessage(update, "42")?.text).toBe("hello")
    expect(ownerMessage(update, "43")).toBeUndefined()
    expect(ownerMessage({ ...update, message: { ...input.message, chat: { id: 42, type: "group" } } }, "42")).toBeUndefined()
    expect(ownerMessage({ ...update, message: { ...input.message, from: { id: 42, is_bot: true } } }, "42")).toBeUndefined()
  })
  it("chunks Unicode without splitting surrogate pairs", () => {
    const text = "🙂".repeat(7001)
    const chunks = messageChunks(text)
    expect(chunks).toHaveLength(5)
    expect(chunks.join("")).toBe(text)
    expect(chunks.every((chunk) => chunk.length <= 3500)).toBe(true)
  })
})
