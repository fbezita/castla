import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PreDecodeDelayQueue } from "../decoder/PreDecodeDelayQueue";

describe("PreDecodeDelayQueue", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("delivers immediately when latency is zero", () => {
    const delivered: number[] = [];
    const queue = new PreDecodeDelayQueue<number>((value) => delivered.push(value));

    queue.enqueue(1, 0);

    expect(delivered).toEqual([1]);
    expect(queue.size).toBe(0);
  });

  it("keeps compressed frames queued until their delay expires", () => {
    const delivered: number[] = [];
    const queue = new PreDecodeDelayQueue<number>((value) => delivered.push(value));

    queue.enqueue(1, 300);
    queue.enqueue(2, 300);
    vi.advanceTimersByTime(299);
    expect(delivered).toEqual([]);

    vi.advanceTimersByTime(1);
    expect(delivered).toEqual([1, 2]);
    expect(queue.size).toBe(0);
  });

  it("cancels queued frames when latency or stream state changes", () => {
    const delivered: number[] = [];
    const queue = new PreDecodeDelayQueue<number>((value) => delivered.push(value));
    queue.enqueue(1, 300);
    queue.enqueue(2, 300);

    expect(queue.clear()).toBe(2);
    vi.advanceTimersByTime(300);

    expect(delivered).toEqual([]);
    expect(queue.size).toBe(0);
  });
});
