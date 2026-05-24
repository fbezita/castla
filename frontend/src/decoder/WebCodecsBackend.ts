import type { EncodedFrame } from '../protocol';
import type { DecoderBackend } from './DecoderBackend';

export class WebCodecsBackend implements DecoderBackend {
  private decoder?: VideoDecoder;
  private canvas?: HTMLCanvasElement;
  private context?: CanvasRenderingContext2D;
  private configPayload?: ArrayBuffer;
  private decoding = false;
  private lastSequence?: number;
  private onFrame?: () => void;

  constructor(onFrame?: () => void, private readonly onRecover?: () => void) {
    this.onFrame = onFrame;
  }

  async initialize(target: HTMLCanvasElement | HTMLVideoElement): Promise<void> {
    if (!(target instanceof HTMLCanvasElement)) throw new Error('WebCodecs backend requires canvas');
    if (!('VideoDecoder' in window)) throw new Error('WebCodecs unavailable');
    this.canvas = target;
    this.context = target.getContext('2d', { alpha: false }) ?? undefined;
    this.decoder = new VideoDecoder({
      output: (frame) => this.render(frame),
      error: (error) => {
        console.error('[WebCodecs]', error);
        this.reset();
        this.onRecover?.();
      }
    });
    this.decoder.configure({
      codec: 'avc1.42001e',
      optimizeForLatency: true,
      hardwareAcceleration: 'prefer-hardware'
    });
  }

  decode(frame: EncodedFrame): void {
    if (!this.decoder || this.decoder.state === 'closed') return;
    if (frame.config) {
      this.configPayload = frame.payload;
      return;
    }
    if (!frame.keyFrame && !this.decoding) {
      this.lastSequence = frame.sequence;
      return;
    }
    if (frame.keyFrame && this.configPayload) {
      this.decoding = true;
    }
    if (!this.configPayload && frame.keyFrame) return;
    if (this.lastSequence !== undefined && frame.sequence !== ((this.lastSequence + 1) & 0xffff) && !frame.keyFrame) {
      this.decoding = false;
      this.onRecover?.();
      this.lastSequence = frame.sequence;
      return;
    }
    this.lastSequence = frame.sequence;
    const payload = frame.keyFrame && this.configPayload
      ? concat(this.configPayload, frame.payload)
      : frame.payload;
    if (!frame.keyFrame && this.decoder.decodeQueueSize > 4) return;
    this.decoder.decode(new EncodedVideoChunk({
      type: frame.keyFrame ? 'key' : 'delta',
      timestamp: frame.serverTimestampMs * 1000,
      data: payload
    }));
  }

  reset(): void {
    this.decoder?.reset();
    this.decoding = false;
    this.lastSequence = undefined;
  }

  destroy(): void {
    this.decoder?.close();
    this.decoder = undefined;
  }

  private render(frame: VideoFrame): void {
    if (!this.canvas || !this.context) {
      frame.close();
      return;
    }
    if (this.canvas.width !== frame.displayWidth) this.canvas.width = frame.displayWidth;
    if (this.canvas.height !== frame.displayHeight) this.canvas.height = frame.displayHeight;
    this.context.drawImage(frame, 0, 0, this.canvas.width, this.canvas.height);
    frame.close();
    this.onFrame?.();
  }
}

function concat(a: ArrayBuffer, b: ArrayBuffer): ArrayBuffer {
  const out = new Uint8Array(a.byteLength + b.byteLength);
  out.set(new Uint8Array(a), 0);
  out.set(new Uint8Array(b), a.byteLength);
  return out.buffer;
}
