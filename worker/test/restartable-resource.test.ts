import { describe, expect, it, vi } from "vitest"
import { RestartableResource } from "../src/restartable-resource"

type FakeOpenCode = {
  readonly authenticated: boolean
  readonly close: () => Promise<void>
}

describe("RestartableResource", () => {
  it("rebuilds an OpenCode client after OAuth completes", async () => {
    const close = vi.fn(async () => undefined)
    const create = vi
      .fn<() => Promise<FakeOpenCode>>()
      .mockResolvedValueOnce({ authenticated: false, close })
      .mockResolvedValueOnce({ authenticated: true, close })
    const runtime = new RestartableResource(create)

    expect((await runtime.current()).authenticated).toBe(false)

    await runtime.restart()

    expect(close).toHaveBeenCalledOnce()
    expect(create).toHaveBeenCalledTimes(2)
    expect((await runtime.current()).authenticated).toBe(true)
  })
})
