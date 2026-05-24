import type { ControlTransport } from '../transport/ControlTransport';

export class ImeBridge {
  private composing = false;
  private previousComposition = '';

  constructor(private readonly control: ControlTransport) {}

  compositionStart(_event: CompositionEvent): void {
    this.composing = true;
    this.previousComposition = '';
  }

  compositionUpdate(event: CompositionEvent): void {
    const text = event.data ?? '';
    this.control.send({
      type: 'ime',
      op: 'setComposingText',
      text,
      replaceChars: [...this.previousComposition].length
    });
    this.previousComposition = text;
  }

  compositionEnd(event: CompositionEvent): void {
    this.composing = false;
    const text = event.data ?? this.previousComposition;
    this.control.send({ type: 'ime', op: 'commitText', text });
    this.control.send({ type: 'ime', op: 'finishComposingText' });
    this.previousComposition = '';
  }

  input(event: Event): void {
    if (this.composing) return;
    const input = event.target as HTMLInputElement;
    if (!input.value) return;
    this.control.send({ type: 'ime', op: 'commitText', text: input.value });
    input.value = '';
  }

  keydown(event: KeyboardEvent): void {
    if (event.key === 'Backspace' && !this.composing) {
      this.control.send({ type: 'ime', op: 'deleteSurroundingText', beforeLength: 1, afterLength: 0 });
      event.preventDefault();
    }
  }
}
