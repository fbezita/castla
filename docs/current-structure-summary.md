# Castla Current Structure Summary

Last updated: 2026-05-25

This document summarizes the current real runtime structure of the project after the Svelte frontend migration, the stream-generation stabilization work, and the ongoing launcher/App Pair restoration work.

## Project Intent

Castla is a remote Android workspace compositor rather than simple phone-screen mirroring.

The intended model is:

- Android owns persistent VirtualDisplays and app/task placement.
- The browser owns viewport composition, split ratio, and remote interaction UX.
- Stream transitions are synchronized with generation metadata and first-frame readiness.
- The remote screen should stay usable even when app layout, split state, or browser connections change.

## Current Runtime Shape

The runtime is still hybrid.

Android production flow is still centered in:

- `app/src/main/java/com/castla/mirror/service/MirrorForegroundService.kt`
- `app/src/main/java/com/castla/mirror/server/MirrorServer.kt`
- `app/src/main/java/com/castla/mirror/server/ControlSocket.kt`
- `app/src/main/java/com/castla/mirror/capture/VirtualDisplayController.kt`

Newer compositor/session abstractions exist under:

- `app/src/main/java/com/castla/mirror/compositor/`

but they are still architectural scaffolding more than the live control center.

Frontend production assets now come from:

- `frontend/src`

and are built into:

- `app/src/main/assets/web`

The old plain JS asset tree has largely been replaced by the Svelte + TypeScript frontend, but legacy behavior is still being restored feature-by-feature in the new UI.

## Android Structure

### Main Active Runtime Files

- `app/src/main/java/com/castla/mirror/service/MirrorForegroundService.kt`
- `app/src/main/java/com/castla/mirror/server/MirrorServer.kt`
- `app/src/main/java/com/castla/mirror/server/ControlSocket.kt`
- `app/src/main/java/com/castla/mirror/capture/VideoEncoder.kt`
- `app/src/main/java/com/castla/mirror/capture/VirtualDisplayController.kt`

### Important Compositor Scaffolding

- `DisplayTier.kt`
- `DisplaySessionRegistry.kt`
- `PersistentVirtualDisplaySession.kt`
- `EncoderBudgetManager.kt`
- `ResourcePolicyManager.kt`
- `LayoutCoordinator.kt`
- `LifecycleStateMachine.kt`
- `ServerLayer.kt`
- `StreamGenerationState.kt`

### Important Reality Check

The production lifecycle is still effectively driven by `MirrorForegroundService.MirroringPipeline`.

The service currently owns:

- `primary` / `secondary` pipelines
- browser-driven layout updates
- VirtualDisplay rebuild scheduling
- encoder lifecycle
- app launching
- suspend/visible/active tier transitions

## Current Android Lifecycle Model

The service currently supports:

- `primary` and `secondary` panes
- generation-based stream metadata
- first-frame signaling
- browser-driven size and visibility changes
- sequentialized VirtualDisplay rebuild work
- stale encoder-start suppression

Important methods to know:

- `onBrowserConnected()`
- `applyBrowserLayoutUpdate(...)`
- `MirroringPipeline.onViewportChange(...)`
- `MirroringPipeline.rebuild(...)`
- `MirroringPipeline.executeActualRebuild(...)`
- `MirroringPipeline.setTier(...)`

### Current Layout Interpretation

The browser now sends only:

- `id`
- `width`
- `height`
- `visible`

The server no longer needs browser `layoutMode` to decide pane state.

Current tier behavior is:

- if only one pane is visible, that pane becomes `ACTIVE`
- if two panes are visible, `primary` becomes `ACTIVE` and `secondary` becomes `VISIBLE`
- hidden or absent panes are suspended

This is important because fullscreen vs split is now treated as a browser composition concern first, not a protocol mode.

## MirrorServer Structure

`MirrorServer` is still monolithic in implementation, but conceptually it now provides:

