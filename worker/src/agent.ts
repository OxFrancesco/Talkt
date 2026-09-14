import { Pigent } from "pigent"
import { parsePersonalProfile } from "pigent/profile"
import { nativeRemoteServersSchema } from "pigent/connectors"
import rawProfile from "./generated/personal-profile.json"
import { chatGPTRelayPlugin } from "./chatgpt-relay-plugin"
import { publicKey } from "./chatgpt-relay-public.json"

const profile = parsePersonalProfile(rawProfile)

export class TalktAgent extends Pigent<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    const personal = ctx.id.equals(env.TALKT_AGENT.idFromName("owner"))
    super(ctx, env, {
      primaryModel: env.PRIMARY_MODEL,
      fallbackModel: env.OPENROUTER_FALLBACK_MODEL,
      identity: { title: "Talkt", source: "talkt", defaultSessionID: "ses_talkt_default", chatGPTLabel: "Talkt ChatGPT" },
      ...(personal ? { personal: {
        plugins: [chatGPTRelayPlugin({
          publicKey,
          online: async () => { const status = await this.capabilities(); return status.personal && status.runner.online },
          execute: (id, tool, arguments_) => this.runnerExecute(id, { kind: "mcp_call", server: "talkt_chatgpt_relay", tool, arguments: arguments_ }),
        })],
        profile,
        secrets: { NATIVE_LINEAR_TOKEN: env.NATIVE_LINEAR_TOKEN, NATIVE_GITHUB_TOKEN: env.NATIVE_GITHUB_TOKEN, NATIVE_NOTION_TOKEN: env.NATIVE_NOTION_TOKEN, NATIVE_CLOUDFLARE_TOKEN: env.NATIVE_CLOUDFLARE_TOKEN },
        remoteServers: nativeRemoteServersSchema.parse(JSON.parse(env.NATIVE_MCP_SERVERS || "[]")),
        ...(env.TALKT_RUNNER_TOKEN ? { runner: { workspace: env.PERSONAL_WORKSPACE } } : {}),
      } } : {}),
    })
  }

  async prompt(text: string, sessionID?: string) {
    let run = await this.startRun(text, "app", sessionID)
    const deadline = Date.now() + 20_000
    while (run.state === "queued" || run.state === "running") {
      const approvals = await this.permissions(run.sessionID)
      if (approvals.length || Date.now() >= deadline) {
        const text = approvals.length
          ? `Action approval required. Open @TalktFrancescoBot and send /status to review it. Run ${run.id} is waiting.`
          : `Your agent is still working. Run ${run.id}. Open @TalktFrancescoBot and send /result ${run.id} for the result.`
        return { pending: true, runID: run.id, sessionID: run.sessionID, text, provider: run.model?.providerID ?? "openrouter", fallback: run.fallback }
      }
      await scheduler.wait(500)
      run = await this.runStatus(run.id)
    }
    if (run.state === "failed") throw new Error(run.error)
    return { pending: false, runID: run.id, sessionID: run.sessionID, text: run.response, provider: run.model?.providerID ?? "openrouter", fallback: run.fallback }
  }

}
