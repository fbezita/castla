import { describe, expect, it } from "vitest";
import { shouldLockExplicitLayoutTargets } from "../lib/layoutTargetLock";

describe("shouldLockExplicitLayoutTargets", () => {
  it("locks while a layout transition is active", () => {
    expect(
      shouldLockExplicitLayoutTargets({
        resizingSplit: false,
        popupInteracting: false,
        layoutTransitionActive: true,
        frozenLayoutActive: false,
      }),
    ).toBe(true);
  });

  it("locks while the barrier is frozen", () => {
    expect(
      shouldLockExplicitLayoutTargets({
        resizingSplit: false,
        popupInteracting: false,
        layoutTransitionActive: false,
        frozenLayoutActive: true,
      }),
    ).toBe(true);
  });

  it("does not keep split targets locked after launch has settled", () => {
    expect(
      shouldLockExplicitLayoutTargets({
        resizingSplit: false,
        popupInteracting: false,
        layoutTransitionActive: false,
        frozenLayoutActive: false,
      }),
    ).toBe(false);
  });

  it("unlocks during an active splitbar drag", () => {
    expect(
      shouldLockExplicitLayoutTargets({
        resizingSplit: true,
        popupInteracting: false,
        layoutTransitionActive: true,
        frozenLayoutActive: true,
      }),
    ).toBe(false);
  });
});
