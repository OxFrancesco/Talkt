import { sendTelegram, escapeHTML, type TelegramMessage, type TelegramKeyboard } from "./telegram"

export type TelegramAction =
  | { kind: "menu" | "sessions" | "settings" | "new" | "chatgpt" | "chatgpt-check" }
  | { kind: "resume" | "stop"; sessionID: string }
  | { kind: "yolo"; enabled: boolean }
  | { kind: "provider"; provider: "chatgpt" | "openrouter" }
  | { kind: "approval"; sessionID: string; requestID: string; reply: "once" | "reject" }

type ControlButton = { text: string } & ({ action: TelegramAction } | { url: string } | { copy: string })
export type TelegramReply = { text: string; buttons?: ControlButton[][]; html?: boolean }
type Callback = { action: TelegramAction; messageID: number; expires: number; usedBy?: number }

const menuButtons: ControlButton[][] = [
  [{ text: "New chat", action: { kind: "new" } }, { text: "Conversations", action: { kind: "sessions" } }],
  [{ text: "Settings", action: { kind: "settings" } }],
]

export class TelegramControls {
  constructor(private readonly storage: DurableObjectStorage, private readonly env: Env) {}

  async send(message: TelegramMessage, reply: TelegramReply) {
    const callbacks: Array<{ key: string; action: TelegramAction }> = []
    const keyboard: TelegramKeyboard = (reply.buttons ?? []).map(row => row.map(button => {
      if ("url" in button) return { text: button.text, url: button.url }
      if ("copy" in button) return { text: button.text, copy_text: { text: button.copy } }
      const key = `t:${crypto.randomUUID().replaceAll("-", "")}`
      callbacks.push({ key, action: button.action })
      return { text: button.text, callback_data: key }
    }))
    const messageID = await sendTelegram(this.env.TELEGRAM_BOT_TOKEN, message, reply.text, { html: reply.html, ...(keyboard.length ? { keyboard } : {}) })
    for (const callback of callbacks) await this.storage.put(`button:${callback.key}`, { action: callback.action, messageID, expires: Date.now() + 86_400_000 } satisfies Callback)
    return messageID
  }

  async resolve(data: string, messageID: number, updateID: number): Promise<TelegramAction | undefined> {
    return this.storage.transaction(async tx => {
      const key = `button:${data}`
      const callback = await tx.get<Callback>(key)
      if (!callback || callback.messageID !== messageID || callback.expires <= Date.now()) return
      if (!["menu", "settings", "sessions", "chatgpt-check"].includes(callback.action.kind)) {
        if (callback.usedBy !== undefined && callback.usedBy !== updateID) return
        await tx.put(key, { ...callback, usedBy: updateID })
      }
      return callback.action
    })
  }

  approval(sessionID: string, request: { id: string; action: string; message?: string; resources: string[] }): TelegramReply {
    const input = request.resources.join("\n").slice(0, 2500)
    return {
      text: `<b>Allow ${escapeHTML(request.action)}?</b>\n<pre>${escapeHTML(input || request.message || request.action)}</pre>`, html: true,
      buttons: [[
        { text: "Allow once", action: { kind: "approval", sessionID, requestID: request.id, reply: "once" } },
        { text: "Deny", action: { kind: "approval", sessionID, requestID: request.id, reply: "reject" } },
      ]],
    }
  }

  async handle(action: TelegramAction, updateID: number): Promise<TelegramReply> {
    const agent = this.env.TALKT_AGENT.getByName("owner")
    switch (action.kind) {
      case "menu": return { text: "Send a message or choose an action.", buttons: menuButtons }
      case "new": {
        const session = await agent.createConversation("Telegram conversation", `ses_tg_${updateID}`)
        await agent.selectConversation(session.id)
        return { text: "New chat started." }
      }
      case "sessions": {
        const sessions = await agent.conversations()
        const titles = new Map<string, number>()
        for (const session of sessions.data) titles.set(session.title ?? "Untitled", (titles.get(session.title ?? "Untitled") ?? 0) + 1)
        const date = new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Rome", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })
        return { text: sessions.data.length ? "Choose a conversation." : "No conversations yet.", buttons: [
          ...sessions.data.slice(0, 20).map(session => {
            const title = session.title || "Untitled"
            return [{ text: (titles.get(title) ?? 0) > 1 ? `${title.slice(0, 36)} · ${date.format(session.created)}` : title.slice(0, 60), action: { kind: "resume" as const, sessionID: session.id } }]
          }),
          [{ text: "New chat", action: { kind: "new" } }],
        ] }
      }
      case "resume":
        await agent.selectConversation(action.sessionID)
        return { text: "Conversation resumed." }
      case "stop":
        await agent.stopConversation(action.sessionID)
        return { text: "Stopped." }
      case "approval":
        try {
          await agent.replyPermission(action.sessionID, action.requestID, action.reply)
          return { text: action.reply === "once" ? "Allowed once." : "Denied." }
        } catch { return { text: "This approval was already handled or expired." } }
      case "yolo":
        await agent.setYolo(action.enabled)
        return this.settings()
      case "settings": return this.settings()
      case "provider":
        await agent.useProvider(action.provider)
        return this.settings()
      case "chatgpt": {
        const attempt = await agent.beginChatGPT()
        const code = attempt.instructions.match(/Enter code:\s*(\S+)/)?.[1]
        return {
          text: `Sign in with ChatGPT.\n\n${escapeHTML(attempt.instructions)}\n\nAfter signing in, check the connection.`, html: true,
          buttons: [
            [{ text: "Open ChatGPT", url: attempt.url }, ...(code ? [{ text: "Copy code", copy: code }] : [])],
            [{ text: "Check connection", action: { kind: "chatgpt-check" } }],
          ],
        }
      }
      case "chatgpt-check": {
        const result = await agent.chatGPTStatus()
        if (result.status === "complete" || result.status === "idle" && (await agent.providerStatus()).chatgpt) return this.settings()
        return { text: result.status === "pending" ? "Waiting for ChatGPT sign-in." : "Login expired or failed. Start a new login.", buttons: [[{
          text: result.status === "pending" ? "Check again" : "Sign in with ChatGPT",
          action: { kind: result.status === "pending" ? "chatgpt-check" : "chatgpt" },
        }]] }
      }
    }
  }

  async settings(): Promise<TelegramReply> {
    const agent = this.env.TALKT_AGENT.getByName("owner")
    const [mode, providers] = await Promise.all([agent.permissionMode(), agent.providerStatus()])
    return {
      text: `YOLO ${mode.yolo ? "on. Tools run without approval prompts." : "off. Tool actions ask for approval."}\nModel: ${providers.model}\nChatGPT: ${providers.chatgpt ? "connected" : "not connected"}`,
      buttons: [
        [{ text: mode.yolo ? "Turn YOLO off" : "Turn YOLO on", action: { kind: "yolo", enabled: !mode.yolo } }],
        [{ text: providers.chatgpt ? "Use ChatGPT" : "Sign in with ChatGPT", action: providers.chatgpt ? { kind: "provider", provider: "chatgpt" } : { kind: "chatgpt" } },
          ...(providers.openrouter ? [{ text: "Use OpenRouter", action: { kind: "provider" as const, provider: "openrouter" as const } }] : [])],
        [{ text: "Conversations", action: { kind: "sessions" } }],
      ],
    }
  }
}
