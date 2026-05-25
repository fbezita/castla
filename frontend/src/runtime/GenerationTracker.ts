import type { EncodedFrame, PaneId, StreamMetadata } from '../protocol';

export class GenerationTracker {
  private metadata = new Map<PaneId, StreamMetadata>();
  private lastSequence = new Map<PaneId, number>();
  private mismatchCount = new Map<PaneId, number>();
  private awaitingKeyframe = new Map<PaneId, boolean>();

  update(metadata: StreamMetadata): void {
    const previous = this.metadata.get(metadata.sessionId);
    this.metadata.set(metadata.sessionId, metadata);
    if (!previous || previous.generation !== metadata.generation) {
      this.lastSequence.delete(metadata.sessionId);
      this.awaitingKeyframe.set(metadata.sessionId, true);
    }
  }

  resetForKeyframe(pane: PaneId): void {
    this.lastSequence.delete(pane);
    this.awaitingKeyframe.set(pane, true);
  }

  acceptFrame(pane: PaneId, frame: EncodedFrame): boolean {
    if (frame.config) return true;
    const meta = this.metadata.get(pane);
    if (meta && !meta.streamReady) return false;
    if (this.awaitingKeyframe.get(pane) === true && !frame.keyFrame) {
      this.mismatchCount.set(pane, (this.mismatchCount.get(pane) ?? 0) + 1);
      return false;
    }
    const last = this.lastSequence.get(pane);
    if (last !== undefined && frame.sequence !== ((last + 1) & 0xffff) && !frame.keyFrame && !frame.config) {
      this.mismatchCount.set(pane, (this.mismatchCount.get(pane) ?? 0) + 1);
      return false;
    }
    this.lastSequence.set(pane, frame.sequence);
    if (frame.keyFrame) {
      this.awaitingKeyframe.delete(pane);
    }
    return true;
  }

  isFirstFrameReady(pane: PaneId): boolean {
    return this.metadata.get(pane)?.firstFrameReady === true;
  }
}
