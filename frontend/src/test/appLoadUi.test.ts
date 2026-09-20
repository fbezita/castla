import { describe, expect, it } from "vitest";
import { appLoadFailureKey } from "../lib/appLoadUi";

describe("appLoadFailureKey", () => {
  it("never exposes the raw parsing error to the launcher", () => {
    expect(appLoadFailureKey(new SyntaxError("Unexpected token '<'"))).toBe(
      "serverUnavailable",
    );
  });

  it("uses the same actionable guidance for HTTP failures", () => {
    expect(appLoadFailureKey(new Error("apps 503"))).toBe("serverUnavailable");
  });
});
