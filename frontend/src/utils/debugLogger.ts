import type { StreamRuntime } from "../runtime/StreamRuntime";

export interface DebugEntry {
  ts: number;
  message: string;
  data?: any;
}

const debugBuffer: DebugEntry[] = [];
let lastDumpTime = 0;

// Persistent on/off toggle (default: true)
let loggingEnabled = typeof localStorage !== "undefined" && localStorage.getItem("castla_debug_logging") !== "false";

export function isLoggingEnabled(): boolean {
  return loggingEnabled;
}

export function setLoggingEnabled(enabled: boolean) {
  loggingEnabled = enabled;
  if (typeof localStorage !== "undefined") {
    localStorage.setItem("castla_debug_logging", enabled ? "true" : "false");
  }
  console.info(`[debugLogger] Diagnostics logging has been ${enabled ? "ENABLED" : "DISABLED"}.`);
  if (!enabled) {
    debugBuffer.length = 0; // Clear the memory buffer
  }
}

export function debugLog(message: string, data?: any) {
  if (!loggingEnabled) return;

  debugBuffer.push({
    ts: Date.now(),
    message,
    data,
  });

  if (debugBuffer.length > 1000) {
    debugBuffer.shift();
  }
}

export function getLogs(): DebugEntry[] {
  return [...debugBuffer];
}

export function triggerDump(runtime: StreamRuntime, reason: string) {
  if (!loggingEnabled) return;

  const now = Date.now();
  if (now - lastDumpTime < 10000) {
    // 10 seconds cooldown to avoid continuous uploads
    return;
  }
  lastDumpTime = now;

  debugLog(`[DumpTrigger] Triggered dump. Reason: ${reason}`);

  try {
    runtime.control.send({
      type: "debugDump",
      source: "tesla",
      logs: getLogs(),
    });
    console.info(`[debugLogger] Successfully sent debugDump to backend. Reason: ${reason}`);
  } catch (error) {
    console.error("[debugLogger] Failed to upload debug dump", error);
  }
}
