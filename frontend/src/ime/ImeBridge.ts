import type { ControlTransport } from "../transport/ControlTransport";

const COMPOSITION_INPUT_SUPPRESS_MS = 100;

export class ImeBridge {
  private composing = false;
  private previousComposition = "";
  private justFinishedComposition = false;
  private compositionTimeout: number | null = null;
  private compositionId = 0;
  private lastCommittedCompositionId = -1;

  constructor(private readonly control: ControlTransport) {}

  compositionStart(_event: CompositionEvent): void {
    this.composing = true;
    this.previousComposition = "";
    this.justFinishedComposition = false;
    this.compositionId++;
    if (this.compositionTimeout) {
      window.clearTimeout(this.compositionTimeout);
      this.compositionTimeout = null;
    }
  }

  compositionUpdate(event: CompositionEvent): void {
    const text = event.data ?? "";
    const payload = {
      type: "ime",
      op: "setComposingText",
      text,
      replaceChars: [...this.previousComposition].length,
    };
    console.warn("[IME_SEND] compositionUpdate", payload);
    this.control.send(payload);
    this.previousComposition = text;
  }

  compositionEnd(event: CompositionEvent): void {
    this.composing = false;
    const text = event.data ?? this.previousComposition;
    if (this.lastCommittedCompositionId !== this.compositionId) {
      const commitPayload = { type: "ime", op: "commitText", text };
      const finishPayload = { type: "ime", op: "finishComposingText" };
      console.warn("[IME_SEND] compositionEnd: commitText", commitPayload);
      this.control.send(commitPayload);
      console.warn("[IME_SEND] compositionEnd: finishComposingText", finishPayload);
      this.control.send(finishPayload);
      this.lastCommittedCompositionId = this.compositionId;
    }
    this.previousComposition = "";

    // Clear target textarea content immediately to prevent subsequent stale input events
    const input = event.target as HTMLTextAreaElement;
    if (input) {
      input.value = "";
    }

    // Set a temporary guard to ignore redundant 'input' events immediately following compositionend
    this.justFinishedComposition = true;
    if (this.compositionTimeout) {
      window.clearTimeout(this.compositionTimeout);
    }
    this.compositionTimeout = window.setTimeout(() => {
      this.justFinishedComposition = false;
    }, COMPOSITION_INPUT_SUPPRESS_MS);
  }

  input(event: Event): void {
    if (this.composing || this.justFinishedComposition) {
      if (this.justFinishedComposition) {
        console.log("[IME_SUPPRESS] stale input ignored after compositionend");
      }
      return;
    }
    const input = event.target as HTMLTextAreaElement;
    if (!input.value) return;
    const payload = { type: "ime", op: "commitText", text: input.value };
    console.warn("[IME_SEND] input: commitText", payload);
    this.control.send(payload);
    input.value = "";
  }

  keydown(event: KeyboardEvent): void {
    if (event.key === "Backspace" && !this.composing) {
      const payload = {
        type: "ime",
        op: "deleteSurroundingText",
        beforeLength: 1,
        afterLength: 0,
      };
      console.warn("[IME_SEND] keydown: deleteSurroundingText", payload);
      this.control.send(payload);
      event.preventDefault();
    } else if (event.key === "Enter") {
      event.preventDefault();
      if (
        this.composing &&
        this.previousComposition &&
        this.lastCommittedCompositionId !== this.compositionId
      ) {
        const commitPayload = {
          type: "ime",
          op: "commitText",
          text: this.previousComposition,
        };
        const finishPayload = { type: "ime", op: "finishComposingText" };
        console.warn("[IME_SEND] keydown Enter: commitText", commitPayload);
        this.control.send(commitPayload);
        console.warn("[IME_SEND] keydown Enter: finishComposingText", finishPayload);
        this.control.send(finishPayload);
        this.lastCommittedCompositionId = this.compositionId;
        this.composing = false;
      }
      const enterPayload = { type: "ime", op: "sendKeyEvent", keyCode: 66 };
      console.warn("[IME_SEND] keydown Enter: sendKeyEvent", enterPayload);
      this.control.send(enterPayload);
    }
  }
}
