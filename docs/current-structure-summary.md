# Castla Current Structure Summary

Last updated: 2026-05-25

This document summarizes the current runtime structure after the Svelte frontend migration, split-layout stabilization, pane-local video recovery work, and the latest frontend touch quarantine changes.

## Project Intent

Castla is a remote Android workspace compositor, not simple screen mirroring.

The intended model is:

- Android owns VirtualDisplays, encoder lifecycle, app/task placement, and MotionEvent injection.
- The browser owns split composition, launcher UX, fullscreen/split transitions, decoder lifecycle, and remote interaction gating.
- Layout is browser-authored and Android-interpreted.
- Video recovery should be pane-local whenever possible.
- Touch recovery should be frontend-first because browser pointer/capture state can outlive Android touch reset.

## Runtime Entry Points

Production runtime is still service-driven.

Android:

- `app/src/main/java/com/castla/mirror/service/MirrorForegroundService.kt`
- `app/src/main/java/com/castla/mirror/server/MirrorServer.kt`
- `app/src/main/java/com/castla/mirror/server/ControlSocket.kt`
- `app/src/main/java/com/castla/mirror/capture/VirtualDisplayController.kt`
- `app/src/main/java/com/castla/mirror/input/TouchInjector.kt`

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

The newer `app/src/main/java/com/castla/mirror/compositor/` tree still exists, but the live orchestration path is still centered on `MirrorForegroundService.MirroringPipeline`.

## Android Runtime

`MirrorForegroundService.MirroringPipeline` owns the active Android-side pane state:

- `primary` / `secondary` pane pipelines
- pane visibility and display tier
- VirtualDisplay creation, resize, rebuild, and release
- encoder/surface lifecycle
- app launch and app restore
- touch injector binding to the current virtual display

`MirrorServer` owns:

- static web asset serving
- `/api/apps`
- `/api/icon`
- control websocket
- per-pane video websocket
- stream metadata broadcast/replay
- keyframe request callbacks

`ControlSocket` parses browser commands, including:

- `touch`
- `touchReset`
- `layout_update`
- `launchApp`
- `requestKeyframe`
- `codec`
- `ping`

## Layout Model

`ViewportHost.svelte` is the only authoritative frontend layout sender.

The browser sends `layout_update` packets containing:

- `id`
- `width`
- `height`
- `visible`

Android derives runtime behavior from that:

- one visible pane: that pane becomes `ACTIVE`
- two visible panes: primary/selected pane is `ACTIVE`, the other visible pane is `VISIBLE`
- hidden or absent panes are `SUSPENDED`

Current frontend layout behavior:

- splitbar movement updates frontend UI immediately
- backend `layout_update` is sent only after resize end, expand, swap, host resize, or explicit layout flush
- split dimensions are aligned to 16-pixel boundaries before sending
- split mode uses `fill` coordinate mapping, single mode uses `contain`

Current Android layout behavior:

- `applyBrowserLayoutUpdate(...)` updates `paneVisibility`
- visible pane count changes are treated as stronger realignment opportunities
- `MirroringPipeline.onViewportChange(...)` forwards viewport changes into the rebuild queue
- rebuild requests are serialized through the VD hardware worker

## App Launch Model

Frontend launch flow:

1. `AppLauncher.svelte` selects target pane and layout mode.
2. `StreamRuntime.launchApp(...)` resets frontend touch session state.
3. Frontend suppresses/quarantines touch input during app launch.
4. Control websocket sends `launchApp`.
5. Android `ControlSocket` routes to `MirrorServer`.
6. `MirrorForegroundService` receives the app launch request via `AppLaunchBus`.
7. The target pane is promoted as needed.
8. `MirroringPipeline.launchAppFromWebLauncher(...)` resolves browser/internal/standard launch paths.

Android now clears the target pane `TouchInjector` state before app launch:

- this is a local injector state clear
- fallback `ACTION_CANCEL` is not sent when no Android-side pointer is tracked
- this avoids noisy `tracked=0 fallback=true` cancel injection failures

App-mounted detection still exists inside `executeAdaptiveWakeup(...)` using `getRunningTasksOnDisplay(displayId)`, but it is not currently used to gate frontend touch. Touch gating is frontend-owned.

## Touch Model

Current touch path:

1. browser pointer event
2. `TouchRouter`
3. `StreamRuntime.control.send({ type: 'touch', ... })`
4. `ControlSocket`
5. `MirrorServer.onTouchEvent(...)`
6. `MirrorForegroundService` touch listener
7. `TouchInjector`
8. `VirtualDisplayController.injectMotionEvent(...)`
9. Shizuku privileged input injection

### Frontend Touch Ownership

The latest conclusion is that the main failure mode is usually frontend/browser pointer state, not Android reset failure.

Evidence:

- F5 revives touch while Android service remains alive.
- Android `touchReset` can be logged without recovery.
- fallback `ACTION_CANCEL` can fail or succeed without reliably determining recovery.
- aggressive drag during app launch reproduces the issue.

Therefore `TouchRouter` now owns stronger browser-side touch gating:

