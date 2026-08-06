import type { EncodedFrame } from '../protocol';

export interface DecoderBackend {
  initialize(target: HTMLCanvasElement | HTMLVideoElement): Promise<void>;
  setVideoLatencyMs(latencyMs: number): void;
  decode(frame: EncodedFrame): void;
  reset(): void;
  destroy(): void;
}
