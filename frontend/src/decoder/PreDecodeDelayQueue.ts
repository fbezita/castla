type TimerHandle = ReturnType<typeof globalThis.setTimeout>;

/**
 * Delays compressed frames before they enter a hardware decoder.
 *
 * Holding decoded VideoFrames for A/V sync can exhaust the decoder's finite
 * output-buffer pool. Compressed frames are cheap to retain and do not block
 * the decoder, so latency belongs on this side of the decode boundary.
 */
export class PreDecodeDelayQueue<T> {
  private readonly timers = new Map<TimerHandle, T>();

  constructor(private readonly deliver: (value: T) => void) {}

  get size(): number {
    return this.timers.size;
  }

  enqueue(value: T, delayMs: number): void {
    if (delayMs <= 0) {
      this.deliver(value);
      return;
    }

    const timer = globalThis.setTimeout(() => {
      this.timers.delete(timer);
      this.deliver(value);
    }, delayMs);
    this.timers.set(timer, value);
  }

  clear(): number {
    const cleared = this.timers.size;
    this.timers.forEach((_value, timer) => globalThis.clearTimeout(timer));
    this.timers.clear();
    return cleared;
  }
}
