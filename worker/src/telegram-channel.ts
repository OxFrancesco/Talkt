import { DurableObject } from "cloudflare:workers"
import type { TelegramMessage, TelegramUpdate } from "./telegram"
import { ownerMessage, ownerCallback, answerCallback, clearKeyboard, messageChunks, telegramHTML, telegramRequest, TelegramDeliveryError } from "./telegram"
import { TelegramControls, type TelegramAction, type TelegramReply } from "./telegram-controls"

type PendingUpdate = {
  updateID: number
  message: TelegramMessage
  created: number
  notices: string[]
  nextAttemptAt?: number
  action?: TelegramAction
  callback?: { id: string; messageID: number }
  lastTyping?: number
} & (
  | { state: "queued" }
  | { state: "running"; runID: string }
  | { state: "reply"; chunks: string[]; sent: number; attempts: number; buttons?: TelegramReply["buttons"]; html?: boolean }
)

type Approval = { sessionID: string; requestID: string }

export class TelegramChannel extends DurableObject<Env> {
  private controls() { return new TelegramControls(this.ctx.storage, this.env) }

  async receive(update: TelegramUpdate) {
    const callback = ownerCallback(update, this.env.TELEGRAM_OWNER_ID)
    const message = callback?.message ?? ownerMessage(update, this.env.TELEGRAM_OWNER_ID)
    if (!message) return { accepted: false }
    const key = this.key(update.update_id)
    const action = callback ? await this.controls().resolve(callback.data, callback.message.message_id, update.update_id) : undefined
    if (callback && !action) {
      await answerCallback(this.env.TELEGRAM_BOT_TOKEN, callback.id, "This button expired or was already used. Open /menu.").catch(() => undefined)
      return { accepted: false }
    }
    await this.ctx.storage.transaction(async (tx) => {
      if (await tx.get(key) || await tx.get(`seen:${update.update_id}`)) return
      await tx.put(key, { updateID: update.update_id, message, created: Date.now(), notices: [], state: "queued", ...(action ? { action } : {}), ...(callback ? { callback: { id: callback.id, messageID: message.message_id } } : {}) } satisfies PendingUpdate)
      await tx.setAlarm(Date.now() + 100)
    })
    if (callback) await answerCallback(this.env.TELEGRAM_BOT_TOKEN, callback.id).catch(() => undefined)
    return { accepted: true }
  }

  private key(id: number) { return `inbox:${String(id).padStart(16, "0")}` }

  async alarm() {
    const pending = await this.ctx.storage.list<PendingUpdate>({ prefix: "inbox:", limit: 100 })
    let retryAt = Date.now() + 2000
    for (let item of pending.values()) {
      if (item.nextAttemptAt && item.nextAttemptAt > Date.now()) continue
      try {
        if (item.state === "queued") item = await this.handle(item)
        if (item.state === "running") {
          const agent = this.env.TALKT_AGENT.getByName("owner")
          const run = await agent.runStatus(item.runID)
          if (run.state === "completed" || run.state === "failed") {
            item = this.reply(item, run.state === "completed" ? run.response : run.error)
          } else {
            const permissions = await agent.permissions(run.sessionID)
            if (!permissions.length && Date.now() - (item.lastTyping ?? 0) > 4000) {
              await telegramRequest(this.env.TELEGRAM_BOT_TOKEN, "sendChatAction", { chat_id: item.message.chat.id, action: "typing" }).catch(() => undefined)
              item.lastTyping = Date.now()
              await this.ctx.storage.put(this.key(item.updateID), item)
            }
            for (const permission of permissions) {
              if (item.notices.includes(permission.id)) continue
              await this.ctx.storage.put(`approval:${permission.id}`, { sessionID: run.sessionID, requestID: permission.id } satisfies Approval)
              await this.controls().send(item.message, this.controls().approval(run.sessionID, permission))
              item.notices.push(permission.id)
              await this.ctx.storage.put(this.key(item.updateID), item)
            }
          }
        }
        if (item.state === "reply") {
          while (item.sent < item.chunks.length) {
            await this.controls().send(item.message, { text: item.chunks[item.sent], html: item.html, ...(item.sent === item.chunks.length - 1 ? { buttons: item.buttons } : {}) })
            item.sent += 1
            await this.ctx.storage.put(this.key(item.updateID), item)
          }
          await this.ctx.storage.transaction(async (tx) => {
            await tx.put(`seen:${item.updateID}`, Date.now())
            await tx.delete(this.key(item.updateID))
          })
        }
      } catch (error) {
        if (error instanceof TelegramDeliveryError && !error.retryable) {
          await this.ctx.storage.put(`failed:${item.updateID}`, { state: "undeliverable", created: Date.now() })
          await this.ctx.storage.put(`seen:${item.updateID}`, Date.now())
          await this.ctx.storage.delete(this.key(item.updateID))
        } else {
          const delay = error instanceof TelegramDeliveryError ? error.retryAfter * 1000 : 10_000
          item.nextAttemptAt = Date.now() + delay
          await this.ctx.storage.put(this.key(item.updateID), item)
          console.warn(JSON.stringify({ message: "telegram_retry", updateID: item.updateID, state: item.state, reason: error instanceof Error ? error.message.replaceAll(this.env.TELEGRAM_BOT_TOKEN, "[redacted]").slice(0, 300) : "unknown" }))
        }
      }
    }
    const remaining = await this.ctx.storage.list<PendingUpdate>({ prefix: "inbox:", limit: 100 })
    if (remaining.size) {
      retryAt = Math.min(...[...remaining.values()].map(item => Math.max(Date.now() + 100, item.nextAttemptAt ?? Date.now() + 2000)))
      await this.ctx.storage.setAlarm(retryAt)
    }
  }

