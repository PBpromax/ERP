export function createLatestRequest() {
  let generation = 0
  let controller: AbortController | undefined

  function cancel() {
    generation++
    controller?.abort()
    controller = undefined
  }

  async function run<T>(request: (signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
    cancel()
    const current = generation
    const active = new AbortController()
    controller = active
    try {
      const result = await request(active.signal)
      return current === generation ? result : undefined
    } catch (error) {
      if (active.signal.aborted || current !== generation) return undefined
      throw error
    } finally {
      if (current === generation) controller = undefined
    }
  }

  return { run, cancel }
}
