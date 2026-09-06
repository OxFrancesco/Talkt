import type { RunnerJob, RunnerRequest, RunnerResult, RunnerSubmission } from "../../shared/runner-protocol"

type Job = {
  id: string
  request: RunnerRequest
  created: number
  deadline: number
} & (
  | { state: "queued" }
  | { state: "expired"; result: RunnerResult }
  | { state: "leased"; runnerID: string; leaseToken: string }
  | { state: "completed"; runnerID: string; leaseToken: string; result: RunnerResult }
)

const queuedExpiry: RunnerResult = {
  kind: "error", code: "runner_expired", message: "The Mac runner did not start this job before its deadline. This job is expired and will not execute.",
}
const leasedTimeout: RunnerResult = {
  kind: "error", code: "runner_timeout", message: "The Mac runner did not finish in time. A started command may still be running; do not repeat a write without checking its result.",
}

export class RunnerJobs {
  constructor(private readonly storage: DurableObjectStorage) {}

  async execute(id: string, request: RunnerRequest): Promise<RunnerResult> {
    await this.storage.transaction(async (tx) => {
      const job = await tx.get<Job>(`runner:job:${id}`)
      if (job) {
        if (JSON.stringify(job.request) !== JSON.stringify(request)) throw new Error("Runner job ID reused with a different request")
        return
      }
      const created = Date.now()
      await tx.put(`runner:job:${id}`, { id, request, created, deadline: created + 180_000, state: "queued" } satisfies Job)
    })
    while (true) {
      const result = await this.storage.transaction(async (tx): Promise<RunnerResult | undefined> => {
        const job = await tx.get<Job>(`runner:job:${id}`)
        if (!job) throw new Error("Runner job disappeared from durable storage")
        if (job.state === "completed" || job.state === "expired") return job.result
        if (Date.now() < job.deadline) return
        if (job.state === "leased") return leasedTimeout
        await tx.put(`runner:job:${id}`, { ...job, state: "expired", result: queuedExpiry } satisfies Job)
        return queuedExpiry
      })
      if (result) return result
      await scheduler.wait(500)
    }
  }

  async poll(runnerID: string): Promise<{ job: RunnerJob | null }> {
    await this.storage.put("runner:lastSeen", Date.now())
    return this.storage.transaction(async (tx) => {
      const jobs = await tx.list<Job>({ prefix: "runner:job:" })
      const outstanding: Job[] = []
      const now = Date.now()
      for (const job of jobs.values()) {
        if (job.state === "queued" && job.deadline <= now) {
          await tx.put(`runner:job:${job.id}`, { ...job, state: "expired", result: queuedExpiry } satisfies Job)
        } else if (job.state === "queued" || job.state === "leased") outstanding.push(job)
      }
      const existing = outstanding.find((job) => job.state === "leased" && job.runnerID === runnerID)
      if (existing?.state === "leased") return { job: { id: existing.id, request: existing.request, leaseToken: existing.leaseToken } }
      const next = outstanding.filter((job) => job.state === "queued").sort((a, b) => a.created - b.created)[0]
      if (!next) return { job: null }
      const leaseToken = crypto.randomUUID()
      await tx.put(`runner:job:${next.id}`, { ...next, state: "leased", runnerID, leaseToken })
      return { job: { id: next.id, request: next.request, leaseToken } }
    })
  }

  async complete(input: RunnerSubmission): Promise<void> {
    await this.storage.transaction(async (tx) => {
      const job = await tx.get<Job>(`runner:job:${input.id}`)
      if (!job || job.state === "queued" || job.state === "expired" || job.runnerID !== input.runnerID || job.leaseToken !== input.leaseToken) {
        throw new Error("Runner lease does not match")
      }
      if (job.state === "completed") return
      await tx.put(`runner:job:${input.id}`, { ...job, state: "completed", result: input.result })
    })
  }

  async status() {
    const lastSeen = await this.storage.get<number>("runner:lastSeen")
    return { online: lastSeen !== undefined && Date.now() - lastSeen < 30_000, lastSeen: lastSeen ?? null }
  }
}
