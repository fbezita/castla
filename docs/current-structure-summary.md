# Castla Current Structure Summary

Last updated: 2026-05-26

This document is a handoff summary for the next refactoring pass.

It focuses on the code paths that are actually live today, the failure pattern reproduced in logs, the mitigations already applied, and the structural changes that now look necessary.

## Project Intent

Castla is a remote Android workspace compositor, not simple phone mirroring.

The intended ownership model is still:

- Android owns VirtualDisplays, encoder lifecycle, app/task placement, and MotionEvent injection.
- The browser owns split composition, launcher UX, pane decoder lifecycle, and input shaping before packets are sent.
- Layout is browser-authored and Android-interpreted.
- Pane-local stream recovery is preferred over broad session resets.

## Live Runtime Entry Points

The production path is still centered on the service monolith plus the Svelte frontend runtime.

Android:

- `app/src/main/java/com/castla/mirror/service/MirrorForegroundService.kt`
- `app/src/main/java/com/castla/mirror/server/MirrorServer.kt`
- `app/src/main/java/com/castla/mirror/server/ControlSocket.kt`
- `app/src/main/java/com/castla/mirror/capture/VirtualDisplayController.kt`
- `app/src/main/java/com/castla/mirror/input/TouchInjector.kt`
- `app/src/main/java/com/castla/mirror/service/AdaptiveBitrateManager.kt`

Frontend:

- `frontend/src/App.svelte`
- `frontend/src/runtime/StreamRuntime.ts`
- `frontend/src/components/ViewportHost.svelte`
- `frontend/src/components/ViewportPane.svelte`
- `frontend/src/touch/TouchRouter.ts`
- `frontend/src/transport/ControlTransport.ts`
- `frontend/src/transport/VideoTransport.ts`

Built frontend assets are copied to:

- `app/src/main/assets/web`

The `app/src/main/java/com/castla/mirror/compositor/` tree still exists, but the live orchestration path is still `MirrorForegroundService.MirroringPipeline`.

## Current Android Structure

`MirrorForegroundService.MirroringPipeline` currently owns too many responsibilities at once:

- VirtualDisplay creation, resize, rebuild, and release
- encoder and surface lifecycle
- touch injector attachment
- app launch and app restore
- display wakeup and keyframe nudges
- fallback and self-healing hooks
- app-mounted verification

Important service-wide pieces:

- `vdRequestChannel` + `startVdHardwareWorker()` serialize hardware rebuild work
- `MirrorServer` exposes control/video channels and callbacks
- `ControlSocket` routes browser messages into service listeners
- `AdaptiveBitrateManager` and other policy managers can still trigger rebuilds

## Current Frontend Structure

Frontend runtime responsibilities are split like this:

- `TouchRouter`
  - normalizes coordinates
  - coalesces and rate-limits `MOVE`
  - tracks gesture stats
  - sends `touch` packets over control websocket
- `ViewportHost`
  - is now the singleton pointer listener surface
  - maps pointer events to panes
  - owns split layout UI and layout flush timing
- `ViewportPane`
  - owns decoder lifecycle and stall watchdog
- `StreamRuntime`
  - owns control/video transports
  - tracks generations and pane health
  - currently still emits decoder recovery signals on frame rejection/stall

## Touch Path Today

Current path:

1. browser pointer event
2. `ViewportHost.handlePointer(...)`
3. `TouchRouter.pointer(...)`
4. `StreamRuntime.control.send({ type: 'touch', ... })`
5. `ControlSocket`
6. `MirrorServer.onTouchEvent(...)`
7. `MirrorForegroundService` touch listener
8. `TouchInjector`
9. `VirtualDisplayController.injectMotionEventWithResult(...)`
10. privileged `InputManager.injectInputEvent()`

### Frontend Touch Changes Already Applied

These were already done before the next refactor:

- singleton pointer listener structure in `ViewportHost`
- hard `MOVE` rate cap in `TouchRouter`
- frontend `MOVE` dedup/drop
- Android `MOVE` dedup/throttle safety net in `TouchInjector`
- reduced frontend logging so only `gesture complete` and warnings/errors remain useful

### Current Touch Conclusion