- static asset serving
- `/api/apps`
- `/api/icon`
- control websocket
- per-pane video websocket
- generation metadata broadcast/replay

Important stream metadata methods already in place:

- `beginStreamGeneration(...)`
- `markFirstFrameReady(...)`
- `pauseStream(...)`

The latest `streamMetadata` is replayed to newly connected control sockets, which is now a required part of the frontend reconnect flow.

## Frontend Structure

The active frontend lives in:

- `frontend/src`

Main folders:

- `components/`
- `compositor/`
- `decoder/`
- `ime/`
- `runtime/`
- `stores/`
- `touch/`
- `transport/`
- `viewport/`
- `workers/`

Key active files:

- `frontend/src/App.svelte`
- `frontend/src/components/AppLauncher.svelte`
- `frontend/src/components/ViewportHost.svelte`
- `frontend/src/components/ViewportPane.svelte`
- `frontend/src/components/DiagnosticsOverlay.svelte`
- `frontend/src/compositor/BrowserCompositor.ts`
- `frontend/src/runtime/StreamRuntime.ts`
- `frontend/src/runtime/GenerationTracker.ts`
- `frontend/src/runtime/StreamHealthMonitor.ts`
- `frontend/src/decoder/JMuxerBackend.ts`
- `frontend/src/transport/ControlTransport.ts`
- `frontend/src/transport/VideoTransport.ts`
- `frontend/src/stores/compositorStore.ts`
- `frontend/src/touch/TouchRouter.ts`

## Current Frontend Runtime Model

Current flow:

1. `App.svelte` creates `StreamRuntime`, `BrowserCompositor`, `TouchRouter`, and `ImeBridge`.
2. `BrowserCompositor` starts `StreamRuntime`.
3. `StreamRuntime` opens:
   - control websocket
   - per-pane video websocket(s)
4. `BrowserCompositor` updates `compositorStore` using `streamMetadata`.
5. `ViewportHost` renders visible panes and is now the single source of `layout_update`.
6. `ViewportPane` attaches a decoder per pane.
7. The default decoder backend is `JMuxerBackend`.

### Important Frontend Decisions Now in Place

- `ViewportPane` no longer sends per-pane layout updates on its own.
- `ViewportHost` sends the authoritative full layout packet.
- single-pane mode explicitly sends the hidden opposite pane too.
- reconnect now replays the last layout from `StreamRuntime`.
- reconnect also re-requests codec setup and keyframes.
- when disconnected, committed frames are hidden so stale images do not remain on screen.

## Current Decoder and Stream Behavior

Default production decoder path:

- H.264 over WebSocket
- `JMuxerBackend`
- MSE video element playback

WebCodecs still exists as an optional path, but it is not the default production mode.

Important stabilization already applied:

- control socket queues messages until open
- latest layout is replayed after reconnect
- latest `streamMetadata` is replayed to fresh control sockets
- generation changes trigger decoder refresh
- stale frames are hidden when stream commitment is lost
- noisy decoder-status events are filtered so only meaningful faults remain

## Current Logging and Diagnostics

Frontend decoder diagnostics are now intentionally much quieter.

Useful log tags:

- `CastlaDecoder`
- `MirrorServer`
- `ControlSocket`
- `MirrorService`
- `VideoEncoder`

Noise reductions already applied:

- normal decoder lifecycle events are filtered in frontend and Android logging
- full control text payload logging was removed
- repeated dynamic encoder-param logs are suppressed unless values really change

Important backend race fix already applied:

- stale encoder `start()` callbacks are ignored using per-rebuild encoder session tokens

## Current UI Structure

The active remote UI is now built around:

- `ViewportHost` for pane composition
- `ViewportPane` for stream surfaces
- `AppLauncher` for the right-side drawer launcher
- `DiagnosticsOverlay` for debug visibility

### Side Drawer Status

The right-side launcher drawer is the main user-facing work surface still under restoration.

Currently implemented in Svelte:

