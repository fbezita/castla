import { describe, expect, it } from "vitest";

import { resolveEmbeddedUiScale } from "../utils/embeddedUiScale";

describe("resolveEmbeddedUiScale", () => {
  it("keeps desktop browsers at default scale", () => {
    expect(
      resolveEmbeddedUiScale({
        userAgent:
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36",
        viewportWidth: 1440,
        viewportHeight: 900,
      }),
    ).toEqual({
      isEmbeddedAutomotive: false,
      scale: 1,
    });
  });

  it("enlarges UI for Tesla-class automotive browsers on wide displays", () => {
    expect(
      resolveEmbeddedUiScale({
        userAgent:
          "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Tesla QtCarBrowser/6.2 Safari/537.36",
        viewportWidth: 1280,
        viewportHeight: 720,
      }),
    ).toEqual({
      isEmbeddedAutomotive: true,
      scale: 1.25,
    });
  });

  it("uses a milder bump for smaller embedded browser panes", () => {
    expect(
      resolveEmbeddedUiScale({
        userAgent:
          "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 QtWebEngine/5.15 Safari/537.36",
        viewportWidth: 1024,
        viewportHeight: 600,
      }),
    ).toEqual({
      isEmbeddedAutomotive: true,
      scale: 1.15,
    });
  });
});
