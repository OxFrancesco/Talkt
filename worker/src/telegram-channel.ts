import { DurableObject } from "cloudflare:workers"
import type { TelegramMessage, TelegramUpdate } from "./telegram"
import { ownerMessage, sendTelegram, messageChunks, TelegramDeliveryError } from "./telegram"

type PendingUpdate = {
  updateID: number
  message: TelegramMessage
  created: number
  notices: string[]
  nextAttemptAt?: number
} & (
  | { state: "queued" }
  | { state: "running"; runID: string }
  | { state: "reply"; chunks: string[]; sent: number; attempts: number }
)

type Approval = { sessionID: string; requestID: string }

export class TelegramChannel extends DurableObject<Env> {
  async receive(update: TelegramUpdate) {
    const message = ownerMessage(update, this.env.TELEGRAM_OWNER_ID)
    if (!message) return { accepted: false }
    const key = this.key(update.update_id)
    await this.ctx.storage.transaction(async (tx) => {
      if (await tx.get(key) || await tx.get(`seen:${update.update_id}`)) return
      await tx.put(key, { updateID: update.update_id, message, created: Date.now(), notices: [], state: "queued" } satisfies PendingUpdate)
      await tx.setAlarm(Date.now() + 100)
    })
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
            for (const permission of permissions) {
              if (item.notices.includes(permission.id)) continue
              await this.ctx.storage.put(`approval:${permission.id}`, { sessionID: run.sessionID, requestID: permission.id } satisfies Approval)
              const detail = permission.message ?? `${permission.action}\n${permission.resources.join("\n")}`
              for (const chunk of messageChunks(`Approval needed\n${detail}\n\n/approve ${permission.id}\n/deny ${permission.id}`)) await sendTelegram(this.env.TELEGRAM_BOT_TOKEN, item.message, chunk)
              item.notices.push(permission.id)
              await this.ctx.storage.put(this.key(item.updateID), item)
            }
          }
        }
        if (item.state === "reply") {
          while (item.sent < item.chunks.length) {
            await sendTelegram(this.env.TELEGRAM_BOT_TOKEN, item.message, item.chunks[item.sent])
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

  private reply(item: PendingUpdate, text: string): PendingUpdate {
    return { updateID: item.updateID, message: item.message, created: item.created, notices: item.notices, state: "reply", chunks: messageChunks(text), sent: 0, attempts: 0 }
  }

  private async handle(item: PendingUpdate): Promise<PendingUpdate> {
    const agent = this.env.TALKT_AGENT.getByName("owner")
    const text = item.message.text?.trim() ?? ""
    const [rawCommand = "", ...args] = text.split(/\s+/)
    const command = rawCommand.split("@")[0].toLowerCase()
    let response: string | undefined
    try {
      switch (command) {
        case "/start": case "/help":
          response = "Talkt uses your personal Cloudflare agent. Send a message to continue the current app conversation.\n\n/new [title]\n/sessions\n/resume <session ID>\n/stop\n/status\n/result <run ID>\n/approve <request ID>\n/deny <request ID>"
          break
        case "/new": {
          const session = await agent.createConversation(args.join(" ") || "Telegram conversation", `ses_tg_${item.updateID}`)
          await agent.selectConversation(session.id)
          response = `Started ${session.id}. The app will use this conversation too.`
          break
        }
        case "/sessions": {
          const sessions = await agent.conversations()
          response = sessions.data.map((session) => `${session.id}\n${session.title ?? "Untitled"}`).slice(0, 20).join("\n\n") || "No conversations yet."
          break
        }
        case "/resume":
          if (!args[0]) { response = "Use /resume <session ID>."; break }
          await agent.selectConversation(args[0])
          response = `Resumed ${args[0]}.`
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
          response = status.personal ? `Personal agent ready\nSkills: ${status.profile.skills}\nMCP configurations: ${status.profile.mcps}\nCLI tools: ${status.profile.clis}\nMac runner: ${status.runner.online ? "online" : "offline"}\nConversation: ${status.conversationID}` : "Personal agent is not configured."
          const approvals = await agent.permissions(status.conversationID)
          for (const approval of approvals) response += `\n\nApproval needed\n${approval.message ?? approval.action}\n/approve ${approval.id}\n/deny ${approval.id}`
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
      response = error instanceof Error && error.message.includes("busy") ? "This conversation is busy. Use /stop or /new, or wait for the current reply." : "Could not complete that request. Check /status or try /help."
    }
    const replied = this.reply(item, response ?? "Done.")
    await this.ctx.storage.put(this.key(item.updateID), replied)
    return replied
  }
}
