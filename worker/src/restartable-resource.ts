type Closable = {
  readonly close: () => Promise<void>
}

export class RestartableResource<T extends Closable> {
  private resource: Promise<T>

  constructor(
    private readonly create: () => Promise<T>,
    initial: Promise<T> = create(),
  ) {
    this.resource = initial
  }

  current(): Promise<T> {
    return this.resource
  }

  restart(): Promise<T> {
    const next = this.resource.then(async (current) => {
      await current.close()
      return this.create()
    })
    this.resource = next
    return next
  }
}