- grouped app list
- favorites
- autorun toggles
- cached app-list startup for faster drawer opening
- long-press drag
- drawer auto-scroll while dragging
- App Pair creation by dropping one app onto another
- App Pair pseudo-items
- App Pair edit dialog
- App Pair drag to `Auto-run`
- App Pair drag to `Remove`

### Current App Pair Model

`AppLauncher.svelte` currently stores App Pairs in:

- `localStorage.castla_app_pairs`

It supports:

- pair pseudo-app rendering
- click-to-launch pair
- drag app over app to create pair
- `⚙️` edit dialog with:
  - swap
  - save
  - dissolve
  - cancel
- drag pair to autorun to assign left/right autorun slots
- drag pair to remove to dissolve the pair

### Important Launcher Reality Check

The launcher is the area with the highest amount of recent regression/restore work.

It is much closer to the legacy behavior again, but it is still the part of the system most likely to have UX mismatches compared with the pre-refactor asset-based implementation.

## Current Known Working State

As of this summary:

- `svelte-check` passes
- frontend production build passes
- frontend assets are copied into Android assets through Gradle
- `compileDebugKotlin` passes
- mirroring renders in both panes
- splitbar movement works visually
- layout updates are browser-authoritative
- reconnect no longer leaves stale frames visible by default
- App Pair drag creation, edit, autorun, and remove flows exist again in the Svelte drawer

## Current Known Risk Areas

These areas still need care:

- exact visual fidelity of the restored side drawer compared with the old asset-based launcher
- split layout correctness for some real Android apps, especially map/navigation apps
- fullscreen promotion vs app-internal relayout behavior
- drawer drag feel compared with the legacy implementation
- reconnect behavior under real flaky network conditions

## Important Files for the Next Chat

If continuing work in a new chat, start with:

- `frontend/src/components/AppLauncher.svelte`
- `frontend/src/components/ViewportHost.svelte`
- `frontend/src/components/ViewportPane.svelte`
- `frontend/src/compositor/BrowserCompositor.ts`
- `frontend/src/runtime/StreamRuntime.ts`
- `frontend/src/runtime/GenerationTracker.ts`
- `frontend/src/transport/ControlTransport.ts`
- `frontend/src/transport/VideoTransport.ts`
- `app/src/main/java/com/castla/mirror/server/MirrorServer.kt`
- `app/src/main/java/com/castla/mirror/server/ControlSocket.kt`
- `app/src/main/java/com/castla/mirror/service/MirrorForegroundService.kt`

If restoring old launcher behavior, these legacy references are still useful:

- `app/build/intermediates/assets/release/mergeReleaseAssets/web/js/main/main.launcher.render.split.js`
- `app/build/intermediates/assets/release/mergeReleaseAssets/web/js/main/main.dragdrop.js`
- `app/build/intermediates/assets/release/mergeReleaseAssets/web/js/main/main.dragdrop.handler.js`
- `app/build/intermediates/assets/release/mergeReleaseAssets/web/js/main/main.dragdrop.action.js`

## Recommended Next Steps

Recommended order:

1. Finish side-drawer UX restoration against the legacy launcher behavior.
2. Verify real-device split/fullscreen behavior for map/navigation apps.
3. Tighten reconnect validation under actual disconnect/reconnect cycles.
4. Continue shrinking `MirrorForegroundService` responsibilities toward compositor/session abstractions.
5. Only after launcher stability is good, revisit broader UI polish.

## Short Handoff

Castla now runs on a hybrid architecture where Android production behavior is still centered in `MirrorForegroundService` and `MirrorServer`, while the browser frontend has been migrated to Svelte + TypeScript and is authoritative for viewport layout. Stream generation replay, reconnect layout replay, stale-frame hiding, and quieter diagnostics are in place. The biggest remaining churn is no longer the decoder path but the side-drawer launcher and App Pair UX, which is being restored against the old asset-based implementation while keeping the newer stream/runtime architecture stable.
