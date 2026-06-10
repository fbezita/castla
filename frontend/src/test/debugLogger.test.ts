import { beforeEach, describe, expect, it, vi } from "vitest";

describe("debugLogger", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.useFakeTimers();
  });

  it("sends a frontend debug dump with buffered logs", async () => {
    const sent: Array<Record<string, unknown>> = [];
    const mod = await import("../utils/debugLogger");

    mod.setLoggingEnabled(true);
    mod.debugLog("first", { ok: true });

    mod.triggerDump(
      {
        control: {
          send(message: Record<string, unknown>) {
            sent.push(message);
          },
        },
      } as any,
      "unit_test",
    );

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe("debugDump");
    expect(sent[0].source).toBe("tesla");
    expect(Array.isArray(sent[0].logs)).toBe(true);
    expect((sent[0].logs as Array<{ message: string }>).some((entry) => entry.message === "first")).toBe(true);
  });
});
