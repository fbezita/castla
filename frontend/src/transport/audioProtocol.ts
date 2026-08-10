export type AudioCapabilities = { type: "audioCapabilities"; opus: boolean };

const AUDIO_OUTPUT_DELAY_MAX_MS = 1000;
const AUDIO_JITTER_BUFFER_SECONDS = 0.02;

export function clampAudioOutputDelayMs(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(AUDIO_OUTPUT_DELAY_MAX_MS, Math.max(0, Math.round(value)));
}

export function audioOutputBufferSeconds(outputDelayMs: number): number {
  return AUDIO_JITTER_BUFFER_SECONDS + clampAudioOutputDelayMs(outputDelayMs) / 1000;
}

export function audioDelaySeconds(outputDelayMs: number): number {
  return clampAudioOutputDelayMs(outputDelayMs) / 1000;
}

export function audioSignalPeak(samples: Float32Array): number {
  let peak = 0;
  for (const sample of samples) peak = Math.max(peak, Math.abs(sample));
  return peak;
}

export function readAudioDelayControl(message: unknown): number | null {
  if (!message || typeof message !== 'object') return null;
  const control = message as { type?: unknown; outputDelayMs?: unknown };
  if (control.type !== 'audioDelay') return null;
  const value = Number(control.outputDelayMs);
  return Number.isFinite(value) ? clampAudioOutputDelayMs(value) : null;
}

export function shouldFallbackFromOpus(
  receivedPackets: number,
  decodedFrames: number,
  packetThreshold = 25,
): boolean {
  return receivedPackets >= packetThreshold && decodedFrames === 0;
}

export async function buildAudioCapabilities(
  probe: () => Promise<{ supported?: boolean }>,
): Promise<AudioCapabilities> {
  try {
    const result = await probe();
    return { type: "audioCapabilities", opus: result.supported === true };
  } catch {
    return { type: "audioCapabilities", opus: false };
  }
}

export class AudioStreamProtocol {
  private configuredStreamId: number | null = null;

  acceptConfig(streamId: number): void {
    this.configuredStreamId = streamId;
  }

  acceptPacket(streamId: number): boolean {
    return this.configuredStreamId !== null && streamId === this.configuredStreamId;
  }

  reset(): void {
    this.configuredStreamId = null;
  }
}
