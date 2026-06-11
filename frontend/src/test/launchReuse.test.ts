import { describe, expect, it } from "vitest";
import { canReuseHotStream } from "../lib/launchReuse";

describe("canReuseHotStream", () => {
  it("does not reuse a stream after launch reset cleared the committed flag", () => {
    expect(
      canReuseHotStream(
        {
          pane: "secondary",
          width: 544,
          height: 704,
          committed: false,
          generation: 2,
          visible: true,
        },
        {
          type: "streamMetadata",
          sessionId: "secondary",
          generation: 2,
          firstFrameReady: true,
          streamReady: true,
          vdId: 9,
          width: 544,
          height: 704,
        },
        2,
      ),
    ).toBe(false);
  });

  it("allows reuse only when the existing committed viewport is still live", () => {
    expect(
      canReuseHotStream(
        {
          pane: "primary",
          width: 368,
          height: 704,
          committed: true,
          generation: 5,
          visible: true,
        },
        {
          type: "streamMetadata",
          sessionId: "primary",
          generation: 5,
          firstFrameReady: true,
          streamReady: true,
          vdId: 7,
          width: 368,
          height: 704,
        },
        5,
      ),
    ).toBe(true);
  });
});
