import type { EncodedFrame, PaneId, StreamMetadata } from '../protocol';
import { GenerationTracker } from '../runtime/GenerationTracker';

const generations = new GenerationTracker();
const workerScope = self as unknown as {
  postMessage(message: unknown, transfer?: Transferable[]): void;
};

self.onmessage = (event: MessageEvent<{ type: string; pane?: PaneId; metadata?: StreamMetadata; frame?: EncodedFrame }>) => {
  const message = event.data;
  if (message.type === 'metadata' && message.metadata) {
    generations.update(message.metadata);
    return;
  }
  if (message.type === 'frame' && message.pane && message.frame) {
    if (generations.acceptFrame(message.pane, message.frame)) {
      workerScope.postMessage({ type: 'frame', pane: message.pane, frame: message.frame }, [message.frame.payload]);
    } else {
      workerScope.postMessage({ type: 'drop', pane: message.pane });
    }
  }
};
