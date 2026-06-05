# Screen-off Mirroring Notes (2026-06-06)

## Summary

This note captures the final state of the screen-off mirroring investigation on Samsung / One UI devices.

Goals:

- Keep mirroring alive while the phone screen is off
- Prevent the physical screen from wake-looping
- Avoid over-aggressive wake / keyguard / input recovery paths
- Keep detailed `[SCREEN_OFF]` logs available only when verbose diagnostics is enabled

## Problem Background

The old keepalive path could sometimes revive virtual-display rendering after power-off, but it also woke the physical default display.

Observed failure pattern:

- Power key OFF
- Mirrored stream turns black
- Mirrored stream resumes
- Physical lock screen wakes
- Physical screen turns off again
- Sequence repeats

On Samsung / One UI, the opposite failure was also observed:

- If physical wake was blocked too aggressively, pure panel-off could stop mirroring completely

## Final Strategy

### 1. Separate VD keepalive from physical wake

`VirtualDisplayController.keepDisplayAwake()` now uses a VD-only keepalive path instead of the older general wake path.

Privileged side behavior:

- `keepVirtualDisplayAlive(displayId)` runs `dumpsys power set-display-state <displayId> ON`
- It skips `displayId <= 0`
- It never intentionally targets physical display 0

### 2. Add a screen-off loop guard

`ScreenOffLoopGuard` was introduced to classify screen events as:

- `USER`
- `SELF_INDUCED`

This prevents Castla's own revive / burst activity from re-triggering the normal recovery path over and over.

### 3. Use a Samsung / One UI blackout strategy

For Samsung-family devices, Castla now prefers:

- blackout overlay
- VD keepalive

instead of relying only on physical panel-off.

This strategy is implemented through `ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE`.

### 4. Reduce aggressive restore paths

During screen-off handling, the app now avoids normal use of:

- `KEYCODE_WAKEUP`
- `wm dismiss-keyguard`
- general input injection for wake purposes
- other physical wake side effects

Recovery is biased toward VD-only keepalive first.

## What Was Improved

- Physical wake-loop behavior was reduced substantially
- Screen-off mirroring remained viable through the blackout + keepalive strategy
- Single-app and dual/split mirroring scenarios remained broadly functional in testing

## What Still Remains

One issue was not fully eliminated:

- When the physical screen is turned back on, the mirrored stream may still briefly go black on some Samsung / One UI devices

We reduced this as much as possible by testing:

- delayed keepalive stop
- keeping VD keepalive alive longer
- removing direct `wakeUpDisplay(0)` from the Samsung blackout restore path
- reducing duplicate revive pulses
- deferring restore handling until real `SCREEN_ON`

Even after those changes, some wake-time black frames remained. The working conclusion is that this last behavior is likely constrained by Samsung / One UI power / capture behavior rather than by Castla's remaining app-level logic.

## Logging Policy

All screen-off investigation logs were standardized under the `[SCREEN_OFF]` prefix.

Examples:

- `[SCREEN_OFF] [SCREEN_OFF_LOOP]`
- `[SCREEN_OFF] [BLACKOUT]`
- `[SCREEN_OFF] [VD_KEEPALIVE]`
- `[SCREEN_OFF] [REVIVE]`
- `[SCREEN_OFF] [RESUME]`
- `[SCREEN_OFF] [REVIVE_REBUILD]`
- `[SCREEN_OFF] [PHYSICAL_WAKE_BLOCKED]`
- `[SCREEN_OFF] [POWER_BURST]`

Current policy:

- Verbose diagnostics OFF: these detailed screen-off logs stay quiet
- Verbose diagnostics ON: these detailed logs are emitted for debugging

Implementation note:

- `MirrorForegroundService` gates `[SCREEN_OFF]` logs with `verboseDiagnosticsEnabled`
- `PrivilegedService` reads the same setting and gates its `[SCREEN_OFF]` logs the same way

## Current Conclusion

The current code should be treated as the practical stopping point for this round:

- Keep screen-off mirroring working
- Avoid the physical wake loop
- Prefer the Samsung blackout keepalive strategy
- Do not keep adding stronger wake hacks unless a new device-specific path is proven necessary
