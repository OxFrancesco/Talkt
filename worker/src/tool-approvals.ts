export type ToolApproval = {
  id: string
  sessionID: string
  action: string
  input: string
  created: number
  expires: number
  decision: "pending" | "once" | "reject"
}

export class ToolApprovals {
  constructor(private readonly storage: DurableObjectStorage) {}

  async require(sessionID: string, callID: string, action: string, input: unknown): Promise<void> {
    const id = `tool_${Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${sessionID}:${callID}`)))).map(byte => byte.toString(16).padStart(2, "0")).join("").slice(0, 32)}`
    const serialized = JSON.stringify(input)
    const key = `approval:${id}`
    await this.storage.transaction(async tx => {
      const saved = await tx.get<ToolApproval>(key)
      if (saved) {
        if (saved.action !== action || saved.input !== serialized || saved.sessionID !== sessionID) throw new Error("Approval call ID reused for a different action")
        return
      }
      await tx.put(key, { id, sessionID, action, input: serialized, created: Date.now(), expires: Date.now() + 600_000, decision: "pending" } satisfies ToolApproval)
    })
    while (true) {
      const approval = await this.storage.get<ToolApproval>(key)
      if (!approval || approval.decision === "reject" || Date.now() >= approval.expires) throw new Error("Action denied or approval expired. Nothing was executed.")
      if (approval.decision === "once") return
      await scheduler.wait(500)
    }
  }

  async pending(sessionID: string) {
    const approvals = await this.storage.list<ToolApproval>({ prefix: "approval:" })
    return [...approvals.values()].filter(item => item.sessionID === sessionID && item.decision === "pending" && item.expires > Date.now()).map(item => ({
      id: item.id, action: item.action, resources: [item.input], message: `${item.action}\n${item.input}`,
    }))
  }

  async reply(sessionID: string, requestID: string, decision: "once" | "always" | "reject") {
    if (decision === "always") throw new Error("Personal tool approvals apply only to this exact action. Use once.")
    await this.storage.transaction(async tx => {
      const key = `approval:${requestID}`
      const approval = await tx.get<ToolApproval>(key)
      if (!approval || approval.sessionID !== sessionID || approval.expires <= Date.now()) throw new Error("Approval not found or expired")
      if (approval.decision !== "pending" && approval.decision !== decision) throw new Error("Approval already decided")
      await tx.put(key, { ...approval, decision })
    })
  }

  async rejectSession(sessionID: string) {
    const approvals = await this.storage.list<ToolApproval>({ prefix: "approval:" })
    for (const approval of approvals.values()) {
      if (approval.sessionID === sessionID && approval.decision === "pending") await this.storage.put(`approval:${approval.id}`, { ...approval, decision: "reject" })
    }
  }
}
