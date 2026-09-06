import { env, runInDurableObject } from "cloudflare:test"
import { describe, expect, it, vi } from "vitest"
import { DurableOperations } from "../src/durable-operations"
import { ToolApprovals } from "../src/tool-approvals"

vi.mock("@opencode-ai/sdk/workerd", () => ({ create: async () => ({}) }))

function withApprovals(run: (approvals: ToolApprovals, storage: DurableObjectStorage) => Promise<void>) {
  const stub = env.TALKT_AGENT.get(env.TALKT_AGENT.newUniqueId())
  return runInDurableObject(stub, async (_instance, state) => run(new ToolApprovals(state.storage), state.storage))
}

describe("exact personal tool approval", () => {
  it("does not release execution until the same session approves exact arguments", async () => withApprovals(async approvals => {
    const input = { cwd: "/tmp/project", argv: ["git", "status"] }
    let executed = false
    const task = approvals.require("session-a", "call-a", "personal_exec", input).then(() => { executed = true })
    await vi.waitFor(async () => expect(await approvals.pending("session-a")).toHaveLength(1))
    const [pending] = await approvals.pending("session-a")
    expect(pending?.message).toContain(JSON.stringify(input))
    expect(executed).toBe(false)
    expect(await approvals.pending("session-b")).toEqual([])
    await expect(approvals.reply("session-b", pending!.id, "once")).rejects.toThrow("not found")
    await expect(approvals.reply("session-a", pending!.id, "always")).rejects.toThrow("exact action")
    expect(executed).toBe(false)
    await approvals.reply("session-a", pending!.id, "once")
    await task
    expect(executed).toBe(true)
    await expect(approvals.require("session-a", "call-a", "personal_exec", { ...input, argv: ["git", "push"] })).rejects.toThrow("different action")
  }))

  it("denies waiting tools when their conversation stops", async () => withApprovals(async approvals => {
    const task = approvals.require("session", "call", "personal_mcp_call", { server: "linear", tool: "create_issue", arguments: { title: "draft" } })
    const rejected = expect(task).rejects.toThrow("Action denied")
    await vi.waitFor(async () => expect(await approvals.pending("session")).toHaveLength(1))
    await approvals.rejectSession("session")
    await rejected
  }))
})

it("returns saved native writes and never repeats ambiguous writes after restart", async () => withApprovals(async (_approvals, storage) => {
  const first = new DurableOperations(storage)
  let calls = 0
  const write = async () => { calls++; return "page-created" }
  expect(await first.execute("write-one", "notion", { title: "test" }, write)).toBe("page-created")
  const restarted = new DurableOperations(storage)
  expect(await restarted.execute("write-one", "notion", { title: "test" }, write)).toBe("page-created")
  expect(calls).toBe(1)
  await expect(restarted.execute("write-one", "notion", { title: "changed" }, write)).rejects.toThrow("different write")
  await expect(first.execute("ambiguous", "notion", {}, async () => { calls++; throw new Error("response lost") })).rejects.toThrow("response lost")
  await expect(restarted.execute("ambiguous", "notion", {}, write)).rejects.toThrow("outcome unknown")
  expect(calls).toBe(2)
}))
