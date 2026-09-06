import type { OpenCode } from "@opencode-ai/sdk"
import { parseModelRef, isFallbackEligible } from "./domain"

export type Run = {
  id: string
  sessionID: string
  messageID: string
  text: string
  source: string
  created: number
  fallback: boolean
} & (
  | { state: "queued" }
  | { state: "running" }
  | { state: "completed"; response: string }
  | { state: "failed"; error: string }
)

export class ConversationRuns {
  constructor(
    private readonly storage: DurableObjectStorage,
    private readonly client: () => Promise<OpenCode.Interface>,
    private readonly primary: string,
    private readonly fallback: string,
  ) {}

  async create(title = "Talkt conversation", id?: string) {
    const client = await this.client()
    if (id) {
      const known = await this.storage.get<string>(`conversation:create:${id}`)
      if (known) return client.sessions.get({ sessionID: known })
    }
    const session = await client.sessions.create({
      ...(id ? { id } : {}), title, agent: "build", model: parseModelRef(this.primary),
      metadata: { source: "talkt", owner: "personal" },
    })
    if (id) await this.storage.put(`conversation:create:${id}`, session.id)
    return session
  }

  async defaultSession(): Promise<string> {
    const existing = await this.storage.get<string>("conversation:default")
    if (existing) return existing
    const legacy = await this.storage.get<string>(`session:primary:${this.primary}`)
    const sessionID = legacy ?? (await this.create("Talkt", "ses_talkt_default")).id
    await this.storage.put("conversation:default", sessionID)
    return sessionID
  }

  async select(sessionID: string): Promise<void> {
    await (await this.client()).sessions.get({ sessionID })
    await this.storage.put("conversation:default", sessionID)
  }

  async start(text: string, source: string, sessionID?: string, id: string = crypto.randomUUID()): Promise<Run> {
    const existing = await this.storage.get<Run>(`run:${id}`)
    if (existing) {
      if (existing.text !== text || (sessionID && existing.sessionID !== sessionID)) throw new Error("Run ID reused with a different prompt")
      if (existing.state === "queued") await this.dispatch(existing)
      return await this.storage.get<Run>(`run:${id}`) ?? existing
    }
    const selected = sessionID ?? await this.defaultSession()
    await (await this.client()).sessions.get({ sessionID: selected })
    const previous = await this.storage.get<string>(`conversation:run:${selected}`)
    if (previous) {
      const state = await this.status(previous)
      if (state.state === "queued" || state.state === "running") throw new Error("Conversation is busy. Stop the current run or start a new conversation.")
    }
    const run: Run = {
      id, sessionID: selected, messageID: `msg_${crypto.randomUUID().replaceAll("-", "")}`,
      text, source, created: Date.now(), fallback: false, state: "queued",
    }
    await this.storage.transaction(async (tx) => {
      const current = await tx.get<string>(`conversation:run:${selected}`)
      if (current !== previous) throw new Error("Conversation is busy")
      await tx.put(`run:${id}`, run)
      await tx.put(`conversation:run:${selected}`, id)
    })
    await this.dispatch(run)
    return await this.storage.get<Run>(`run:${id}`) ?? run
  }

  private async dispatch(run: Run) {
    const client = await this.client()
    const [context, inbox] = await Promise.all([
      client.sessions.context({ sessionID: run.sessionID }), client.sessions.inbox.list({ sessionID: run.sessionID }),
    ])
    if (!context.some((item) => item.id === run.messageID) && !inbox.some((item) => item.id === run.messageID)) {
      await client.sessions.prompt({ sessionID: run.sessionID, id: run.messageID, text: run.text, delivery: "queue", metadata: { source: run.source, runID: run.id } })
    }
    await this.storage.put(`run:${run.id}`, { ...run, state: "running" })
  }

  async status(id: string): Promise<Run> {
    let run = await this.storage.get<Run>(`run:${id}`)
    if (!run) throw new Error("Run not found")
    if (run.state === "completed" || run.state === "failed") return run
    if (run.state === "queued") await this.dispatch(run)
    const client = await this.client()
    const [active, inbox, context, session] = await Promise.all([
      client.sessions.active(), client.sessions.inbox.list({ sessionID: run.sessionID }),
      client.sessions.context({ sessionID: run.sessionID }), client.sessions.get({ sessionID: run.sessionID }),
    ])
    const messageID = run.messageID
    if (active[run.sessionID] || inbox.some((item) => item.id === messageID)) return { ...run, state: "running" }
    const start = context.findIndex((item) => item.id === messageID)
    const messages = start < 0 ? [] : context.slice(start + 1)
    const reply = messages.findLast((item) => item.type === "assistant")
    if (!reply || reply.type !== "assistant" || !reply.time.completed) {
      if (session.outcome !== "interrupted" && session.outcome !== "failed") return { ...run, state: "running" }
      run = { ...run, state: "failed", error: session.outcome === "interrupted" ? "Run stopped." : "Agent run failed." }
    } else if (reply.error) {
      const hadTools = messages.some((item) => item.type === "assistant" && item.content.some((part) => part.type === "tool"))
      if (!run.fallback && !hadTools && isFallbackEligible(new Error(JSON.stringify(reply.error)))) {
        await client.sessions.switchModel({ sessionID: run.sessionID, model: parseModelRef(this.fallback) })
        run = { ...run, fallback: true, messageID: `msg_${crypto.randomUUID().replaceAll("-", "")}`, state: "queued" }
        await this.storage.put(`run:${id}`, run)
        await this.dispatch(run)
        return run
      }
      run = { ...run, state: "failed", error: "The model could not complete this run. Check provider connection and agent logs." }
    } else {
      const response = reply.content.filter((part) => part.type === "text").map((part) => part.text).join("\n").trim()
      run = response ? { ...run, state: "completed", response } : { ...run, state: "failed", error: "The model returned no text." }
    }
    await this.storage.put(`run:${id}`, run)
    return run
  }

  async stop(sessionID: string) {
    const client = await this.client()
    for (const item of await client.sessions.inbox.list({ sessionID })) await client.sessions.inbox.cancel({ sessionID, inboxID: item.id })
    await client.sessions.interrupt({ sessionID, continue: false })
    const id = await this.storage.get<string>(`conversation:run:${sessionID}`)
    if (id) {
      const run = await this.storage.get<Run>(`run:${id}`)
      if (run && (run.state === "running" || run.state === "queued")) await this.storage.put(`run:${id}`, { ...run, state: "failed", error: "Run stopped." })
    }
  }
}
