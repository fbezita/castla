import { describe, expect, it } from "vitest";
import { fingerprintSources } from "../../buildFingerprint";

describe("frontend source fingerprint", () => {
  it("is stable when the source entries are provided in a different order", () => {
    const first = fingerprintSources([
      { path: "src/App.svelte", content: "app" },
      { path: "package.json", content: "package" },
    ]);
    const second = fingerprintSources([
      { path: "package.json", content: "package" },
      { path: "src/App.svelte", content: "app" },
    ]);

    expect(second).toBe(first);
  });

  it("changes only when a source path or its content changes", () => {
    const original = fingerprintSources([
      { path: "src/App.svelte", content: "before" },
    ]);
    const contentChanged = fingerprintSources([
      { path: "src/App.svelte", content: "after" },
    ]);
    const pathChanged = fingerprintSources([
      { path: "src/Other.svelte", content: "before" },
    ]);

    expect(contentChanged).not.toBe(original);
    expect(pathChanged).not.toBe(original);
  });
});
