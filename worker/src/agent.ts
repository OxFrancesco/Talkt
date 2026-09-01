import { DurableObject } from "cloudflare:workers"
import type { OpenCode } from "@opencode-ai/sdk"
import { isFallbackEligible, parseModelRef } from "./domain"
import { RestartableResource } from "./restartable-resource"

type AgentReply = {
  readonly sessionID: string
  readonly text: string
  readonly provider: "openrouter"
  readonly fallback: boolean
}

export class TalktAgent extends DurableObject<Env> {
  private readonly opencode: RestartableResource<OpenCode.Interface>

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env)
    const createOpenCode = async () => {
      const { OpenCodeWorkerd } = await import("@opencode-ai/sdk/workerd")
      return OpenCodeWorkerd.create({
        storage: ctx.storage,
        config: {
          default_agent: "build",
          providers: {
            // "fast": ask OpenRouter to route to the highest-throughput provider.
            openrouter: { body: { provider: { sort: "throughput" } } },
          },
        },
      })
    }
    this.opencode = new RestartableResource(createOpenCode, ctx.blockConcurrencyWhile(createOpenCode))
  }

  async health(): Promise<{ readonly healthy: boolean; readonly version?: string }> {
    const client = await this.opencode.current()
    const health = await client.health.get()
    return { healthy: health.healthy, version: health.version }
  }

  async connectionStatus(): Promise<{ readonly connected: boolean; readonly label?: string }> {
    const client = await this.opencode.current()
    const result = await client.integration.get({ integrationID: "openrouter" })
    const connection = result.data?.connections[0]
    if (!connection) return { connected: false }
    return {
      connected: true,
      label: connection.type === "credential" ? connection.label : `env ${connection.name}`,
    }
  }

  async connectOpenRouter(key: string): Promise<{ readonly connected: boolean; readonly label?: string }> {
    const client = await this.opencode.current()
    await client.integration.connect.key({ integrationID: "openrouter", key, label: "Talkt" })
    return this.connectionStatus()
  }

  async prompt(text: string): Promise<AgentReply> {
    try {
      return await this.runAttempt(text, this.env.PRIMARY_MODEL, "primary")
    } catch (error) {
      if (!isFallbackEligible(error)) throw error
      console.warn(JSON.stringify({ level: "warn", message: "primary_fallback", error: String(error) }))
      return this.runAttempt(text, this.env.OPENROUTER_FALLBACK_MODEL, "fallback")
    }
  }

  private async runAttempt(text: string, modelName: string, role: "primary" | "fallback"): Promise<AgentReply> {
    const client = await this.opencode.current()
    const storageKey = `session:${role}:${modelName}`
    let sessionID = await this.ctx.storage.get<string>(storageKey)
    if (!sessionID) {
      const session = await client.sessions.create({
        title: role === "primary" ? "Talkt" : "Talkt fallback",
        agent: "build",
        model: parseModelRef(modelName),
        metadata: { source: "talkt-android", provider: "openrouter", role },
      })
      sessionID = session.id
      await this.ctx.storage.put(storageKey, sessionID)
    }

    await client.sessions.prompt({
      sessionID,
      text,
      metadata: { source: "voice", runID: crypto.randomUUID() },
    })
    await client.sessions.wait({ sessionID })
    const context = await client.sessions.context({ sessionID })
    const message = context.findLast((item) => item.type === "assistant")
    if (!message || message.type !== "assistant") throw new Error("OpenCode produced no assistant response")
    if (message.error) throw new Error(JSON.stringify(message.error))
    const responseText = message.content
      .filter((part) => part.type === "text")
      .map((part) => part.text)
      .join("\n")
      .trim()
    if (!responseText) throw new Error("OpenCode returned an empty response")
    return { sessionID, text: responseText, provider: "openrouter", fallback: role === "fallback" }
  }
}
