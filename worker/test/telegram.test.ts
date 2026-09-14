import { env, runInDurableObject } from "cloudflare:test"
import { afterEach, describe, expect, it, vi } from "vitest"
import { ownerCallback, telegramHTML, telegramUpdateSchema } from "../src/telegram"
import { TelegramControls } from "../src/telegram-controls"

afterEach(() => vi.restoreAllMocks())

describe("Telegram controls", () => {
  it("accepts only owner callbacks from the owner's private chat", () => {
    const callback = { id: "callback", from: { id: 42 }, data: "t:opaque", message: { message_id: 10, chat: { id: 42, type: "private" } } }
    const update = telegramUpdateSchema.parse({ update_id: 1, callback_query: callback })
    expect(ownerCallback(update, "42")?.data).toBe("t:opaque")
    expect(ownerCallback(update, "43")).toBeUndefined()
    expect(ownerCallback({ ...update, callback_query: { ...callback, from: { id: 41 } } }, "42")).toBeUndefined()
    expect(ownerCallback({ ...update, callback_query: { ...callback, message: { ...callback.message, chat: { id: 42, type: "group" } } } }, "42")).toBeUndefined()
    expect(ownerCallback({ ...update, callback_query: { ...callback, message: undefined } }, "42")).toBeUndefined()
  })

  it("formats Markdown as escaped Telegram HTML", () => {
    expect(telegramHTML('**Done** <script>& `x<y` [Open](https://example.com/?a=1&b=2)')).toBe('<b>Done</b> &lt;script&gt;&amp; <code>x&lt;y</code> <a href="https://example.com/?a=1&amp;b=2">Open</a>')
    expect(telegramHTML('```ts\nconst x = "<unsafe>"\n```')).toBe('<pre>const x = &quot;&lt;unsafe&gt;&quot;\n</pre>')
    expect(telegramHTML('[bad](javascript:alert)')).not.toContain('<a')
  })

  it("binds approval buttons to their message, persists their exact session, and consumes them once", async () => {
    const stub = env.TALKT_TELEGRAM.get(env.TALKT_TELEGRAM.newUniqueId())
    await runInDurableObject(stub, async (_instance, state) => {
      const fetcher = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ ok: true, result: { message_id: 123 } })))
      const controls = new TelegramControls(state.storage, env)
      const message = { message_id: 1, chat: { id: 42, type: "private" } }
      const reply = controls.approval("session-a", { id: "request-a", action: "personal_exec", resources: ['{"argv":["git","status"]}'] })
      expect(await controls.send(message, reply)).toBe(123)
      const body = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body))
      const token: string = body.reply_markup.inline_keyboard[0][0].callback_data
      expect(new TextEncoder().encode(token).length).toBeLessThanOrEqual(64)
      expect(body.text).not.toContain("/approve")
      expect(await controls.resolve(token, 999, 1)).toBeUndefined()
      const restarted = new TelegramControls(state.storage, env)
      expect(await restarted.resolve(token, 123, 1)).toEqual({ kind: "approval", sessionID: "session-a", requestID: "request-a", reply: "once" })
      expect(await restarted.resolve(token, 123, 1)).toBeDefined()
      expect(await restarted.resolve(token, 123, 2)).toBeUndefined()
      expect(await restarted.resolve("t:forged", 123, 3)).toBeUndefined()
    })
  })
})
