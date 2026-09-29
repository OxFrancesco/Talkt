import { DurableObject } from "cloudflare:workers"

// Keep deployed namespaces and their saved data without running legacy agent jobs.
export class TalktAgent extends DurableObject<Env> {
  async alarm(): Promise<void> { await this.ctx.storage.deleteAlarm() }
  async fetch(): Promise<Response> { return new Response("Agent features have been removed", { status: 410 }) }
}

export class TelegramChannel extends TalktAgent {}
