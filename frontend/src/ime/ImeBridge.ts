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
    this.control.send({
      type: "ime",
      op: "setComposingText",
      text,
      replaceChars: [...this.previousComposition].length,
    });
    this.previousComposition = text;
  }

  compositionEnd(event: CompositionEvent): void {
    this.composing = false;
    const text = event.data ?? this.previousComposition;
    if (this.lastCommittedCompositionId !== this.compositionId) {
      this.control.send({ type: "ime", op: "commitText", text });
      this.control.send({ type: "ime", op: "finishComposingText" });
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
    this.control.send({ type: "ime", op: "commitText", text: input.value });
    input.value = "";
  }

  keydown(event: KeyboardEvent): void {
    if (event.key === "Backspace" && !this.composing) {
      this.control.send({
        type: "ime",
        op: "deleteSurroundingText",
        beforeLength: 1,
        afterLength: 0,
      });
      event.preventDefault();
    } else if (event.key === "Enter") {
      // console.log("[IME ENTER HIT]", {
      //   key: event.key,
      //   code: event.code,
      //   keyCode: event.keyCode,
      //   isComposing: event.isComposing,
      //   composing: this.composing,
      //   activeElement: document.activeElement,
      // });
      event.preventDefault();
      if (
        this.composing &&
        this.previousComposition &&
        this.lastCommittedCompositionId !== this.compositionId
      ) {
        this.control.send({
          type: "ime",
          op: "commitText",
          text: this.previousComposition,
        });
        this.control.send({ type: "ime", op: "finishComposingText" });
        this.lastCommittedCompositionId = this.compositionId;
        this.composing = false;
      }
      this.control.send({ type: "ime", op: "sendKeyEvent", keyCode: 66 });
    }
  }
}