- active pointer tracking stores both remote pointer id and browser pointer capture target
- `resetTouchState(...)` emits a local frontend reset event
- `resetTouchSession(...)` bumps frontend touch epoch before app launch
- `suppressTouchInput(...)` blocks remote touch during risky windows
- app launch enables pointer quarantine when a physical pointer/button is already down
- quarantined pointer streams are blocked before they reach viewport handlers
- `pointercancel` does not immediately release quarantine
- quarantine releases only after real pointer idle is observed through `pointerup buttons=0`, `mouseup buttons=0`, or touch idle

Important logs:

- `[CastlaTouch] suppress`
- `[CastlaTouch] waiting for pointer idle`
- `[CastlaTouch] quarantine kept after cancel`
- `[CastlaTouch] quarantine kept active`
- `[CastlaTouch] quarantine released`
- `[CastlaTouch] quarantine released all`
- `[CastlaTouch] pointer idle`

### Android TouchInjector

`TouchInjector` still maintains Android-side active pointer state.

Current behavior:

- duplicate `down` clears stale Android-side state
- overflow clears stale Android-side state
- `updateDimensions(...)` cancels active pointers if tracked
- `release()` clears local state
- `release()` no longer sends fallback `ACTION_CANCEL` when no active pointer is tracked

This keeps Android cleanup useful while avoiding synthetic cancel events with no real tracked pointer.

## Video / Decoder Model

Default stream path:

- H.264 over per-pane video websocket
- `JMuxerBackend`
- MSE video element playback

WebCodecs still exists as an optional path but is not the default production path.

`StreamRuntime.attachVideo(...)` owns per-pane `VideoTransport` instances:

- creates a video socket only when the pane has listeners
- closes and removes the pane video socket when the last listener detaches
- prevents hidden secondary video sockets from lingering forever

`ViewportPane.svelte` owns pane decoder lifecycle:

- attach/detach video frames
- create/destroy decoder backend
- request keyframes
- run stall watchdog
- report decoder status

Current recovery model:

- generation changes refresh pane decoder
- frame rejection requests a keyframe after throttling
- stall watchdog first attempts pane-local recovery
- `recoverPaneStream(pane)` requests keyframe and updates health state
- full decoder refresh is delayed until repeated recovery attempts
- control reconnect no longer tears down the whole frontend session by itself
- pane stall recovery no longer performs broad control/video soft reconnect by default

This was changed to stop secondary pane flicker/reconnect loops.

## Transport Model

### ControlTransport

Responsibilities:

- open/reopen control websocket
- queue outbound messages while closed
- emit connection state
- send `ping`
- accept `pong`
- reconnect only after a long pong timeout

Heartbeat is intentionally relaxed so temporary control hiccups do not reset video/decoder state aggressively.

### VideoTransport

Responsibilities:

- open per-pane video websocket
- parse binary frame packets
- reconnect on unexpected close
- avoid reconnect scheduling when intentionally closed
- support `reconnectNow()` for explicit recovery

## Frontend Runtime Model

Startup:

1. `App.svelte` creates `StreamRuntime`, `BrowserCompositor`, `TouchRouter`, and `ImeBridge`.
2. `BrowserCompositor.start()` starts `StreamRuntime`.
3. `StreamRuntime` opens control websocket.
4. Visible `ViewportPane` instances attach per-pane video sockets.
5. `ViewportHost` observes host size and sends layout.

`StreamRuntime` responsibilities:

- own control transport
- own pane video transports
- replay last layout after control reconnect
- request codec/keyframe after reconnect for attached panes
- track stream generations
- track frontend touch session epoch
- send `touchReset`
- suppress touch input around risky transitions
- perform pane-local stream recovery

## BrowserCompositor

`BrowserCompositor.ts` bridges stream metadata into `compositorStore`.

Current important behavior:

- stream metadata updates committed pane state
- session-level committed reset is no longer tied to transient control disconnect
- this avoids periodic frontend visual reset/flicker caused by heartbeat/control reconnect behavior

## Launcher UI

`AppLauncher.svelte` includes:

- grouped app list
- cached app bootstrap
- favorites
- autorun toggles
- long-press drag
- drag-to-primary / drag-to-secondary
- drag-to-favorite / autorun / remove
- app pair creation by dropping app onto app
- app pair pseudo-items
- app pair settings modal

Launcher long-press uses its own pointer capture. Remote viewport touch quarantine is separate and applies to remote stream input, not ordinary launcher interaction.

## Current Known Weak Spots

Important current risks:

1. Browser pointer/capture behavior during app launch is still the most sensitive area.
2. `pointercancel` can arrive while the physical button/finger is still down, so it must not be trusted as idle by itself.
3. `getRunningTasksOnDisplay(...)` may fail to detect some apps even when the launch visually succeeds.
4. Some map/navigation apps behave differently during VirtualDisplay resize, app launch, and focus restore.
5. Frontend and Android still use separate control/video paths, so local browser state must remain disciplined.

## Current Design Direction

The current architecture is moving toward:

- browser-owned composition and touch gating
- Android-owned VD/app/encoder execution
- pane-local video recovery
- fewer synthetic touch/wakeup hacks
- no server-side touch block/unblock state machine unless proven necessary
- explicit frontend quarantine for risky browser pointer streams

The most important current debugging rule:

- if F5 revives touch without restarting Android service, investigate frontend pointer/capture/session state first.
