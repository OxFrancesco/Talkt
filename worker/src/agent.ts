import { DurableObject } from "cloudflare:workers"
import type { OpenCode } from "@opencode-ai/sdk"
import { RestartableResource } from "./restartable-resource"
import { ConversationRuns } from "./conversation-runs"
import { DurableOperations } from "./durable-operations"
import { ToolApprovals } from "./tool-approvals"
import { RunnerJobs } from "./runner-jobs"
import type { RunnerRequest, RunnerSubmission } from "../../shared/runner-protocol"
import rawProfile from "./generated/personal-profile.json"
import { parsePersonalProfile } from "./profile-types"

const profile = parsePersonalProfile(rawProfile)

export class TalktAgent extends DurableObject<Env> {
  private readonly opencode: RestartableResource<OpenCode.Interface>
  private readonly runs: ConversationRuns
  private readonly jobs: RunnerJobs
  private readonly approvals: ToolApprovals
  private readonly personal: boolean

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env)
    this.jobs = new RunnerJobs(ctx.storage)
    this.approvals = new ToolApprovals(ctx.storage)
    this.personal = ctx.id.equals(env.TALKT_AGENT.idFromName("owner"))
    const createOpenCode = async () => {
      const { create } = await import("@opencode-ai/sdk/workerd")
      if (!this.personal) return create({ storage: ctx.storage, config: { default_agent: "build", providers: { openrouter: { body: { provider: { sort: "throughput" } } } } } })
      const [{ personalProfilePlugin }, { nativeConnectorsPlugin, nativeRemoteServersSchema }, { runnerPlugin }, { approvalPlugin }] = await Promise.all([
        import("./personal-profile"), import("./native-connectors"), import("./runner-plugin"), import("./approval-plugin"),
      ])
      const native = {
        secrets: this.nativeSecrets(),
        durableWrite: (id: string, action: string, input: unknown, execute: () => Promise<string>) => new DurableOperations(ctx.storage).execute(id, action, input, execute),
        remoteServers: nativeRemoteServersSchema.parse(JSON.parse(env.NATIVE_MCP_SERVERS || "[]")),
        ...(env.TALKT_RUNNER_TOKEN ? { googleRunner: async (request: { command: "gws" | "gog"; args: string[] }, context: { sessionID: string; id: string }) => {
          return JSON.stringify(await this.jobs.execute(`${context.sessionID}:${context.id}`, { kind: "exec", argv: [request.command, ...request.args], cwd: env.PERSONAL_WORKSPACE }))
        } } : {}),
      }
      return create({
        storage: ctx.storage,
        config: { default_agent: "build", providers: { openrouter: { body: { provider: { sort: "throughput" } } } } },
        plugins: this.personal ? [
          approvalPlugin(this.approvals),
          personalProfilePlugin(profile, { readFile: async (path) => {
            const result = await this.jobs.execute(crypto.randomUUID(), { kind: "read_file", path })
            if (result.kind === "error") throw new Error(result.message)
            if (typeof result.data === "string") return result.data
            if (typeof result.data === "object" && result.data !== null && "text" in result.data && typeof result.data.text === "string") return result.data.text
            throw new Error("Runner returned an invalid file result")
          } }),
          nativeConnectorsPlugin(native),
          ...(env.TALKT_RUNNER_TOKEN ? [runnerPlugin((id, request) => this.jobs.execute(id, request))] : []),
        ] : [],
      })
    }
    this.opencode = new RestartableResource(createOpenCode, createOpenCode())
    this.runs = new ConversationRuns(ctx.storage, () => this.opencode.current(), env.PRIMARY_MODEL, env.OPENROUTER_FALLBACK_MODEL)
  }

  private nativeSecrets() {
    return { NATIVE_LINEAR_TOKEN: this.env.NATIVE_LINEAR_TOKEN, NATIVE_GITHUB_TOKEN: this.env.NATIVE_GITHUB_TOKEN, NATIVE_NOTION_TOKEN: this.env.NATIVE_NOTION_TOKEN, NATIVE_CLOUDFLARE_TOKEN: this.env.NATIVE_CLOUDFLARE_TOKEN }
  }

  async health() { return (await this.opencode.current()).health.get() }

  async connectionStatus() {
    const result = await (await this.opencode.current()).integration.get({ integrationID: "openrouter" })
    const connection = result.data?.connections.find(connection => connection.type === "credential")
    if (!connection) return { connected: false }
    return { connected: true, label: connection.label }
  }

  async connectOpenRouter(key: string) {
    await (await this.opencode.current()).integration.connect.key({ integrationID: "openrouter", key, label: "Talkt" })
    return this.connectionStatus()
  }

  async capabilities() {
    const { nativeConnectorStatus } = await import("./native-connectors")
    const client = await this.opencode.current()
    if (!this.personal) return { personal: false as const, conversationID: await this.runs.defaultSession() }
    const runner = await this.jobs.status()
    return {
      personal: true as const,
      profile: { skills: profile.skills.length, mcps: profile.mcps.length, clis: profile.clis.length, plugins: profile.plugins.length, instructions: Boolean(profile.instructions), gaps: profile.gaps },
      runner,
      connectors: nativeConnectorStatus({ secrets: this.nativeSecrets(), ...(this.env.TALKT_RUNNER_TOKEN ? { googleRunner: async () => runner.online ? "online" : "offline" } : {}) }),
      mcp: await client.mcp.list(),
      conversationID: await this.runs.defaultSession(),
    }
  }

  async conversations(): Promise<{ data: Array<{ id: string; title?: string }> }> {
    const result = await (await this.opencode.current()).sessions.list({ limit: 50 })
    return { data: result.data.map(({ id, title }) => ({ id, ...(title ? { title } : {}) })) }
  }
  async createConversation(title?: string, id?: string): Promise<{ id: string; title?: string }> {
    const session = await this.runs.create(title, id)
    return { id: session.id, ...(session.title ? { title: session.title } : {}) }
  }
  async selectConversation(sessionID: string) { return this.runs.select(sessionID) }
  async startRun(text: string, source: string, sessionID?: string, id?: string) { return this.runs.start(text, source, sessionID, id) }
  async runStatus(id: string) { return this.runs.status(id) }
  async stopConversation(sessionID?: string) {
    const selected = sessionID ?? await this.runs.defaultSession()
    await this.approvals.rejectSession(selected)
    return this.runs.stop(selected)
  }
  async permissions(sessionID: string): Promise<Array<{ id: string; action: string; resources: string[]; message?: string }>> {
    const pending = await (await this.opencode.current()).permission.list({ sessionID })
    return [...await this.approvals.pending(sessionID), ...pending.map(({ id, action, resources, message }) => ({ id, action, resources, ...(message ? { message } : {}) }))]
  }
  async replyPermission(sessionID: string, requestID: string, reply: "once" | "always" | "reject") {
    if (requestID.startsWith("tool_")) return this.approvals.reply(sessionID, requestID, reply)
    return (await this.opencode.current()).permission.reply({ sessionID, requestID, reply })
  }
  async conversationContext(sessionID: string): Promise<string> { return JSON.stringify(await (await this.opencode.current()).sessions.context({ sessionID })) }

  async prompt(text: string, sessionID?: string) {
    let run = await this.runs.start(text, "app", sessionID)
    const deadline = Date.now() + 20_000
    while (run.state === "queued" || run.state === "running") {
      const approvals = await this.permissions(run.sessionID)
      if (approvals.length || Date.now() >= deadline) {
        const text = approvals.length
          ? `Action approval required. Open @TalktFrancescoBot and send /status to review it. Run ${run.id} is waiting.`
          : `Your agent is still working. Run ${run.id}. Open @TalktFrancescoBot and send /result ${run.id} for the result.`
        return { pending: true, runID: run.id, sessionID: run.sessionID, text, provider: "openrouter", fallback: run.fallback }
      }
      await scheduler.wait(500)
      run = await this.runs.status(run.id)
    }
    if (run.state === "failed") throw new Error(run.error)
    return { pending: false, runID: run.id, sessionID: run.sessionID, text: run.response, provider: "openrouter", fallback: run.fallback }
  }

  async runnerPoll(runnerID: string) { return this.jobs.poll(runnerID) }
  async runnerResult(input: RunnerSubmission) { return this.jobs.complete(input) }
  async runnerExecute(id: string, request: RunnerRequest) { return this.jobs.execute(id, request) }
}
