import { describe, expect, it } from "vitest";
import { isFreshCommittedViewport } from "../lib/streamCommitPolicy";

describe("isFreshCommittedViewport", () => {
  it("accepts newer generations regardless of backend policy", () => {
    expect(
      isFreshCommittedViewport(
        { committed: true, generation: 8 },
        7,
        true,
        false,
      ),
    ).toBe(true);
  });

  it("accepts same-generation recommits when explicitly allowed", () => {
    expect(
      isFreshCommittedViewport(
        { committed: true, generation: 7 },
        7,
        false,
        true,
      ),
    ).toBe(true);
  });

  it("rejects same-generation recommits when a fresh layout stream is required", () => {
    expect(
      isFreshCommittedViewport(
        { committed: true, generation: 7, width: 464 },
        7,
        false,
        false,
      ),
    ).toBe(false);
  });

  it("rejects committed streams whose width still matches the old layout", () => {
    expect(
      isFreshCommittedViewport(
        { committed: true, generation: 8, width: 1024 },
        7,
        false,
        false,
        464,
      ),
    ).toBe(false);
  });
});