The original `MOVE flooding` problem was real, but it is no longer the main blocker.

Evidence from the latest logs:

- `movePacketsPerSecond` is now usually around `2..6`, not `15..22`
- `gesturePackets` per swipe are relatively stable, usually around `8..13`
- `inject_privileged durationMs` is usually small, often `0.5..1.5ms`

So the browser/input packet density problem has been reduced significantly.

## Decoder / Recovery Path Today

Frontend decoder health still feeds recovery behavior:

- `ViewportPane` runs a stall watchdog and can call `runtime.recoverPaneStream(pane)`
- `StreamRuntime.dispatchFrame(...)` can emit `frameRejected`
- `StreamRuntime.recoverRejectedStream(...)` can request a keyframe after repeated rejects

Recent mitigation applied:

- frame reject recovery is now less aggressive
- a single reject no longer triggers immediate recovery
- repeated rejects within a short window are required before `requestKeyframeAfterReject`

This reduced noisy recovery, but the path is still structurally coupled to Android rebuild behavior.

## Rebuild Path Today

This is the most important current problem.

Rebuilds can still be triggered from multiple places:

- `MirroringPipeline.onViewportChange(...)`
- app launch preparation / self-healing branches
- display density listener
- codec/profile change handling
- `AdaptiveBitrateManager.applyPipelineScale(...)`
- fallback/recovery/self-healing branches inside `MirrorForegroundService`

Even though hardware execution is serialized through `vdRequestChannel`, **the requests themselves are still generated from many layers**.

### Recent Mitigation Applied

`MirroringPipeline.rebuild(...)` now checks whether any pipeline has active touch interaction and defers enqueue for up to about `1.5s`.

This logs:

- `[InputTrace] touch_guard ... reason=rebuild_defer ...`

This is a guardrail, not a structural fix.

## Failure Pattern Confirmed in Logs

The latest logs point to a specific sequence:

1. repeated app switching across map/navigation apps
2. decoder/frame anomalies such as `frameRejected`
3. `secondary` and then `primary` rebuilds occur
4. touch in progress gets canceled or interrupted around rebuild
5. after rebuild / post-rebuild keyframe, fresh `DOWN` eventually becomes:
   - `inject_privileged ... result=false`

Important observation:

- the eventual failure is **not** preceded by high move throughput anymore
- the failure is strongly correlated with **rebuild/reconfigure windows**
- `inject_false_probe` still shows the correct app and focused display, but stale task/session traces remain on the same display id

Current leading hypothesis:

- touch input is being invalidated by display/window/task state churn during or right after rebuild
- multiple subsystems can currently request recovery/rebuild independently
- this creates racey interaction between stream recovery, display rebuild, and active touch injection

## What We Learned From the Latest Logs

These points should be treated as established for the next refactor:

1. The dominant issue is no longer raw browser `pointermove` volume.
2. `injectInputEvent()` failure can still happen even when move volume is low and inject durations are short.
3. The strongest correlation is:
   - `frameRejected` or other recovery pressure
   - followed by `rebuild_begin(...)`
   - followed by `injectInputEvent(...)=false`
4. The current architecture lets too many layers ask for rebuild directly.

## Why A Structural Refactor Is Now Justified

At this point, the problem is not one isolated bug.

The issue is that:

- input path
- decoder recovery
- layout transitions
- app launch/restore
- VirtualDisplay rebuild

are all partially entangled.

The code already has mitigation patches, but the logs suggest the real fix needs stronger separation of concerns.

## Refactoring Direction For The Next Session

The next refactor should aim for a single rebuild/recovery control plane.

### Target Direction

1. Separate **input session state** from **display recovery state**
2. Route all rebuild intents through one coordinator API
3. Prevent direct rebuild calls from decoder / thermal / viewport / codec handlers
4. Treat active touch as a first-class gate for rebuild approval

### Recommended First Refactor Step

Create a single rebuild request boundary such as:

- `requestRebuild(reason, priority, pane, width, height, options)`

and move policy into that boundary:

- reject or defer while touch is active
- coalesce duplicate requests
- apply cooldowns
- choose which requests are allowed during launch / recovery / split transitions

