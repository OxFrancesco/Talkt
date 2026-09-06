import { env } from "cloudflare:workers"
import { runInDurableObject } from "cloudflare:test"
import { describe, expect, it, vi } from "vitest"
import type { RunnerRequest, RunnerResult } from "../../shared/runner-protocol"
import { RunnerJobs } from "../src/runner-jobs"

vi.mock("@opencode-ai/sdk/workerd", () => ({
  create: async () => ({}),
  OpenCodeWorkerd: { create: async () => ({}) },
}))

const write: RunnerRequest = { kind: "exec", cwd: "/tmp", argv: ["gws", "gmail", "users", "messages", "send"] }
const success: RunnerResult = { kind: "success", data: "sent-once" }

function withJobs(run: (jobs: RunnerJobs, storage: DurableObjectStorage) => Promise<void>) {
  const stub = env.TALKT_AGENT.get(env.TALKT_AGENT.newUniqueId())
  return runInDurableObject(stub, async (_instance, state) => run(new RunnerJobs(state.storage), state.storage))
}

async function seed(storage: DurableObjectStorage, input: {
  id?: string
  deadline: number
  state: "queued" | "leased" | "completed"
}) {
  const base = { id: input.id ?? "job-one", created: 0, deadline: input.deadline, request: write }
  const job = input.state === "queued" ? { ...base, state: "queued" }
    : input.state === "leased" ? { ...base, state: "leased", runnerID: "mac-one", leaseToken: "lease-one" }
      : { ...base, state: "completed", runnerID: "mac-one", leaseToken: "lease-one", result: success }
  await storage.put(`runner:job:${base.id}`, job)
}

describe("durable runner expiry and replay", () => {
  it("returns a completed result after its deadline and rejects changed requests", async () => withJobs(async (jobs, storage) => {
    await seed(storage, { deadline: Date.now() - 1000, state: "completed" })
    expect(await jobs.execute("job-one", write)).toEqual(success)
    await expect(jobs.execute("job-one", { kind: "read_file", path: "/tmp/other" })).rejects.toThrow("different request")
  }))

  it("persists queued expiry and never leases the expired write", async () => withJobs(async (jobs, storage) => {
    await seed(storage, { deadline: Date.now() - 1000, state: "queued" })
    const expired = await jobs.execute("job-one", write)
    expect(expired).toMatchObject({ kind: "error", code: "runner_expired" })
    expect(await jobs.poll("mac-one")).toEqual({ job: null })
    expect(await new RunnerJobs(storage).execute("job-one", write)).toEqual(expired)
    expect(await storage.get("runner:job:job-one")).toMatchObject({ state: "expired", result: expired })
    await expect(jobs.complete({ id: "job-one", runnerID: "mac-one", leaseToken: "invented", result: success })).rejects.toThrow("lease does not match")
  }))

  it("expires abandoned queued jobs during poll without an active execute waiter", async () => withJobs(async (jobs, storage) => {
    await seed(storage, { deadline: Date.now() - 1000, state: "queued" })
    await seed(storage, { id: "fresh", deadline: Date.now() + 60_000, state: "queued" })
    expect((await jobs.poll("mac-two")).job?.id).toBe("fresh")
    expect(await storage.get("runner:job:job-one")).toMatchObject({ state: "expired" })
  }))

  it("keeps an expired lease with its original runner and accepts its late journal result", async () => withJobs(async (jobs, storage) => {
    await seed(storage, { deadline: Date.now() - 1000, state: "leased" })
    expect(await jobs.execute("job-one", write)).toMatchObject({ kind: "error", code: "runner_timeout" })
    expect(await jobs.poll("mac-two")).toEqual({ job: null })
    const recovered = (await new RunnerJobs(storage).poll("mac-one")).job
    expect(recovered).toEqual({ id: "job-one", request: write, leaseToken: "lease-one" })
    if (!recovered) throw new Error("Expected the same runner to recover its lease")
    await jobs.complete({ id: recovered.id, leaseToken: recovered.leaseToken, runnerID: "mac-one", result: success })
    expect(await jobs.execute("job-one", write)).toEqual(success)
    expect(await jobs.poll("mac-one")).toEqual({ job: null })
  }))

  it("accepts the original runner's interrupted-journal outcome without repeating the job", async () => withJobs(async (jobs, storage) => {
    await seed(storage, { deadline: Date.now() - 1000, state: "leased" })
    const unknown: RunnerResult = { kind: "error", code: "outcome_unknown", message: "Local journal records started work without a result" }
    await jobs.complete({ id: "job-one", runnerID: "mac-one", leaseToken: "lease-one", result: unknown })
    expect(await jobs.execute("job-one", write)).toEqual(unknown)
    expect(await jobs.poll("mac-two")).toEqual({ job: null })
  }))

  it("rejects the wrong runner and lease, and repeated completion preserves the first result", async () => withJobs(async (jobs, storage) => {
    await seed(storage, { deadline: Date.now() - 1000, state: "leased" })
    await expect(jobs.complete({ id: "job-one", runnerID: "mac-two", leaseToken: "lease-one", result: success })).rejects.toThrow("lease does not match")
    await expect(jobs.complete({ id: "job-one", runnerID: "mac-one", leaseToken: "wrong", result: success })).rejects.toThrow("lease does not match")
    await jobs.complete({ id: "job-one", runnerID: "mac-one", leaseToken: "lease-one", result: success })
    await jobs.complete({ id: "job-one", runnerID: "mac-one", leaseToken: "lease-one", result: { kind: "success", data: "different" } })
    expect(await jobs.execute("job-one", write)).toEqual(success)
  }))

  it("only leases a queued job once when two runners poll concurrently", async () => withJobs(async (jobs, storage) => {
    await seed(storage, { deadline: Date.now() + 60_000, state: "queued" })
    const responses = await Promise.all([jobs.poll("mac-one"), jobs.poll("mac-two")])
    expect(responses.filter((response) => response.job !== null)).toHaveLength(1)
  }))

  it("concurrent callers share one durable job and its first result", async () => withJobs(async (jobs) => {
    const first = jobs.execute("shared-job", write)
    const second = jobs.execute("shared-job", write)
    const leased = (await jobs.poll("mac-one")).job
    if (!leased) throw new Error("Expected a single queued job")
    expect(await jobs.poll("mac-two")).toEqual({ job: null })
    await jobs.complete({ id: leased.id, runnerID: "mac-one", leaseToken: leased.leaseToken, result: success })
    expect(await Promise.all([first, second])).toEqual([success, success])
  }))
})
