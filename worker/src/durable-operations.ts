type Operation = { action: string; input: string } & ({ state: "started" } | { state: "completed"; result: string })

export class DurableOperations {
  constructor(private readonly storage: DurableObjectStorage) {}

  async execute(id: string, action: string, input: unknown, execute: () => Promise<string>): Promise<string> {
    const serialized = JSON.stringify(input)
    const key = `operation:${id}`
    const previous = await this.storage.transaction(async tx => {
      const saved = await tx.get<Operation>(key)
      if (saved) {
        if (saved.action !== action || saved.input !== serialized) throw new Error("Operation ID reused for a different write")
        return saved
      }
      await tx.put(key, { action, input: serialized, state: "started" } satisfies Operation)
    })
    if (previous?.state === "completed") return previous.result
    if (previous) throw new Error("Write outcome unknown after interrupted execution. Check the provider before attempting another write.")
    const result = await execute()
    await this.storage.put(key, { action, input: serialized, state: "completed", result } satisfies Operation)
    return result
  }
}