  private reply(item: PendingUpdate, value: string | TelegramReply): PendingUpdate {
    const reply = typeof value === "string" ? { text: value } : value
    return { updateID: item.updateID, message: item.message, created: item.created, notices: item.notices, state: "reply", chunks: reply.html ? [reply.text] : messageChunks(reply.text).map(telegramHTML), sent: 0, attempts: 0, html: true, buttons: reply.buttons }
  }

  private async handle(item: PendingUpdate): Promise<PendingUpdate> {
    const agent = this.env.TALKT_AGENT.getByName("owner")
    const text = item.message.text?.trim() ?? ""
    const [rawCommand = "", ...args] = text.split(/\s+/)
    const command = rawCommand.split("@")[0].toLowerCase()
    let response: string | TelegramReply | undefined
    try {
      if (item.action) {
        response = await this.controls().handle(item.action, item.updateID)
        if (item.callback && !["menu", "sessions", "settings", "chatgpt-check"].includes(item.action.kind)) {
          await clearKeyboard(this.env.TELEGRAM_BOT_TOKEN, item.message.chat.id, item.callback.messageID).catch(() => undefined)
        }
        const replied = this.reply(item, response)
        await this.ctx.storage.put(this.key(item.updateID), replied)
        return replied
      }
      switch (command) {
        case "/start": case "/help": case "/menu":
          await this.configureMenu()
          response = await this.controls().handle({ kind: "menu" }, item.updateID)
          break
        case "/settings":
          response = await this.controls().settings()
          break
        case "/yolo":
          response = args[0] === "on" || args[0] === "off"
            ? await this.controls().handle({ kind: "yolo", enabled: args[0] === "on" }, item.updateID)
            : await this.controls().settings()
          break
        case "/connect":
          response = await this.controls().handle({ kind: "chatgpt" }, item.updateID)
          break
        case "/new": {
          const session = await agent.createConversation(args.join(" ") || "Telegram conversation", `ses_tg_${item.updateID}`)
          await agent.selectConversation(session.id)
          response = "New chat started."
          break
        }
        case "/sessions": {
          response = await this.controls().handle({ kind: "sessions" }, item.updateID)
          break
        }
        case "/resume":
          if (!args[0]) { response = "Use /resume <session ID>."; break }
          await agent.selectConversation(args[0])
          response = "Conversation resumed."
          break
        case "/stop":
          await agent.stopConversation()
          response = "Stopped the active conversation."
          break
        case "/result": {
          if (!args[0]) { response = "Use /result <run ID>."; break }
          const run = await agent.runStatus(args[0])
          response = run.state === "completed" ? run.response : run.state === "failed" ? run.error : "Still working. Use /status for pending approvals."
          break
        }
        case "/status": {
          const status = await agent.capabilities()
          const mode = await agent.permissionMode()
          const provider = await agent.providerStatus()
          response = { text: `Model: ${provider.model}\nYOLO: ${mode.yolo ? "on" : "off"}\nMac runner: ${status.personal && status.runner.online ? "online" : "offline"}`, buttons: [[
            { text: "Stop", action: { kind: "stop", sessionID: status.conversationID } },
            { text: "Settings", action: { kind: "settings" } },
          ]] }
          const approvals = await agent.permissions(status.conversationID)
          for (const approval of approvals) await this.controls().send(item.message, this.controls().approval(status.conversationID, approval))
          break
        }
        case "/approve": case "/deny": {
          let approval = args[0] ? await this.ctx.storage.get<Approval>(`approval:${args[0]}`) : undefined
          if (!approval && args[0]) {
            const current = await agent.capabilities()
            if ((await agent.permissions(current.conversationID)).some(permission => permission.id === args[0])) approval = { sessionID: current.conversationID, requestID: args[0] }
          }
          if (!approval) { response = "No matching approval request. Use the request ID shown in the approval message."; break }
          await agent.replyPermission(approval.sessionID, approval.requestID, command === "/approve" ? "once" : "reject")
          await this.ctx.storage.delete(`approval:${args[0]}`)
          response = command === "/approve" ? "Approved this action." : "Action denied."
          break
        }
        default:
          if (command.startsWith("/")) { response = "Unknown command. Use /help."; break }
          if (!text) { response = "Send a text message. Voice and file messages are not supported yet."; break }
          const run = await agent.startRun(text, "telegram", undefined, `tg_${item.updateID}`)
          const running: PendingUpdate = { ...item, state: "running", runID: run.id }
          await this.ctx.storage.put(this.key(item.updateID), running)
          return running
      }
    } catch (error) {
      response = error instanceof Error && error.message.includes("busy")
        ? { text: "This conversation is still working.", buttons: [[{ text: "Stop", action: { kind: "stop", sessionID: (await agent.capabilities()).conversationID } }, { text: "New chat", action: { kind: "new" } }]] }
        : { text: "Could not complete that request.", buttons: [[{ text: "Settings", action: { kind: "settings" } }]] }
    }
    const replied = this.reply(item, response ?? "Done.")
    await this.ctx.storage.put(this.key(item.updateID), replied)
    return replied
  }

  private async configureMenu() {
    if (await this.ctx.storage.get("menu:v2")) return
    await telegramRequest(this.env.TELEGRAM_BOT_TOKEN, "setMyCommands", { commands: [
      { command: "menu", description: "Open controls" }, { command: "new", description: "Start a new chat" },
      { command: "sessions", description: "Choose a conversation" }, { command: "settings", description: "YOLO mode and model connection" },
      { command: "status", description: "Current model, runner and approvals" }, { command: "stop", description: "Stop the current run" },
    ], scope: { type: "chat", chat_id: Number(this.env.TELEGRAM_OWNER_ID) } })
    await this.ctx.storage.put("menu:v2", true)
  }
}
