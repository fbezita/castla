import { describe, expect, it } from "vitest";
import { resolveAppStreamStoppedUi } from "../lib/appStreamStoppedUi";

describe("app stream stopped UI policy", () => {
  it("shows the home overlay and opens the drawer when the only primary app leaves", () => {
    expect(
      resolveAppStreamStoppedUi(
        { type: "APP_STREAM_STOPPED", pane: "primary" },
        false,
      ),
    ).toEqual({ openDrawer: true, showHome: true, pane: "primary" });
  });

  it("does not cover a remaining secondary app with the full home overlay", () => {
    expect(
      resolveAppStreamStoppedUi(
        { type: "APP_STREAM_STOPPED", pane: "primary" },
        true,
      ),
    ).toEqual({ openDrawer: true, showHome: false, pane: "primary" });
  });

  it("opens the drawer without covering the primary app when the secondary app leaves", () => {
    expect(
      resolveAppStreamStoppedUi(
        { type: "APP_STREAM_STOPPED", pane: "secondary" },
        false,
      ),
    ).toEqual({ openDrawer: true, showHome: false, pane: "secondary" });
  });

  it("ignores unrelated control messages", () => {
    expect(resolveAppStreamStoppedUi({ type: "streamMetadata" }, false)).toBeNull();
  });
});