### Direct `rebuild()` Call Sites That Need To Be Collapsed

At minimum, inspect and route these through a central coordinator:

- `MirroringPipeline.onViewportChange(...)`
- service display density listener
- codec/profile switch logic
- `AdaptiveBitrateManager.applyPipelineScale(...)`
- fallback / self-healing / restore paths
- any decoder-recovery-triggered path that can eventually lead to rebuild

## Suggested State Separation

The next design pass should probably separate:

### Input state machine

- `Idle`
- `TouchActive`
- `Cancelling`
- `Rejected`

### Display / recovery state machine

- `Idle`
- `Launching`
- `Stable`
- `Recovering`
- `Rebuilding`
- `Suspended`

Then explicitly define allowed transitions such as:

- no rebuild while `TouchActive`
- decoder recovery may request keyframe while `TouchActive`
- decoder recovery may request rebuild only after touch ends

## Current Temporary Guards In Place

These are important so the next session does not accidentally remove them without replacement:

- frontend singleton pointer routing
- frontend hard `MOVE` cap
- frontend and Android `MOVE` dedup/drop
- touch-time fallback cancellation / skip
- `frameRejected` recovery throttling
- touch-time rebuild defer

They are tactical protections, not the final architecture.

## Handoff Summary

If the next session starts from one sentence, it should be this:

> The urgent structural problem is not raw move spam anymore; it is that decoder/layout/recovery code can still trigger rebuild pressure that collides with active touch and eventually corrupts the input path on the target VirtualDisplay.

### 2026-05-26 Refactor Pass Update

The first rebuild boundary has now been introduced on Android:

- `MirrorForegroundService.requestRebuild(RebuildRequest)` is the central coordinator before requests enter `vdRequestChannel`
- direct rebuild callers in viewport, density, launch self-heal, adaptive bitrate, and thermal paths now route through `MirroringPipeline.requestRebuild(...)`
- rebuild requests now carry `reason` and `priority`
- the existing active-touch defer behavior has moved into the coordinator
- duplicate same-pane rebuild requests are coalesced over a short window before hardware enqueue

Remaining follow-up work:

1. inventorying every `rebuild(...)` caller
2. reducing direct cross-layer recovery triggers
3. promoting decoder recovery into explicit keyframe-first / rebuild-later policy
4. formalizing input and display recovery state machines

### 2026-05-26 Touch Fix Outcome

The hardreset touch freeze is now resolved.

Temporary hardreset tracing was used to isolate the real failure boundary and has since been
trimmed back out of the runtime path.

#### Root Cause Confirmed

The key issue was not browser reconnect, control transport, app launch routing, or display
focus drift.

The real problem was that browser `pointerId` values were being passed straight through into
Android `MotionEvent.PointerProperties.id`.

After repeated reload/hardreset cycles, the browser could send larger ids such as `36`, while
Android input injection remained happy with compact local ids such as `0`, `1`, `2`.

That mismatch explained the observed behavior:

- real user touches after hardreset could be rejected at `injectInputEvent(...)`
- synthetic internal nudges using small local ids could still succeed
- full reload often worked because the next browser pointer id happened to be small again

#### Fix Kept In The Runtime

`TouchInjector` now remaps browser pointer ids to Android-local pointer ids for the lifetime
of the active gesture state.

Current behavior:

- browser ids remain the external protocol ids
- Android injection uses compact local ids within the supported pointer range
- local ids are released on `UP`, `CANCEL`, and injector reset/release paths

This is the behavior-changing fix that should remain.

#### Cleanup Applied After Confirmation

The temporary hardreset investigation scaffolding is no longer considered part of the
intended structure:

- hardreset generation tagging on touch packets
- first-packet hardreset tracing
- dispatcher probe logging used during the hardreset investigation
- inject source tagging used only for diagnosis

The normal input and rebuild logs remain, but the special-case investigation logging has been
reduced again.

#### Current Touch Handoff

If the next session starts from one sentence, it should be this:

> The hardreset touch freeze was fixed by remapping browser pointer ids to Android-local pointer ids inside `TouchInjector`; the remaining touch protections worth keeping are the existing move dedup/throttle guard and the rebuild defer guard while touch is active.
